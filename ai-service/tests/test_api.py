import asyncio
import json
import os
from copy import deepcopy
from typing import Any

import httpx
import pytest

os.environ["AI_PROVIDER"] = "fixture"
os.environ["AI_SERVICE_INTERNAL_TOKEN"] = "ai-service-test-token"

from app.main import analysis_service, app
from app.schemas import AnalysisProvider, RiskAnalysisRequest
from app.service import RiskAnalysisService


def call_api(
    method: str,
    path: str,
    json: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    async def send() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as client:
            return await client.request(
                method,
                path,
                json=json,
                headers={
                    "Authorization": "Bearer ai-service-test-token",
                    **(headers or {}),
                },
            )

    return asyncio.run(send())


def guarantee_request() -> dict:
    return {
        "analysisId": 1,
        "scenarioCode": "GUARANTEE_MISUNDERSTANDING_HIGH",
        "confirmedText": "최근 안정적인 수익률을 기록한 투자상품입니다.",
        "personaCodes": ["FINANCIAL_BEGINNER", "SENIOR"],
        "redTeamPackCode": "CORE_FINANCIAL_RISK_V1",
        "ruleCodes": ["STABILITY_KEYWORD", "LOSS_SOFTENING"],
        "selectedEvidenceDocumentIds": [1],
        "retrievedContexts": [
            {
                "chunkId": 11,
                "evidenceDocumentId": 1,
                "sourceType": "INTERNAL_POLICY",
                "title": "금융상품 설명 내부준칙",
                "chunkText": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
                "rank": 1,
                "similarity": 0.93,
            }
        ],
    }


def test_health() -> None:
    response = call_api("GET", "/internal/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "provider": "fixture"}


def test_rejects_unauthenticated_risk_analysis() -> None:
    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
        headers={"Authorization": ""},
    )

    assert response.status_code == 401
    assert response.json()["errorCode"] == "INTERNAL_AUTHENTICATION_REQUIRED"
    assert response.json()["retryable"] is False


def test_defaults_to_ollama_when_provider_is_unconfigured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("AI_PROVIDER")
    client = httpx.Client()
    try:
        service = RiskAnalysisService(http_client=client)
        assert service.provider == AnalysisProvider.OLLAMA
    finally:
        client.close()


def test_returns_fixture_for_supported_scenario() -> None:
    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["riskScore"] == 82
    assert body["modelVersion"] == "mock-risk-v1"
    assert body["findings"][0]["severity"] == "HIGH"
    assert body["findings"][0]["affectedPersonaCodes"] == [
        "FINANCIAL_BEGINNER",
        "SENIOR",
    ]
    assert body["findings"][0]["retrievedContextChunkIds"] == [11]


def test_accepts_negative_cosine_similarity() -> None:
    request = guarantee_request()
    request["retrievedContexts"][0]["similarity"] = -1.0

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=request,
    )

    assert response.status_code == 200


def test_returns_not_found_for_unknown_scenario() -> None:
    request = guarantee_request()
    request["scenarioCode"] = "UNKNOWN"

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 404
    assert response.json() == {
        "errorCode": "SCENARIO_NOT_FOUND",
        "message": "Unsupported scenarioCode: UNKNOWN",
        "retryable": False,
    }


def test_retryable_scenario_fails_once_then_succeeds() -> None:
    request = guarantee_request()
    request["analysisId"] = 9001
    request["scenarioCode"] = "PROVIDER_RATE_LIMITED_THEN_SUCCESS"

    first_response = call_api("POST", "/internal/v1/risk-analyses", json=request)
    second_response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert first_response.status_code == 503
    assert first_response.json()["errorCode"] == "AI_SERVICE_TEMPORARY_FAILURE"
    assert first_response.json()["retryable"] is True
    assert second_response.status_code == 200
    assert second_response.json()["riskScore"] == 82


