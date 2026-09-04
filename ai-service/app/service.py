import json
import os
from threading import Lock
from typing import Any

import httpx
from pydantic import ValidationError

from app.errors import AiServiceError, FixtureInvalidError
from app.fixture_loader import FixtureLoader
from app.schemas import (
    AnalysisProvider,
    EvidenceReference,
    FindingPayload,
    HealthResponse,
    RiskAnalysisRequest,
    RiskAnalysisResponse,
)


class OllamaUnavailableError(AiServiceError):
    def __init__(self) -> None:
        super().__init__(
            error_code="AI_PROVIDER_UNAVAILABLE",
            message="The configured Ollama provider is unavailable.",
            retryable=True,
            status_code=503,
        )


class OllamaResponseInvalidError(AiServiceError):
    def __init__(self) -> None:
        super().__init__(
            error_code="AI_PROVIDER_RESPONSE_INVALID",
            message="The Ollama response does not match the analysis contract.",
            retryable=False,
            status_code=500,
        )


class RiskAnalysisService:
    OLLAMA_PROMPT_VERSION = "ollama-grounded-v1"

    def __init__(
        self,
        fixture_loader: FixtureLoader | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self.fixture_loader = fixture_loader or FixtureLoader()
        provider_value = os.getenv("AI_PROVIDER", AnalysisProvider.FIXTURE.value)
        try:
            self.provider = AnalysisProvider(provider_value.strip().lower())
        except ValueError as error:
            raise ValueError(
                "AI_PROVIDER must be either 'fixture' or 'ollama'."
            ) from error
        self.ollama_base_url = os.getenv(
            "OLLAMA_BASE_URL", "http://127.0.0.1:11434"
        ).rstrip("/")
        self.ollama_model = os.getenv(
            "OLLAMA_MODEL", "qwen2.5:7b-instruct"
        )
        self.http_client = http_client or httpx.Client(timeout=60.0)
        self._attempts: dict[tuple[int, str], int] = {}
        self._attempt_lock = Lock()

    def health(self) -> HealthResponse:
        if self.provider == AnalysisProvider.FIXTURE:
            return HealthResponse(status="UP", provider=self.provider)

        try:
            response = self.http_client.get(
                f"{self.ollama_base_url}/api/tags",
                timeout=5.0,
            )
            response.raise_for_status()
            payload = response.json()
            available_models = {
                model_name
                for model in payload["models"]
                for model_name in (model.get("name"), model.get("model"))
                if model_name
            }
        except (
            httpx.HTTPError,
            AttributeError,
            KeyError,
            TypeError,
            ValueError,
        ):
            raise OllamaUnavailableError() from None

        if self.ollama_model not in available_models:
            raise OllamaUnavailableError()
        return HealthResponse(status="UP", provider=self.provider)

    def analyze(self, request: RiskAnalysisRequest) -> RiskAnalysisResponse:
        if self.provider == AnalysisProvider.OLLAMA:
            return self._analyze_with_ollama(request)

        attempt_number = self._next_attempt(request)
        payload = self.fixture_loader.load(request.scenario_code, attempt_number)
        try:
            response = RiskAnalysisResponse.model_validate(payload)
        except ValidationError as error:
            raise FixtureInvalidError(
                f"Fixture response does not match the contract: {error}"
            ) from error

        return self._adapt_to_request_selections(request, response)

    def _analyze_with_ollama(
        self, request: RiskAnalysisRequest
    ) -> RiskAnalysisResponse:
        response_schema = RiskAnalysisResponse.model_json_schema(by_alias=True)
        system_prompt = (
            "You are a financial-product sales risk analyst. Return only JSON "
            "that conforms exactly to the supplied JSON schema. Analyze only "
            "the confirmedText, selected personas and rules, and evidence "
            "documents in the user message. Do not use unstated facts. Every "
            "finding must cite at least one supplied evidence document, use "
            "its exact positive integer id, and quote an exact non-empty "
            "excerpt from that document's content. affectedPersonaCodes may "
            "contain only selected personaCodes. Set modelVersion to "
            f"{json.dumps(self.ollama_model)} and promptVersion to "
            f"{json.dumps(self.OLLAMA_PROMPT_VERSION)}."
        )
        payload: dict[str, Any] = {
            "model": self.ollama_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": request.model_dump_json(by_alias=True),
                },
            ],
            "format": response_schema,
            "stream": False,
            "options": {"temperature": 0},
        }

        try:
            provider_response = self.http_client.post(
                f"{self.ollama_base_url}/api/chat",
                json=payload,
            )
            provider_response.raise_for_status()
        except httpx.HTTPError:
            raise OllamaUnavailableError() from None

        try:
            generated_json = provider_response.json()["message"]["content"]
        except (KeyError, TypeError, ValueError):
            raise OllamaResponseInvalidError() from None

        try:
            response = RiskAnalysisResponse.model_validate_json(generated_json)
            self._validate_grounding(request, response)
        except (ValidationError, TypeError, ValueError):
            raise OllamaResponseInvalidError() from None

        return response.model_copy(
            update={
                "model_version": self.ollama_model,
                "prompt_version": self.OLLAMA_PROMPT_VERSION,
            }
        )

    @staticmethod
    def _validate_grounding(
        request: RiskAnalysisRequest,
        response: RiskAnalysisResponse,
    ) -> None:
        selected_personas = set(request.persona_codes)
        evidence_by_id = {
            document.id: document for document in request.evidence_documents
        }

        for finding in response.findings:
            if not set(finding.affected_persona_codes) <= selected_personas:
                raise ValueError("Finding contains an unselected persona.")
            if not finding.evidence_references:
                raise ValueError("Finding is missing source evidence.")
            for reference in finding.evidence_references:
                document = evidence_by_id.get(reference.evidence_document_id)
                if document is None:
                    raise ValueError("Finding cites unknown evidence.")
                excerpt = " ".join(reference.excerpt.split())
                content = " ".join(document.content.split())
                if excerpt not in content:
                    raise ValueError("Evidence excerpt is not a source quote.")

    def _next_attempt(self, request: RiskAnalysisRequest) -> int:
        if not self.fixture_loader.tracks_attempts(request.scenario_code):
            return 1

        key = (request.analysis_id, request.scenario_code)
        with self._attempt_lock:
            attempt_number = self._attempts.get(key, 0) + 1
            self._attempts[key] = attempt_number
            return attempt_number

    @staticmethod
    def _adapt_to_request_selections(
        request: RiskAnalysisRequest,
        response: RiskAnalysisResponse,
    ) -> RiskAnalysisResponse:
        selected_personas = set(request.persona_codes)
        adapted_findings: list[FindingPayload] = []

        for finding in response.findings:
            affected_personas = [
                persona
                for persona in finding.affected_persona_codes
                if persona in selected_personas
            ]
            if not affected_personas:
                affected_personas = list(request.persona_codes)

            evidence_references = [
                RiskAnalysisService._evidence_reference_for(
                    request,
                    reference_index,
                    reference,
                )
                for reference_index, reference in enumerate(
                    finding.evidence_references
                )
            ]
            adapted_findings.append(
                finding.model_copy(
                    update={
                        "affected_persona_codes": affected_personas,
                        "evidence_references": evidence_references,
                    }
                )
            )

        return response.model_copy(update={"findings": adapted_findings})

    @staticmethod
    def _evidence_reference_for(
        request: RiskAnalysisRequest,
        reference_index: int,
        reference: EvidenceReference,
    ) -> EvidenceReference:
        evidence_by_id = {
            document.id: document for document in request.evidence_documents
        }
        document = evidence_by_id.get(reference.evidence_document_id)
        if document is None:
            document = request.evidence_documents[
                reference_index % len(request.evidence_documents)
            ]

        normalized_excerpt = (
            " ".join(document.content.split())[:500] or reference.excerpt
        )
        return reference.model_copy(
            update={
                "evidence_document_id": document.id,
                "excerpt": normalized_excerpt,
            }
        )
