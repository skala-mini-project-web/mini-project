import json
import os
from threading import Lock
from typing import Any

import httpx
from pydantic import ValidationError

from app.errors import AiServiceError
from app.fixture_loader import FixtureLoader
from app.schemas import (
    AnalysisProvider,
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


class OllamaRequestRejectedError(AiServiceError):
    def __init__(self) -> None:
        super().__init__(
            error_code="AI_PROVIDER_REQUEST_REJECTED",
            message="The configured Ollama provider rejected the request.",
            retryable=False,
            status_code=422,
        )


class ProviderResponseInvalidError(AiServiceError):
    def __init__(self) -> None:
        super().__init__(
            error_code="AI_PROVIDER_RESPONSE_INVALID",
            message="The provider response does not match the analysis contract.",
            retryable=False,
            status_code=500,
        )


class RiskAnalysisService:
    OLLAMA_PROMPT_VERSION = "ollama-rag-grounded-v6"

    def __init__(
        self,
        fixture_loader: FixtureLoader | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self.fixture_loader = fixture_loader or FixtureLoader()
        provider_value = os.getenv("AI_PROVIDER", AnalysisProvider.OLLAMA.value)
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
            self._validate_grounding(request, response)
        except (ValidationError, TypeError, ValueError):
            raise ProviderResponseInvalidError() from None

        return response

    def _analyze_with_ollama(
        self, request: RiskAnalysisRequest
    ) -> RiskAnalysisResponse:
        response_schema = RiskAnalysisResponse.model_json_schema(by_alias=True)
        system_prompt = (
            "You are a financial-product sales risk analyst. Return only JSON "
            "that conforms exactly to the supplied JSON schema. Analyze only "
            "the confirmedText, selected personas and rules, and evidence "
            "contexts retrieved in the user message. Never infer or request "
            "the full evidence documents. Do not use unstated facts. Every "
            "retrievedContextChunkIds entry must be a chunkId selected exactly "
            "from retrievedContexts. Use only chunkId values present in "
            "retrievedContexts; never invent, alter, or substitute an ID. "
            "For every cited chunkId, include at least one evidenceSpans entry "
            "whose chunkId is that cited ID and whose excerpt is a nonblank, "
            "exact contiguous substring copied verbatim from that chunk's "
            "chunkText. Preserve its whitespace, punctuation, and casing. "
            "Every evidence span must refer to a cited chunk, and duplicate "
            "spans are forbidden. "
            "One well-grounded finding is sufficient. "
            "Never quote confirmedText or any other product text as evidence. "
            "Do not cite unknown or unretrieved contexts. knownFactIds "
            "may contain only exact factId values supplied in knownFacts; "
            "leave it empty rather than inventing or substituting a fact "
            "reference. "
            "affectedPersonaCodes may "
            "contain only selected personaCodes. Set modelVersion to "
            f"{json.dumps(self.ollama_model)} and promptVersion to "
            f"{json.dumps(self.OLLAMA_PROMPT_VERSION)}."
        )
        user_payload = request.model_dump(mode="json", by_alias=True)
        payload: dict[str, Any] = {
            "model": self.ollama_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": json.dumps(
                        user_payload,
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                },
            ],
            "format": response_schema,
            "stream": False,
            "options": {"temperature": 0, "seed": 42},
        }

        try:
            provider_response = self.http_client.post(
                f"{self.ollama_base_url}/api/chat",
                json=payload,
            )
            provider_response.raise_for_status()
        except httpx.HTTPStatusError as error:
            status_code = error.response.status_code
            if status_code != 429 and 400 <= status_code < 500:
                raise OllamaRequestRejectedError() from None
            raise OllamaUnavailableError() from None
        except httpx.RequestError:
            raise OllamaUnavailableError() from None

        try:
            generated_json = provider_response.json()["message"]["content"]
        except (KeyError, TypeError, ValueError):
            raise ProviderResponseInvalidError() from None

        try:
            response = RiskAnalysisResponse.model_validate_json(generated_json)
            self._validate_grounding(request, response)
        except (ValidationError, TypeError, ValueError):
            raise ProviderResponseInvalidError() from None

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
        selected_evidence = set(request.selected_evidence_document_ids)
        contexts_by_chunk_id = {
            context.chunk_id: context for context in request.retrieved_contexts
        }

        for finding in response.findings:
            if not set(finding.affected_persona_codes) <= selected_personas:
                raise ValueError("Finding contains an unselected persona.")
            if not finding.retrieved_context_chunk_ids:
                raise ValueError("Finding is missing source evidence.")
            cited_chunk_ids = set(finding.retrieved_context_chunk_ids)
            for chunk_id in finding.retrieved_context_chunk_ids:
                context = contexts_by_chunk_id.get(chunk_id)
                if context is None:
                    raise ValueError("Finding cites an unretrieved context.")
                if context.evidence_document_id not in selected_evidence:
                    raise ValueError("Finding cites unselected evidence.")
            spanned_chunk_ids: set[int] = set()
            seen_spans: set[tuple[int, str]] = set()
            for span in finding.evidence_spans:
                context = contexts_by_chunk_id.get(span.chunk_id)
                if context is None or span.chunk_id not in cited_chunk_ids:
                    raise ValueError(
                        "Evidence span refers to an uncited context."
                    )
                identity = (span.chunk_id, span.excerpt)
                if identity in seen_spans:
                    raise ValueError("Finding contains duplicate evidence spans.")
                seen_spans.add(identity)
                if not span.excerpt.strip():
                    raise ValueError("Evidence span excerpt is blank.")
                if span.excerpt not in context.chunk_text:
                    raise ValueError(
                        "Evidence span is not an exact context excerpt."
                    )
                spanned_chunk_ids.add(span.chunk_id)
            if spanned_chunk_ids != cited_chunk_ids:
                raise ValueError("A cited context is missing an evidence span.")
        RiskAnalysisService._validate_fact_references(request, response)

    @staticmethod
    def _validate_fact_references(
        request: RiskAnalysisRequest,
        response: RiskAnalysisResponse,
    ) -> None:
        known_fact_ids = {fact.fact_id for fact in request.known_facts}
        for finding in response.findings:
            if not set(finding.known_fact_ids) <= known_fact_ids:
                raise ValueError("Finding cites an unknown fact.")

    def _next_attempt(self, request: RiskAnalysisRequest) -> int:
        if not self.fixture_loader.tracks_attempts(request.scenario_code):
            return 1

        key = (request.analysis_id, request.scenario_code)
        with self._attempt_lock:
            attempt_number = self._attempts.get(key, 0) + 1
            self._attempts[key] = attempt_number
            return attempt_number
