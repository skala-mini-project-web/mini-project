import asyncio
import json
from copy import deepcopy
from typing import Any

import httpx
import pytest

from app.main import analysis_service, app
from app.schemas import AnalysisProvider


def call_api(
    method: str,
    path: str,
    json: dict[str, Any] | None = None,
) -> httpx.Response:
    async def send() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as client:
            return await client.request(method, path, json=json)

    return asyncio.run(send())


def guarantee_request() -> dict:
    return {
        "analysisId": 1,
        "scenarioCode": "GUARANTEE_MISUNDERSTANDING_HIGH",
        "confirmedText": "최근 안정적인 수익률을 기록한 투자상품입니다.",
        "personaCodes": ["FINANCIAL_BEGINNER", "SENIOR"],
        "redTeamPackCode": "CORE_FINANCIAL_RISK_V1",
        "ruleCodes": ["STABILITY_KEYWORD", "LOSS_SOFTENING"],
        "evidenceDocuments": [
            {
                "id": 1,
                "sourceType": "INTERNAL_POLICY",
                "title": "금융상품 설명 내부준칙",
                "content": "원금손실 가능성은 안정성 표현과 인접하여 표시해야 합니다.",
            }
        ],
    }


def test_health() -> None:
    response = call_api("GET", "/internal/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "provider": "fixture"}


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
    assert body["findings"][0]["evidenceReferences"][0][
        "evidenceDocumentId"
    ] == 1


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
                "evidenceReferences": [
                    {
                        "evidenceDocumentId": 1,
                        "excerpt": request["evidenceDocuments"][0]["content"],
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


def test_rejects_fixture_output_with_unselected_persona() -> None:
    request = deepcopy(guarantee_request())
    request["personaCodes"] = ["FINANCIAL_BEGINNER"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


def test_rejects_fixture_output_citing_unknown_evidence() -> None:
    request = deepcopy(guarantee_request())
    request["evidenceDocuments"][0]["id"] = 99

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "AI_PROVIDER_RESPONSE_INVALID"
    assert response.json()["retryable"] is False


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
