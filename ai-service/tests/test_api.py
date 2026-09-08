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
from app.schemas import (
    AnalysisProvider,
    FindingPayload,
    OllamaFindingPayload,
    OllamaRiskAnalysisResponse,
    RiskAnalysisRequest,
    RiskAnalysisResponse,
)
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


def ollama_finding(
    *,
    statement: str = "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
    policy_rule_code: str = "STABILITY_KEYWORD",
    option_ids: list[str] | None = None,
) -> dict[str, Any]:
    return {
        "statement": statement,
        "severity": "HIGH",
        "policyRuleCode": policy_rule_code,
        "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        "evidenceSpanOptionIds": option_ids or ["evidence-option-1"],
        "knownFactIds": [],
        "recommendation": "원금 손실 가능성을 함께 고지하세요.",
    }


def mock_ollama_outputs(
    monkeypatch: pytest.MonkeyPatch,
    outputs: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        output = outputs[min(len(provider_requests) - 1, len(outputs) - 1)]
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
    return provider_requests


def test_health() -> None:
    response = call_api("GET", "/internal/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "provider": "fixture"}


def test_response_schema_exposes_required_bounds() -> None:
    request_schema = RiskAnalysisRequest.model_json_schema(by_alias=True)
    response_schema = RiskAnalysisResponse.model_json_schema(by_alias=True)
    ollama_schema = OllamaRiskAnalysisResponse.model_json_schema(by_alias=True)
    finding_schema = response_schema["$defs"]["FindingPayload"]
    ollama_finding_schema = ollama_schema["$defs"]["OllamaFindingPayload"]

    assert request_schema["properties"]["personaCodes"]["maxItems"] == 12
    assert request_schema["properties"]["retrievedContexts"]["maxItems"] == 20
    assert request_schema["properties"]["knownFacts"]["maxItems"] == 50
    assert response_schema["properties"]["findings"]["maxItems"] == 20
    assert ollama_schema["properties"]["findings"]["maxItems"] == 20
    assert "findings" in response_schema["required"]
    assert "findings" in ollama_schema["required"]
    assert finding_schema["properties"]["affectedPersonaCodes"][
        "maxItems"
    ] == 12
    assert finding_schema["properties"]["retrievedContextChunkIds"][
        "maxItems"
    ] == 20
    assert finding_schema["properties"]["evidenceSpans"]["maxItems"] == 60
    assert finding_schema["properties"]["knownFactIds"]["maxItems"] == 50
    assert ollama_finding_schema["properties"]["evidenceSpanOptionIds"][
        "maxItems"
    ] == 60
    assert ollama_finding_schema["properties"]["evidenceSpanOptionIds"][
        "minItems"
    ] == 1
    assert response_schema["properties"]["modelVersion"]["maxLength"] == 50
    assert response_schema["properties"]["promptVersion"]["maxLength"] == 50


@pytest.mark.parametrize("statement", [" ", "\t", "\n"])
def test_finding_payload_rejects_blank_statement(statement: str) -> None:
    with pytest.raises(ValueError):
        FindingPayload.model_validate(
            {
                "statement": statement,
                "severity": "HIGH",
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": [11],
                "evidenceSpans": [
                    {
                        "chunkId": 11,
                        "excerpt": "원금 손실 가능성을 함께 고지해야 합니다.",
                    }
                ],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        )


@pytest.mark.parametrize("statement", [" ", "\t", "\n"])
def test_ollama_finding_payload_rejects_blank_statement(
    statement: str,
) -> None:
    with pytest.raises(ValueError):
        OllamaFindingPayload.model_validate(
            ollama_finding(statement=statement)
        )


def test_finding_payloads_preserve_valid_statement() -> None:
    statement = "  원문 공백을 유지합니다.  "
    public_finding = FindingPayload.model_validate(
        {
            "statement": statement,
            "severity": "HIGH",
            "policyRuleCode": "STABILITY_KEYWORD",
            "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
            "retrievedContextChunkIds": [11],
            "evidenceSpans": [
                {
                    "chunkId": 11,
                    "excerpt": "  근거 원문 공백도 유지합니다.  ",
                }
            ],
            "knownFactIds": [],
            "recommendation": "원금 손실 가능성을 함께 고지하세요.",
        }
    )
    ollama_finding_payload = OllamaFindingPayload.model_validate(
        ollama_finding(statement=statement)
    )

    assert public_finding.statement == statement
    assert public_finding.evidence_spans[0].excerpt == (
        "  근거 원문 공백도 유지합니다.  "
    )
    assert ollama_finding_payload.statement == statement


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
    assert body["findings"][0]["policyRuleCode"] == "STABILITY_KEYWORD"
    assert body["findings"][0]["affectedPersonaCodes"] == [
        "FINANCIAL_BEGINNER",
        "SENIOR",
    ]
    assert body["findings"][0]["retrievedContextChunkIds"] == [11]


def test_fixture_without_known_facts_does_not_invent_fact_reference() -> None:
    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    assert response.json()["findings"][0]["knownFactIds"] == []


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


def test_accepts_more_than_four_selected_persona_codes() -> None:
    request = guarantee_request()
    request["personaCodes"] = [
        "FINANCIAL_BEGINNER",
        "SENIOR",
        "LOSS_EXPERIENCED",
        "SHORT_TERM_LIQUIDITY",
        "SELF_EMPLOYED",
    ]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200


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


def test_fixture_does_not_fabricate_known_fact_when_none_applies() -> None:
    request = guarantee_request()
    request["knownFacts"] = [
        {"factId": 101, "text": "시장 상황에 따라 원금 손실이 발생할 수 있습니다."},
        {"factId": 202, "text": "중도 해지 시 비용이 부과될 수 있습니다."},
    ]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    finding = response.json()["findings"][0]
    assert finding["knownFactIds"] == []
    assert finding["policyRuleCode"] == "STABILITY_KEYWORD"
    assert finding["retrievedContextChunkIds"] == [11]
    assert finding["evidenceSpans"] == [
        {
            "chunkId": 11,
            "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
        }
    ]


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


def test_rejects_ollama_output_citing_unknown_known_fact(
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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": ["evidence-option-1"],
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


def test_ollama_accepts_empty_known_fact_ids_when_none_applies(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["knownFacts"] = [
        {"factId": 101, "text": "시장 상황에 따라 원금 손실이 발생할 수 있습니다."}
    ]
    finding = {
        "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
        "severity": "HIGH",
        "policyRuleCode": "STABILITY_KEYWORD",
        "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        "evidenceSpanOptionIds": ["evidence-option-1"],
        "knownFactIds": [],
        "recommendation": "원금 손실 가능성을 함께 고지하세요.",
    }
    provider_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [finding],
    }
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        return httpx.Response(
            200,
            json={
                "message": {
                    "content": json.dumps(
                        provider_output
                    )
                }
            },
        )

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    assert response.json()["findings"][0]["knownFactIds"] == []
    assert len(provider_requests) == 1
    system_prompt = provider_requests[0]["messages"][0]["content"]
    assert (
        "knownFactIds may be empty when no supplied known fact contains or "
        "applies to the criticized claim"
        in system_prompt
    )
    assert (
        "never select the first fact merely because it was supplied"
        in system_prompt
    )


def test_ollama_accepts_scope_bound_clean_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    provider_requests = mock_ollama_outputs(
        monkeypatch,
        [
            {
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [],
            }
        ],
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    assert response.json()["riskScore"] is None
    assert response.json()["findings"] == []
    assert len(provider_requests) == 1


def test_ollama_accepts_two_fully_grounded_findings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["retrievedContexts"].append(
        {
            **request["retrievedContexts"][0],
            "chunkId": 12,
            "chunkText": "손실 위험을 완화 표현 없이 명확히 표시해야 합니다.",
            "rank": 2,
        }
    )
    second_finding = ollama_finding(
        statement="손실 완화 표현도 별도 위험을 만들 수 있습니다.",
        policy_rule_code="LOSS_SOFTENING",
        option_ids=["evidence-option-2"],
    )
    mock_ollama_outputs(
        monkeypatch,
        [
            {
                "riskScore": 82,
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [ollama_finding(), second_finding],
            }
        ],
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    assert len(response.json()["findings"]) == 2
    assert response.json()["findings"][1]["evidenceSpans"] == [
        {
            "chunkId": 12,
            "excerpt": "손실 위험을 완화 표현 없이 명확히 표시해야 합니다.",
        }
    ]


def test_ollama_accepts_shared_option_across_findings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_ollama_outputs(
        monkeypatch,
        [
            {
                "riskScore": None,
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [
                    ollama_finding(),
                    ollama_finding(
                        statement="같은 근거가 별도 선택 규칙도 지원합니다.",
                        policy_rule_code="LOSS_SOFTENING",
                    ),
                ],
            }
        ],
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    assert len(response.json()["findings"]) == 2
    assert (
        response.json()["findings"][0]["evidenceSpans"]
        == response.json()["findings"][1]["evidenceSpans"]
    )


@pytest.mark.parametrize("finding_count", [20, 21])
def test_ollama_enforces_twenty_finding_bound(
    monkeypatch: pytest.MonkeyPatch,
    finding_count: int,
) -> None:
    provider_requests = mock_ollama_outputs(
        monkeypatch,
        [
            {
                "riskScore": 82,
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [
                    ollama_finding(statement=f"지원되는 지적 {index}")
                    for index in range(finding_count)
                ],
            }
        ],
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == (200 if finding_count == 20 else 500)
    assert len(provider_requests) == (1 if finding_count == 20 else 2)


@pytest.mark.parametrize(
    "provider_output",
    [
        {
            "modelVersion": "ignored-provider-version",
            "promptVersion": "ignored-prompt-version",
        },
        {
            "modelVersion": "ignored-provider-version",
            "promptVersion": "ignored-prompt-version",
            "findings": None,
        },
        {
            "riskScore": 0,
            "modelVersion": "ignored-provider-version",
            "promptVersion": "ignored-prompt-version",
            "findings": [],
        },
    ],
    ids=["missing-findings", "null-findings", "numeric-zero-clean"],
)
def test_ollama_rejects_malformed_clean_outputs_after_one_repair(
    monkeypatch: pytest.MonkeyPatch,
    provider_output: dict[str, Any],
) -> None:
    provider_requests = mock_ollama_outputs(monkeypatch, [provider_output])

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert len(provider_requests) == 2
    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"


def test_ollama_schema_requires_only_source_option_ids(
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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": ["evidence-option-1"],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        ollama_payload = json.loads(provider_request.content)
        finding_schema = ollama_payload["format"]["$defs"][
            "OllamaFindingPayload"
        ]
        assert "retrievedContextChunkIds" not in finding_schema["properties"]
        assert "evidenceSpanOptionIds" in finding_schema["required"]
        assert "policyRuleCode" in finding_schema["required"]
        assert "evidenceSpans" not in finding_schema["properties"]
        assert finding_schema["additionalProperties"] is False
        assert finding_schema["properties"]["evidenceSpanOptionIds"][
            "maxItems"
        ] == 60
        assert finding_schema["properties"]["evidenceSpanOptionIds"][
            "minItems"
        ] == 1
        assert ollama_payload["format"]["properties"]["findings"][
            "maxItems"
        ] == 20
        assert "findings" in ollama_payload["format"]["required"]
        user_payload = json.loads(ollama_payload["messages"][1]["content"])
        system_prompt = ollama_payload["messages"][0]["content"]
        expected_analysis_request = RiskAnalysisRequest.model_validate(
            request
        ).model_dump(mode="json", by_alias=True)
        expected_analysis_request["allowedEvidenceSpanOptions"] = [
            {
                "optionId": "evidence-option-1",
                "chunkId": 11,
                "excerpt": (
                    "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다."
                ),
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
            "Do not return retrievedContextChunkIds, evidenceSpans"
            in system_prompt
        )
        assert "citationCatalog" not in system_prompt
        assert "candidateExcerpts" not in system_prompt
        assert "evidenceReference" not in system_prompt
        assert "Return 0 to 20 fully grounded findings." in system_prompt
        assert (
            "it does not assert safety or legal compliance" in system_prompt
        )
        assert (
            "allowedEvidenceSpanOptions supplied in the user message"
            in system_prompt
        )
        assert (
            "policyRuleCode must be one exact value selected from ruleCodes"
            in system_prompt
        )
        assert (
            "evidenceSpanOptionIds are only POLICY_REQUIREMENT citations"
            in system_prompt
        )
        assert (
            "Never put confirmedText, product text, or known facts into "
            "evidenceSpanOptionIds."
            in system_prompt
        )
        assert (
            "knownFactIds are separate DOCUMENT_CLAIM provenance."
            in system_prompt
        )
        assert (
            "When a supplied known fact records product wording containing "
            "the claim criticized by a finding, cite that actual applicable "
            "factId."
            in system_prompt
        )
        assert (
            "CONFIRMED_DOCUMENT verification confirms the wording and source, "
            "not the truth or compliance of a marketing claim; citing the "
            "fact does not endorse the claim."
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
    assert response.json()["findings"][0]["evidenceSpans"] == [
        {
            "chunkId": 11,
            "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
        }
    ]
    assert response.json()["findings"][0]["retrievedContextChunkIds"] == [11]
    assert response.json()["promptVersion"] == "ollama-rag-grounded-v17"


@pytest.mark.parametrize(
    "policy_rule_code",
    [None, "FORMAL_CONFIRMATION"],
    ids=["missing", "unselected"],
)
def test_rejects_missing_or_unselected_ollama_policy_rule_code(
    monkeypatch: pytest.MonkeyPatch,
    policy_rule_code: str | None,
) -> None:
    finding = {
        "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
        "severity": "HIGH",
        "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        "evidenceSpanOptionIds": ["evidence-option-1"],
        "knownFactIds": [],
        "recommendation": "원금 손실 가능성을 함께 고지하세요.",
    }
    if policy_rule_code is not None:
        finding["policyRuleCode"] = policy_rule_code
    provider_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [finding],
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
    "option_ids",
    [
        ["unknown-option"],
        ["evidence-option-1", "evidence-option-1"],
    ],
    ids=[
        "unknown-option-id",
        "duplicate-option-id",
    ],
)
def test_unknown_or_repeated_ollama_options_reject_after_repair_without_guessing(
    monkeypatch: pytest.MonkeyPatch,
    option_ids: list[str],
) -> None:
    provider_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [
            {
                "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
                "severity": "HIGH",
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": option_ids,
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }
    call_count = 0

    def provider_response(_request: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
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

    assert call_count == 2
    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"


def test_ollama_internal_finding_rejects_model_authored_chunk_ids() -> None:
    obsolete_payload = ollama_finding()
    obsolete_payload["retrievedContextChunkIds"] = [11]

    with pytest.raises(ValueError):
        OllamaFindingPayload.model_validate(obsolete_payload)


def test_ollama_derives_one_chunk_id_from_multiple_exact_options(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["retrievedContexts"][0]["chunkText"] = (
        "원금 손실 가능성을 표시해야 합니다. "
        "안정성 표현의 한계를 함께 알려야 합니다."
    )
    provider_requests = mock_ollama_outputs(
        monkeypatch,
        [
            {
                "riskScore": 82,
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [
                    ollama_finding(
                        option_ids=[
                            "evidence-option-1",
                            "evidence-option-2",
                        ]
                    )
                ],
            }
        ],
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert len(provider_requests) == 1
    assert response.status_code == 200
    assert response.json()["findings"][0]["retrievedContextChunkIds"] == [11]
    assert response.json()["findings"][0]["evidenceSpans"] == [
        {"chunkId": 11, "excerpt": "원금 손실 가능성을 표시해야 합니다."},
        {
            "chunkId": 11,
            "excerpt": "안정성 표현의 한계를 함께 알려야 합니다.",
        },
    ]


def test_ollama_derives_chunk_ids_from_options_not_evidence_document_ids(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    request = guarantee_request()
    request["selectedEvidenceDocumentIds"] = [101, 202]
    request["retrievedContexts"][0]["evidenceDocumentId"] = 101
    request["retrievedContexts"].append(
        {
            **request["retrievedContexts"][0],
            "chunkId": 12,
            "evidenceDocumentId": 202,
            "chunkText": "손실 위험을 완화 표현 없이 명확히 표시해야 합니다.",
            "rank": 2,
        }
    )
    mock_ollama_outputs(
        monkeypatch,
        [
            {
                "riskScore": 82,
                "modelVersion": "ignored-provider-version",
                "promptVersion": "ignored-prompt-version",
                "findings": [
                    ollama_finding(
                        option_ids=[
                            "evidence-option-2",
                            "evidence-option-1",
                        ]
                    )
                ],
            }
        ],
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 200
    finding = response.json()["findings"][0]
    assert finding["retrievedContextChunkIds"] == [12, 11]
    assert finding["evidenceSpans"] == [
        {
            "chunkId": 12,
            "excerpt": "손실 위험을 완화 표현 없이 명확히 표시해야 합니다.",
        },
        {
            "chunkId": 11,
            "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
        },
    ]


def test_invalid_second_finding_rejects_whole_output_after_one_repair(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    valid_finding = {
        "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
        "severity": "HIGH",
        "policyRuleCode": "STABILITY_KEYWORD",
        "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
        "evidenceSpanOptionIds": ["evidence-option-1"],
        "knownFactIds": [],
        "recommendation": "원금 손실 가능성을 함께 고지하세요.",
    }
    unsupported_finding = {
        **valid_finding,
        "statement": "상품 문구를 근거로 삼은 지원되지 않는 지적입니다.",
        "evidenceSpanOptionIds": ["unknown-option"],
    }
    first_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [valid_finding, unsupported_finding],
    }
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        return httpx.Response(
            200,
            json={"message": {"content": json.dumps(first_output)}},
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
    assert len(provider_requests) == 2
    repair_messages = provider_requests[1]["messages"]
    assert repair_messages[-2] == {
        "role": "assistant",
        "content": json.dumps(first_output),
    }
    repair_prompt = repair_messages[-1]["content"]
    assert "Return 0 to 20 fully grounded findings." in repair_prompt
    assert "retaining the best" not in repair_prompt
    assert "removing all others" not in repair_prompt
    assert "exactly one" not in repair_prompt
    assert (
        '"optionId":"evidence-option-1","chunkId":11,'
        '"excerpt":"원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다."'
    ) in repair_prompt
    assert (
        "Do not return retrievedContextChunkIds, evidenceSpans"
        in repair_prompt
    )
    assert (
        "Do not invent, alter, or duplicate option IDs within a finding"
        in repair_prompt
    )
    assert (
        "only POLICY_REQUIREMENT citations to retrieved normative or "
        "source-policy text"
        in repair_prompt
    )
    assert (
        "Never put confirmedText, product text, or known facts into "
        "evidenceSpanOptionIds."
        in repair_prompt
    )
    assert (
        "knownFactIds are separate DOCUMENT_CLAIM provenance."
        in repair_prompt
    )
    assert (
        "When a supplied known fact records product wording containing the "
        "claim criticized by a finding, cite that actual applicable factId."
        in repair_prompt
    )
    assert (
        "CONFIRMED_DOCUMENT verification confirms the wording and source, "
        "not the truth or compliance of a marketing claim; citing the fact "
        "does not endorse the claim."
        in repair_prompt
    )
    assert (
        "knownFactIds may be empty when no supplied known fact contains or "
        "applies to the criticized claim"
        in repair_prompt
    )
    assert (
        "never select the first fact merely because it was supplied"
        in repair_prompt
    )


def test_blank_second_finding_repairs_whole_output_without_dropping_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    valid_finding = ollama_finding()
    blank_second_finding = ollama_finding(
        statement="\t\n",
        policy_rule_code="LOSS_SOFTENING",
    )
    repaired_second_finding = ollama_finding(
        statement="손실 완화 표현도 별도 위험을 만들 수 있습니다.",
        policy_rule_code="LOSS_SOFTENING",
    )
    first_output = {
        "riskScore": 82,
        "modelVersion": "ignored-provider-version",
        "promptVersion": "ignored-prompt-version",
        "findings": [valid_finding, blank_second_finding],
    }
    provider_requests = mock_ollama_outputs(
        monkeypatch,
        [
            first_output,
            {
                **first_output,
                "findings": [valid_finding, repaired_second_finding],
            },
        ],
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

    assert response.status_code == 200
    assert len(provider_requests) == 2
    assert len(response.json()["findings"]) == 2
    assert response.json()["findings"][1]["statement"] == (
        repaired_second_finding["statement"]
    )
    assert provider_requests[1]["messages"][-2] == {
        "role": "assistant",
        "content": json.dumps(first_output),
    }


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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": ["unknown-option"],
                "knownFactIds": [],
                "recommendation": "원금 손실 가능성을 함께 고지하세요.",
            }
        ],
    }
    grounded_finding = deepcopy(invalid_output["findings"][0])
    grounded_finding["evidenceSpanOptionIds"] = ["evidence-option-1"]
    invalid_repair_output = {
        **invalid_output,
        "findings": [grounded_finding, invalid_output["findings"][0]],
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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": ["unknown-option"],
                "knownFactIds": [],
                "recommendation": "근거를 수정하세요.",
            }
        ],
    }
    repaired_output = deepcopy(invalid_output)
    repaired_output["findings"][0]["evidenceSpanOptionIds"] = [
        "evidence-option-1"
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
    for provider_request in provider_requests:
        assert provider_request["options"] == {
            "temperature": 0,
            "seed": 42,
            "num_ctx": RiskAnalysisService.DEFAULT_OLLAMA_NUM_CTX,
        }
        assert "keep_alive" not in provider_request
    initial_user_payload = json.loads(
        provider_requests[0]["messages"][1]["content"]
    )
    initial_options = initial_user_payload["allowedEvidenceSpanOptions"]
    assert initial_options == [
        {
            "optionId": "evidence-option-1",
            "chunkId": 11,
            "excerpt": (
                "“안정”, “보장” 표현이 있으면 원금손실 가능성을 "
                "같은 화면에 표시한다."
            ),
        },
        {
            "optionId": "evidence-option-2",
            "chunkId": 11,
            "excerpt": "각 고지는 홍보 문구보다 늦게 나타나면 안 된다.",
        },
        {
            "optionId": "evidence-option-3",
            "chunkId": 11,
            "excerpt": "안정 또는 확정을 연상시키는 표현의 한계를 명확히 정정한다.",
        },
    ]
    assert request["confirmedText"] not in json.dumps(
        initial_options,
        ensure_ascii=False,
    )
    repair_prompt = provider_requests[1]["messages"][-1]["content"]
    assert (
        '"optionId":"evidence-option-1","chunkId":11,'
        '"excerpt":"“안정”, “보장” 표현이 있으면 원금손실 가능성을 같은 화면에 '
        '표시한다."'
    ) in repair_prompt
    assert "ARGUS | SYNTHETIC DEMO CORPUS" not in repair_prompt
    assert "문서번호 SYN-DISC-2026-1" not in repair_prompt
    assert "완전 합성 내부 표시기준" not in repair_prompt
    assert "2. 동일 화면 원칙" not in repair_prompt
    assert "제13조(강조 표현의 정정)" not in repair_prompt
    assert "비권위 선언" not in repair_prompt
    assert "네 번째 정책 문장은 선택 한도를 넘어 제외된다." not in repair_prompt
    assert "완전 합성 데모 문서" not in repair_prompt
    assert request["confirmedText"] not in repair_prompt


def test_ollama_excerpt_options_accept_bullets_tables_and_no_period_lines(
) -> None:
    request_payload = guarantee_request()
    request_payload["retrievedContexts"][0]["chunkText"] = (
        "- 원금 손실 가능성을 안정성 표현과 함께 표시\n"
        "항목 | 표시 기준 | 동일 화면에 인접하여 표시"
    )
    request = RiskAnalysisRequest.model_validate(request_payload)

    options = RiskAnalysisService._repair_excerpt_options(request)

    assert [option["excerpt"] for option in options] == [
        "- 원금 손실 가능성을 안정성 표현과 함께 표시",
        "항목 | 표시 기준 | 동일 화면에 인접하여 표시",
    ]
    assert all(
        option["excerpt"]
        in request_payload["retrievedContexts"][0]["chunkText"]
        for option in options
    )


def test_ollama_excerpt_options_bound_long_sentence_exactly(
) -> None:
    request_payload = guarantee_request()
    long_line = ("원금 손실 위험과 중도 해지 비용을 함께 안내 " * 60) + "합니다."
    request_payload["retrievedContexts"][0]["chunkText"] = long_line
    request = RiskAnalysisRequest.model_validate(request_payload)

    options = RiskAnalysisService._repair_excerpt_options(request)

    assert len(options) == RiskAnalysisService.MAX_REPAIR_EXCERPTS_PER_CHUNK
    assert all(
        0 < len(option["excerpt"])
        <= RiskAnalysisService.MAX_REPAIR_EXCERPT_CHARS
        for option in options
    )
    assert all(option["excerpt"] in long_line for option in options)
    assert [option["optionId"] for option in options] == [
        "evidence-option-1",
        "evidence-option-2",
        "evidence-option-3",
    ]


def test_ollama_excerpt_options_preserve_chunk_coverage_and_byte_bound(
) -> None:
    request_payload = guarantee_request()
    request_payload["retrievedContexts"] = [
        {
            **request_payload["retrievedContexts"][0],
            "chunkId": chunk_id,
            "rank": chunk_id,
            "chunkText": "원금손실위험고지" * 100,
        }
        for chunk_id in range(1, 21)
    ]
    request = RiskAnalysisRequest.model_validate(request_payload)

    options = RiskAnalysisService._repair_excerpt_options(request)

    assert {option["chunkId"] for option in options} == set(range(1, 21))
    assert (
        sum(len(option["excerpt"].encode("utf-8")) for option in options)
        <= RiskAnalysisService.MAX_REPAIR_EXCERPT_BYTES
    )
    contexts_by_id = {
        context["chunkId"]: context["chunkText"]
        for context in request_payload["retrievedContexts"]
    }
    assert all(
        option["excerpt"] in contexts_by_id[option["chunkId"]]
        for option in options
    )


@pytest.mark.parametrize(
    "metadata_text",
    [
        (
            "ARGUS | SYNTHETIC DEMO CORPUS POLICY-v1\n"
            "문서번호 SYN-DISC-2026-1 · 버전 2026.1\n"
            "완전 합성 데모 문서 · 실제 상품이나 법령을 나타내지 않음\n"
            "2. 동일 화면 원칙"
        ),
        "합성 조문 해설\n발췌 범위\n출처",
        "합성 금융소비자 설명의무 규정 발췌\n데모 적용 예\n비권위 선언",
    ],
)
def test_ollama_rejects_metadata_only_context_before_provider_call(
    monkeypatch: pytest.MonkeyPatch,
    metadata_text: str,
) -> None:
    request = guarantee_request()
    request["retrievedContexts"][0]["chunkText"] = metadata_text
    provider_call_count = 0

    def provider_response(_request: httpx.Request) -> httpx.Response:
        nonlocal provider_call_count
        provider_call_count += 1
        raise AssertionError("metadata-only evidence must not call Ollama")

    monkeypatch.setattr(analysis_service, "provider", AnalysisProvider.OLLAMA)
    monkeypatch.setattr(
        analysis_service,
        "http_client",
        httpx.Client(transport=httpx.MockTransport(provider_response)),
    )

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert provider_call_count == 0
    assert response.status_code == 422
    assert response.json() == {
        "errorCode": "AI_PROVIDER_REQUEST_REJECTED",
        "message": "The request contains no usable source evidence excerpts.",
        "retryable": False,
    }


def test_accepts_ollama_output_without_model_authored_chunk_ids(
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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "evidenceSpanOptionIds": ["evidence-option-1"],
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

    assert response.status_code == 200
    assert response.json()["findings"][0]["retrievedContextChunkIds"] == [11]
    assert response.json()["findings"][0]["evidenceSpans"] == [
        {
            "chunkId": 11,
            "excerpt": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
        }
    ]


@pytest.mark.parametrize(
    "chunk_ids",
    [[], [0], [11, 11], [99]],
    ids=["empty", "non-positive", "duplicate", "unretrieved"],
)
def test_strict_ollama_contract_rejects_obsolete_chunk_id_field(
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
                "policyRuleCode": "STABILITY_KEYWORD",
                "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                "retrievedContextChunkIds": chunk_ids,
                "evidenceSpanOptionIds": ["evidence-option-1"],
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


def test_rejects_fixture_output_with_unselected_policy_rule(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    load_fixture = analysis_service.fixture_loader.load

    def load_fixture_with_unselected_policy_rule(
        scenario_code: str,
        attempt_number: int = 1,
    ) -> dict[str, Any]:
        payload = deepcopy(load_fixture(scenario_code, attempt_number))
        payload["findings"][0]["policyRuleCode"] = "FORMAL_CONFIRMATION"
        return payload

    monkeypatch.setattr(
        analysis_service.fixture_loader,
        "load",
        load_fixture_with_unselected_policy_rule,
    )

    response = call_api(
        "POST",
        "/internal/v1/risk-analyses",
        json=guarantee_request(),
    )

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


def test_ollama_context_and_keep_alive_follow_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AI_PROVIDER", "ollama")
    monkeypatch.setenv("OLLAMA_NUM_CTX", "8192")
    monkeypatch.setenv("OLLAMA_KEEP_ALIVE", "1m")
    request = guarantee_request()
    provider_requests: list[dict[str, Any]] = []

    def provider_response(provider_request: httpx.Request) -> httpx.Response:
        provider_requests.append(json.loads(provider_request.content))
        return httpx.Response(
            200,
            json={
                "message": {
                    "content": json.dumps(
                        {
                            "riskScore": 82,
                            "modelVersion": "qwen2.5:7b-instruct",
                            "promptVersion": RiskAnalysisService.OLLAMA_PROMPT_VERSION,
                            "findings": [
                                {
                                    "statement": "안정성 표현이 원금 손실 가능성을 가릴 수 있습니다.",
                                    "severity": "HIGH",
                                    "policyRuleCode": "STABILITY_KEYWORD",
                                    "affectedPersonaCodes": ["FINANCIAL_BEGINNER"],
                                    "evidenceSpanOptionIds": ["evidence-option-1"],
                                    "knownFactIds": [],
                                    "recommendation": "손실 가능성을 같은 화면에 표시하세요.",
                                }
                            ],
                        }
                    )
                }
            },
        )

    service = RiskAnalysisService(
        http_client=httpx.Client(transport=httpx.MockTransport(provider_response))
    )
    service.analyze(RiskAnalysisRequest.model_validate(request))

    assert provider_requests[0]["options"]["num_ctx"] == 8192
    assert provider_requests[0]["keep_alive"] == "1m"


def test_ollama_num_ctx_rejects_non_positive_value(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OLLAMA_NUM_CTX", "0")

    with pytest.raises(ValueError, match="OLLAMA_NUM_CTX"):
        RiskAnalysisService()
