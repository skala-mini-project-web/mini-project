import os
import secrets
from typing import Annotated

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.errors import AiServiceError
from app.schemas import (
    ErrorResponse,
    HealthResponse,
    RiskAnalysisRequest,
    RiskAnalysisResponse,
)
from app.service import RiskAnalysisService


app = FastAPI(
    title="CrossCheckLab Mock AI Service",
    version="0.1.0",
    description=(
        "Spring Boot 백엔드가 호출하는 내부용 금융상품 판매 리스크 "
        "Mock 분석 API"
    ),
)
analysis_service = RiskAnalysisService()
internal_token = os.getenv(
    "AI_SERVICE_INTERNAL_TOKEN",
    "crosschecklab-local-internal-token",
)
if not internal_token.strip():
    raise RuntimeError("AI_SERVICE_INTERNAL_TOKEN must not be blank.")


def require_internal_token(
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    expected = f"Bearer {internal_token}"
    if authorization is None or not secrets.compare_digest(
        authorization,
        expected,
    ):
        raise AiServiceError(
            error_code="INTERNAL_AUTHENTICATION_REQUIRED",
            message="A valid internal bearer token is required.",
            retryable=False,
            status_code=401,
        )


def error_response(
    *,
    status_code: int,
    error_code: str,
    message: str,
    retryable: bool,
) -> JSONResponse:
    body = ErrorResponse(
        error_code=error_code,
        message=message,
        retryable=retryable,
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(by_alias=True),
    )


@app.exception_handler(AiServiceError)
async def handle_ai_service_error(
    _request: Request,
    error: AiServiceError,
) -> JSONResponse:
    return error_response(
        status_code=error.status_code,
        error_code=error.error_code,
        message=error.message,
        retryable=error.retryable,
    )


@app.exception_handler(RequestValidationError)
async def handle_request_validation_error(
    _request: Request,
    _error: RequestValidationError,
) -> JSONResponse:
    return error_response(
        status_code=422,
        error_code="REQUEST_VALIDATION_FAILED",
        message="Request body does not match the risk analysis contract.",
        retryable=False,
    )


@app.get(
    "/internal/v1/health",
    response_model=HealthResponse,
    tags=["operations"],
)
def health() -> HealthResponse:
    return analysis_service.health()


@app.post(
    "/internal/v1/risk-analyses",
    response_model=RiskAnalysisResponse,
    responses={
        401: {"model": ErrorResponse},
        404: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
    tags=["risk-analysis"],
)
def analyze_risk(
    request: RiskAnalysisRequest,
    _authenticated: Annotated[None, Depends(require_internal_token)],
) -> RiskAnalysisResponse:
    return analysis_service.analyze(request)
