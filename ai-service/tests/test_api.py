import asyncio
from copy import deepcopy
from typing import Any

import httpx

from app.main import app


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
        "redTeamPackCode": "DEFAULT_RED_TEAM_PACK",
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


def test_returns_retryable_error_fixture() -> None:
    request = guarantee_request()
    request["scenarioCode"] = "PROVIDER_RATE_LIMITED_THEN_SUCCESS"

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 503
    assert response.json()["errorCode"] == "AI_SERVICE_TEMPORARY_FAILURE"
    assert response.json()["retryable"] is True


def test_rejects_duplicate_persona_codes() -> None:
    request = guarantee_request()
    request["personaCodes"] = ["SENIOR", "SENIOR"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 422
    assert response.json()["errorCode"] == "REQUEST_VALIDATION_FAILED"
    assert response.json()["retryable"] is False


def test_detects_fixture_persona_not_selected_by_request() -> None:
    request = deepcopy(guarantee_request())
    request["personaCodes"] = ["FINANCIAL_BEGINNER"]

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "FIXTURE_INVALID"
    assert "SENIOR" in response.json()["message"]


def test_detects_fixture_evidence_not_in_request() -> None:
    request = deepcopy(guarantee_request())
    request["evidenceDocuments"][0]["id"] = 99

    response = call_api("POST", "/internal/v1/risk-analyses", json=request)

    assert response.status_code == 500
    assert response.json()["errorCode"] == "FIXTURE_INVALID"
    assert "1" in response.json()["message"]