def test_rejects_duplicate_persona_codes() -> None:
    request = guarantee_request()
    request["personaCodes"] = ["SENIOR", "SENIOR"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"
    assert response.json()["retryable"] is False


def test_accepts_four_selected_persona_codes() -> None:
    request = guarantee_request()
    request["personaCodes"] = [
        "FINANCIAL_BEGINNER",
        "SENIOR",
        "LOSS_EXPERIENCED",
        "SHORT_TERM_LIQUIDITY",
    ]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200


def test_rejects_more_than_four_selected_persona_codes() -> None:
    request = guarantee_request()
    request["personaCodes"] = [
        "FINANCIAL_BEGINNER",
        "SENIOR",
        "LOSS_EXPERIENCED",
        "SHORT_TERM_LIQUIDITY",
        "SELF_EMPLOYED",
    ]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"


def test_rejects_unknown_red_team_pack() -> None:
    request = guarantee_request()
    request["redTeamPackCode"] = "DEFAULT_RED_TEAM_PACK"

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"


def test_rejects_scenario_without_required_rule() -> None:
    request = guarantee_request()
    request["ruleCodes"] = ["COST_OMISSION"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"


def test_accepts_valid_known_facts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["knownFacts"] = [
        {"factId": 101, "text": "시장 상황에 따라 원금 손실이 발생할 수 있습니다."},
        {"factId": 202, "text": "중도 해지 시 비용이 부과될 수 있습니다."},
    ]
    load_fixture = analysis_service.fixture_loader.load

    def load_fixture_with_fact_reference(
        scenario_code: str,
        attempt_number: int = 1,
    ) -> dict[str, Any]:
        payload = deepcopy(load_fixture(scenario_code, attempt_number))
        payload["findings"][0]["knownFactIds"] = [101]
        return payload

    monkeypatch.setattr(
        analysis_service.fixture_loader,
        "load",
        load_fixture_with_fact_reference,
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    assert response.json()["findings"][0]["knownFactIds"] == [101]


@pytest.mark.parametrize(
    "known_facts",
    [
        [
            {"factId": 101, "text": "첫 번째 사실"},
            {"factId": 101, "text": "중복 식별자의 사실"},
        ],
        [{"factId": 0, "text": "유효하지 않은 식별자의 사실"}],
        [{"factId": 101, "text": "   "}],
    ],
    ids=["duplicate-id", "non-positive-id", "blank-text"],
)
def test_rejects_invalid_known_facts(
    known_facts: list[dict[str, Any]],
) -> None:
    request = guarantee_request()
    request["knownFacts"] = known_facts

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"
    assert response.json()["retryable"] is False


def test_rejects_provider_output_citing_unknown_known_fact(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["knownFacts"] = [
        {"factId": 101, "text": "시장 상황에 따라 원금 손실이 발생할 수 있습니다."}
    ]
    provider_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
                "severity": "HIGH",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": [11],
                "evidenceSpans": [
                    {
                        "chunkId": 11,
                        "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
                    }
                ],
                "knownFactIds": [999],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }

    def provider_response(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(provider_output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_ollama_schema_requires_and_accepts_retrieved_chunk_ids(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    provider_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
                "severity": "HIGH",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": [11],
                "evidenceSpans": [
                    {
                        "chunkId": 11,
                        "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
                    }
                ],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        ollama_payload = json.loads(provider_request.content)
        finding_schema = ollama_payload["format"]["$defs"]["FindingPayload"]
        assert "retrievedContextChunkIds" in finding_schema["required"]
        assert "evidenceSpans" in finding_schema["required"]
        assert (
            finding_schema["properties"]["retrievedContextChunkIds"][
                "minItems"
            ]
            == 1
        )
        user_payload = json.loads(ollama_payload["messages"][1]["content"])
        system_prompt = ollama_payload["messages"][0]["content"]
        expected_analysis_request = RiskAnalysisRequest.model_validate(
            request
        ).model_dump(mode="json", by_alias=True)
        expected_analysis_request["allowedEvidenceExcerptOptions"] = [
            {
                "chunkId": 11,
                "excerpts": [
                    "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다."
                ],
            }
        ]
        assert user_payload == expected_analysis_request
        assert "evidenceDocuments" not in user_payload
        assert "citationCatalog" not in user_payload
        assert user_payload["selectedEvidenceDocumentIds"] == [1]
        assert set(user_payload["retrievedContexts"][0]) == {
            "chunkId",
            "evidenceDocumentId",
            "sourceType",
            "title",
            "chunkText",
            "rank",
            "similarity",
        }
        assert (
            "retrievedContextChunkIds entry must be a chunkId selected exactly "
            "from retrievedContexts"
        ) in system_prompt
        assert (
            "Use only chunkId values present in retrievedContexts"
            in system_prompt
        )
        assert "citationCatalog" not in system_prompt
        assert "candidateExcerpts" not in system_prompt
        assert "evidenceReference" not in system_prompt
        assert "Return exactly one fully grounded finding." in system_prompt
        assert (
            "allowedEvidenceExcerptOptions supplied in the user message"
            in system_prompt
        )
        assert "Do not combine, shorten, rewrite, or extend" in system_prompt
        assert (
            "Never quote confirmedText or any other product text as evidence."
            in system_prompt
        )
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(provider_output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200


def test_ollama_repairs_mixed_grounded_and_ungrounded_findings_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    valid_finding = {
        "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
        "severity": "HIGH",
        "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        "retrievedContextChunkIds": [11],
        "evidenceSpans": [
            {
                "chunkId": 11,
                "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
            }
        ],
        "knownFactIds": [],
        "recommendation": "원금 손실 가능성을 함께 고지하세요.",
    }
    unsupported_finding = {
        **valid_finding,
        "statement": "상품 문구를 근거로 삼은 지원되지 않는 지적입니다.",
        "evidenceSpans": [
            {
                "chunkId": 11,
                "excerpt": "최근 안정적인 수익률을 기록한 투자상품입니다.",
            }
        ],
    }
    first_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [valid_finding, unsupported_finding],
    }
    repaired_output = {
        **first_output,
        "findings": [valid_finding],
    }
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        output = first_output if len(provider_requests) == 1 else repaired_output
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    assert response.json()["findings"] == [
        {
            **valid_finding,
            "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        }
    ]
    assert len(provider_requests) == 2
    repair_messages = provider_requests[1]["messages"]
    assert repair_messages[-2] == {
        "role": "assistant",
        "content": json.dumps(first_output),
    }
    repair_prompt = repair_messages[-1]["content"]
    assert "Return exactly one finding" in repair_prompt
    assert (
        '"chunkId":11,"excerpts":'
        '["원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다."]'
    ) in repair_prompt
    assert (
        "Do not combine, shorten, rewrite, or extend an option."
        in repair_prompt
    )
    assert "Do not invent, alter, or copy evidence" in repair_prompt


def test_rejects_ollama_output_that_remains_invalid_after_one_repair(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    invalid_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "상품 문구를 근거로 삼은 지원되지 않는 지적입니다.",
                "severity": "HIGH",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": [11],
                "evidenceSpans": [
                    {
                        "chunkId": 11,
                        "excerpt": "최근 안정적인 수익률을 기록한 투자상품입니다.",
                    }
                ],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }
    grounded_finding = deepcopy(invalid_output["findings"][0])
    grounded_finding["evidenceSpans"] = [
        {
            "chunkId": 11,
            "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
        }
    ]
    invalid_repair_output = {
        **invalid_output,
        "findings": [grounded_finding, deepcopy(grounded_finding)],
    }
    call_count = 0

    def provider_response(_request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        output = invalid_output if call_count == 1 else invalid_repair_output
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert call_count == 2
    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_ollama_repair_excerpt_options_are_bounded_and_exact(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["retrievedContexts"][0]["chunkText"] = (
        "ARGUS | SYNTHETIC DEMO CORPUS POLICY-v1 · 1/2\n"
        "중요정보 표시 내부정책\n"
        "문서번호 SYN-DISC-2026-1 · 버전 2026.1\n"
        "완전 합성 내부 표시기준 — 실제 법령이나 업계 표준이 아닙니다.\n"
        "2. 동일 화면 원칙\n"
        f"{'가' * 401}.\n"
        "“안정”, “보장” 표현이 있으면 원금손실 가능성을 같은 화면에 "
        "표시한다. 각 고지는 홍보 문구보다 늦게 나타나면 안 된다.\n"
        "제13조(강조 표현의 정정)\n"
        "안정 또는 확정을 연상시키는 표현의 한계를 명확히 정정한다.\n"
        "비권위 선언\n"
        "네 번째 정책 문장은 선택 한도를 넘어 제외된다.\n"
        "완전 합성 데모 문서 · 실제 상품이나 법령을 나타내지 않음"
    )
    invalid_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "지원되지 않는 지적입니다.",
                "severity": "HIGH",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": [11],
                "evidenceSpans": [
                    {"chunkId": 11, "excerpt": "상품 원문의 잘못된 근거"}
                ],
                "knownFactIds": [],
                "recommendation": "근거를 수정하세요.",
            }
        ],
    }
    repaired_output = deepcopy(invalid_output)
    repaired_output["findings"][0]["evidenceSpans"] = [
        {
            "chunkId": 11,
            "excerpt": (
                "“안정”, “보장” 표현이 있으면 원금손실 가능성을 "
                "같은 화면에 표시한다."
            ),
        }
    ]
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        output = (
            invalid_output if len(provider_requests) == 1 else repaired_output
        )
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    initial_user_payload = json.loads(
        provider_requests[0]["messages"][1]["content"]
    )
    initial_options = initial_user_payload["allowedEvidenceExcerptOptions"]
    assert initial_options == [
        {
            "chunkId": 11,
            "excerpts": [
                (
                    "“안정”, “보장” 표현이 있으면 원금손실 가능성을 "
                    "같은 화면에 표시한다."
                ),
                "각 고지는 홍보 문구보다 늦게 나타나면 안 된다.",
                "안정 또는 확정을 연상시키는 표현의 한계를 명확히 정정한다.",
            ],
        }
    ]
    assert request["confirmedText"] not in json.dumps(
        initial_options,
        ensure_ascii=False,
    )
    repair_prompt = provider_requests[1]["messages"][-1]["content"]
    assert (
        '"chunkId":11,"excerpts":'
        '["“안정”, “보장” 표현이 있으면 원금손실 가능성을 같은 화면에 '
        '표시한다.","각 고지는 홍보 문구보다 늦게 나타나면 안 된다.",'
        '"안정 또는 확정을 연상시키는 표현의 한계를 명확히 정정한다."]'
    ) in repair_prompt
    assert "ARGUS | SYNTHETIC DEMO CORPUS" not in repair_prompt
    assert "문서번호 SYN-DISC-2026-1" not in repair_prompt
    assert "완전 합성 내부 표시기준" not in repair_prompt
    assert "2. 동일 화면 원칙" not in repair_prompt
    assert "제13조(강조 표현의 정정)" not in repair_prompt
    assert "비권위 선언" not in repair_prompt
    assert ("가" * 401) not in repair_prompt
    assert "네 번째 정책 문장은 선택 한도를 넘어 제외된다." not in repair_prompt
    assert "완전 합성 데모 문서" not in repair_prompt
    assert request["confirmedText"] not in repair_prompt


def test_rejects_ollama_output_omitting_retrieved_chunk_ids(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider_output = {
        "riskScore": 30,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "안정성 표현이 투자 위험을 축소할 수 있습니다.",
                "severity": "LOW",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }

    def provider_response(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(provider_output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


@pytest.mark.parametrize(
    "chunk_ids",
    [[], [0], [11, 11], [99]],
    ids=["empty", "non-positive", "duplicate", "unretrieved"],
)
def test_rejects_invalid_ollama_retrieved_chunk_ids(
    monkeypatch: pytest.MonkeyPatch,
    chunk_ids: list[int],
) -> None:
    provider_output = {
        "riskScore": 30,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "안정성 표현이 투자 위험을 축소할 수 있습니다.",
                "severity": "LOW",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": chunk_ids,
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }

    def provider_response(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(provider_output)}},
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_rejects_fixture_output_with_unselected_persona() -> None:
    request = deepcopy(guarantee_request())
    request["personaCodes"] = ["FINANCIAL_BEGINNER"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_rejects_fixture_output_citing_unretrieved_chunk() -> None:
    request = deepcopy(guarantee_request())
    request["retrievedContexts"][0]["chunkId"] = 99

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_rejects_legacy_full_evidence_document_payload() -> None:
    request = guarantee_request()
    request["evidenceDocuments"] = [
        {
            "id": 1,
            "sourceType": "INTERNAL_POLICY",
            "title": "금융상품 설명 내부준칙",
            "content": "문서 전체 원문은 provider 계약에 포함될 수 없습니다.",
        }
    ]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"
    assert response.json()["retryable"] is False


def test_rejects_context_for_unselected_evidence() -> None:
    request = guarantee_request()
    request["retrievedContexts"][0]["evidenceDocumentId"] = 99

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"
    assert response.json()["retryable"] is False


def test_fixture_citation_requires_exact_evidence_span() -> None:
    request = guarantee_request()
    request["retrievedContexts"][0][
        "chunkText"
    ] = "원금손실  가능성은 안정성 표현과 인접하여 표시해야 합니다."

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"


def test_rejects_fixture_output_citing_unknown_known_fact(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["knownFacts"] = [
        {"factId": 101, "text": "시장 상황에 따라 원금 손실이 발생할 수 있습니다."}
    ]
    load_fixture = analysis_service.fixture_loader.load

    def load_fixture_with_unknown_fact_reference(
        scenario_code: str,
        attempt_number: int = 1,
    ) -> dict[str, Any]:
        payload = deepcopy(load_fixture(scenario_code, attempt_number))
        payload["findings"][0]["knownFactIds"] = [999]
        return payload

    monkeypatch.setattr(
        analysis_service.fixture_loader,
        "load",
        load_fixture_with_unknown_fact_reference,
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False
