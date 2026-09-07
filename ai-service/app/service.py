import json
import os
import re
from threading import Lock
from typing import Any

import httpx
from pydantic import ValidationError

from app.errors import AiServiceError
from app.fixture_loader import FixtureLoader
from app.schemas import (
    AnalysisProvider,
    EvidenceSpan,
    FindingPayload,
    HealthResponse,
    OllamaRiskAnalysisResponse,
    RedTeamRuleCode,
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
    OLLAMA_PROMPT_VERSION = "ollama-rag-grounded-v13"
    # 100,000 UTF-8 bytes 한글 prompt ≈ 25k token. qwen2.5 7B KV cache 약 1.9GB.
    DEFAULT_OLLAMA_NUM_CTX = 32768
    FIXTURE_POLICY_RULE_CODES = {
        "GUARANTEE_MISUNDERSTANDING_HIGH": RedTeamRuleCode.STABILITY_KEYWORD,
        "EARLY_TERMINATION_COST_MEDIUM": RedTeamRuleCode.COST_OMISSION,
        "ACCESSIBILITY_LOW": RedTeamRuleCode.COGNITIVE_ACCESSIBILITY,
        "PROVIDER_RATE_LIMITED_THEN_SUCCESS": RedTeamRuleCode.STABILITY_KEYWORD,
    }
    MAX_REPAIR_EXCERPTS_PER_CHUNK = 3
    MAX_REPAIR_EXCERPT_CHARS = 400
    MAX_REPAIR_EXCERPT_BYTES = 12_000
    REPAIR_METADATA_LINE_PATTERNS = (
        re.compile(r"^ARGUS\s*\|", re.IGNORECASE),
        re.compile(r"\bSYNTHETIC DEMO CORPUS\b", re.IGNORECASE),
        re.compile(r"^(?:문서번호|버전|데모 규정)\b"),
        re.compile(
            r"^(?:version|document(?:\s+number)?|section)\b",
            re.IGNORECASE,
        ),
        re.compile(r"^\d+\.\s*[^.!?。！？]+$"),
        re.compile(r"^제\d+조(?:의\d+)?\s*\([^)]*\)\s*$"),
    )
    REPAIR_METADATA_MARKERS = (
        "완전 합성",
        "합성 데이터",
        "합성 문서",
        "합성 텍스트",
        "가상 규정",
        "실제 회사 정책이 아닙니다",
        "실제 법령",
        "실제 상품",
        "법적 효력",
        "법적 권위",
        "규제기관 승인",
        "데모 전용",
        "demo only",
    )

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
        # Ollama 기본 context(4096 token)는 confirmedText 상한과 retrieved context를 담지 못하고
        # 초과분을 앞에서 조용히 잘라 system prompt까지 잃는다. aggregate prompt 상한
        # 100,000 UTF-8 bytes가 들어가는 크기를 명시한다.
        self.ollama_num_ctx = self._read_positive_int_env(
            "OLLAMA_NUM_CTX", self.DEFAULT_OLLAMA_NUM_CTX
        )
        # 미설정이면 Ollama 서버 기본값(5m)을 따른다. 로컬 메모리를 줄이려면 "0" 또는 "1m" 등으로 지정한다.
        self.ollama_keep_alive = os.getenv("OLLAMA_KEEP_ALIVE", "").strip() or None
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
        selected_known_fact_id = (
            request.known_facts[0].fact_id if request.known_facts else None
        )
        try:
            payload = {
                **payload,
                "findings": [
                    {
                        **finding,
                        "policyRuleCode": finding.get(
                            "policyRuleCode",
                            self.FIXTURE_POLICY_RULE_CODES[
                                request.scenario_code
                            ].value,
                        ),
                        "knownFactIds": (
                            finding.get("knownFactIds")
                            if finding.get("knownFactIds")
                            else (
                                [selected_known_fact_id]
                                if selected_known_fact_id is not None
                                else []
                            )
                        ),
                    }
                    for finding in payload["findings"]
                ],
            }
            response = RiskAnalysisResponse.model_validate(payload)
            self._validate_grounding(request, response)
        except (ValidationError, TypeError, ValueError):
            raise ProviderResponseInvalidError() from None

        return response

    def _analyze_with_ollama(
        self, request: RiskAnalysisRequest
    ) -> RiskAnalysisResponse:
        response_schema = OllamaRiskAnalysisResponse.model_json_schema(
            by_alias=True
        )
        system_prompt = (
            "You are a financial-product sales risk analyst. Return only JSON "
            "that conforms exactly to the supplied JSON schema. Analyze only "
            "the confirmedText, selected personas and rules, and evidence "
            "contexts retrieved in the user message. Never infer or request "
            "the full evidence documents. Do not use unstated facts. Every "
            "retrievedContextChunkIds entry must be a chunkId selected exactly "
            "from retrievedContexts. Use only chunkId values present in "
            "retrievedContexts; never invent, alter, or substitute an ID. "
            "Return exactly one fully grounded finding. For every cited "
            "chunkId, select at least one evidenceSpanOptionIds value whose "
            "option has that chunkId. Select only exact optionId values from "
            "allowedEvidenceSpanOptions supplied in the user message. Never "
            "invent, alter, or duplicate an option ID, and never select an "
            "option for an uncited chunk. Do not return evidenceSpans or any "
            "free-text evidence excerpt; the server maps selected opaque "
            "option IDs to exact source excerpts. "
            "Never quote confirmedText or any other product text as evidence. "
            "Do not cite unknown or unretrieved contexts. When knownFacts is "
            "non-empty, every finding must select at least one applicable "
            "exact factId supplied in knownFacts for knownFactIds. When "
            "knownFacts is empty, knownFactIds must be empty. Never invent, "
            "alter, or substitute a fact reference. "
            "affectedPersonaCodes may "
            "contain only selected personaCodes. policyRuleCode must be one "
            "exact value selected from ruleCodes; never derive it from "
            "severity, personas, similarity, or finding text. Set "
            "modelVersion to "
            f"{json.dumps(self.ollama_model)} and promptVersion to "
            f"{json.dumps(self.OLLAMA_PROMPT_VERSION)}."
        )
        user_payload = request.model_dump(mode="json", by_alias=True)
        excerpt_options = self._repair_excerpt_options(request)
        user_payload["allowedEvidenceSpanOptions"] = excerpt_options
        serialized_options = json.dumps(
            excerpt_options,
            ensure_ascii=False,
            separators=(",", ":"),
        )
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
            "options": {
                "temperature": 0,
                "seed": 42,
                "num_ctx": self.ollama_num_ctx,
            },
        }
        if self.ollama_keep_alive is not None:
            payload["keep_alive"] = self.ollama_keep_alive

        generated_json = self._call_ollama(payload)
        try:
            ollama_response = OllamaRiskAnalysisResponse.model_validate_json(
                generated_json
            )
            response = self._map_ollama_response(
                ollama_response, excerpt_options
            )
            self._validate_grounding(request, response)
        except (ValidationError, TypeError, ValueError):
            payload["messages"].extend(
                [
                    {"role": "assistant", "content": generated_json},
                    {
                        "role": "user",
                        "content": (
                            "The prior JSON failed the required schema or "
                            "grounding validation. Return a new complete JSON "
                            "response matching the same supplied schema. "
                            "Return exactly one finding, retaining the best "
                            "fully grounded finding from the prior response "
                            "and removing all others. Select only exact "
                            "evidenceSpanOptionIds from the allowed "
                            "source-only options below, with at least one "
                            "selected option for every cited chunk and no "
                            "option for an uncited chunk. Do not return "
                            "evidenceSpans or any free-text evidence excerpt. "
                            "Do not invent, alter, or duplicate option IDs. "
                            "When knownFacts is non-empty, repair empty or "
                            "unknown knownFactIds by selecting at least one "
                            "applicable exact factId from knownFacts. When "
                            "knownFacts is empty, keep knownFactIds empty. Do "
                            "not invent, alter, or substitute a fact "
                            "reference. The "
                            "allowed options are "
                            f"{serialized_options}. "
                            "Apply all other original constraints. Return "
                            "only the new JSON."
                        ),
                    },
                ]
            )
            repaired_json = self._call_ollama(payload)
            try:
                ollama_response = (
                    OllamaRiskAnalysisResponse.model_validate_json(
                        repaired_json
                    )
                )
                response = self._map_ollama_response(
                    ollama_response, excerpt_options
                )
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
    def _read_positive_int_env(name: str, default: int) -> int:
        raw = os.getenv(name, "").strip()
        if not raw:
            return default
        try:
            value = int(raw)
        except ValueError as error:
            raise ValueError(f"{name} must be a positive integer.") from error
        if value <= 0:
            raise ValueError(f"{name} must be a positive integer.")
        return value

    def _call_ollama(self, payload: dict[str, Any]) -> str:
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
            if not isinstance(generated_json, str):
                raise TypeError
            return generated_json
        except (KeyError, TypeError, ValueError):
            raise ProviderResponseInvalidError() from None

    @classmethod
    def _repair_excerpt_options(
        cls,
        request: RiskAnalysisRequest,
    ) -> list[dict[str, Any]]:
        options: list[dict[str, Any]] = []
        remaining_bytes = cls.MAX_REPAIR_EXCERPT_BYTES
        context_count = len(request.retrieved_contexts)
        for index, context in enumerate(request.retrieved_contexts):
            excerpts: list[str] = []
            context_budget = remaining_bytes // (context_count - index)
            for source_line in context.chunk_text.splitlines():
                line = source_line.strip()
                if cls._is_repair_metadata_line(line):
                    continue
                candidates = re.findall(
                    r".+?[.!?。！？]+(?:[\"'”’」』]+)?(?=\s|$)",
                    line,
                )
                for candidate in candidates:
                    excerpt = candidate.strip()
                    encoded_size = len(excerpt.encode("utf-8"))
                    if (
                        not excerpt
                        or len(excerpt) > cls.MAX_REPAIR_EXCERPT_CHARS
                        or encoded_size > context_budget
                    ):
                        continue
                    excerpts.append(excerpt)
                    context_budget -= encoded_size
                    remaining_bytes -= encoded_size
                    if len(excerpts) == cls.MAX_REPAIR_EXCERPTS_PER_CHUNK:
                        break
                if len(excerpts) == cls.MAX_REPAIR_EXCERPTS_PER_CHUNK:
                    break
                if context_budget == 0:
                    break
            if excerpts:
                for excerpt in excerpts:
                    options.append(
                        {
                            "optionId": f"evidence-option-{len(options) + 1}",
                            "chunkId": context.chunk_id,
                            "excerpt": excerpt,
                        }
                    )
        return options

    @staticmethod
    def _map_ollama_response(
        response: OllamaRiskAnalysisResponse,
        excerpt_options: list[dict[str, Any]],
    ) -> RiskAnalysisResponse:
        if len(response.findings) != 1:
            raise ValueError(
                "An Ollama response must contain exactly one finding."
            )
        options_by_id = {
            option["optionId"]: option for option in excerpt_options
        }
        finding = response.findings[0]
        selected_option_ids = finding.evidence_span_option_ids
        if len(selected_option_ids) != len(set(selected_option_ids)):
            raise ValueError("Finding contains duplicate evidence option ids.")

        cited_chunk_ids = set(finding.retrieved_context_chunk_ids)
        selected_options: list[dict[str, Any]] = []
        for option_id in selected_option_ids:
            option = options_by_id.get(option_id)
            if option is None:
                raise ValueError(
                    "Finding contains an unknown evidence option id."
                )
            if option["chunkId"] not in cited_chunk_ids:
                raise ValueError(
                    "Evidence option refers to an uncited context."
                )
            selected_options.append(option)

        selected_chunk_ids = {
            option["chunkId"] for option in selected_options
        }
        if selected_chunk_ids != cited_chunk_ids:
            raise ValueError("A cited context is missing an evidence option.")

        mapped_finding = FindingPayload(
            statement=finding.statement,
            severity=finding.severity,
            policy_rule_code=finding.policy_rule_code,
            affected_persona_codes=finding.affected_persona_codes,
            retrieved_context_chunk_ids=finding.retrieved_context_chunk_ids,
            evidence_spans=[
                EvidenceSpan(
                    chunk_id=option["chunkId"],
                    excerpt=option["excerpt"],
                )
                for option in selected_options
            ],
            known_fact_ids=finding.known_fact_ids,
            recommendation=finding.recommendation,
        )
        return RiskAnalysisResponse(
            risk_score=response.risk_score,
            model_version=response.model_version,
            prompt_version=response.prompt_version,
            findings=[mapped_finding],
        )

    @classmethod
    def _is_repair_metadata_line(cls, line: str) -> bool:
        if not line:
            return True
        normalized = line.casefold()
        if any(
            marker.casefold() in normalized
            for marker in cls.REPAIR_METADATA_MARKERS
        ):
            return True
        if any(
            pattern.search(line)
            for pattern in cls.REPAIR_METADATA_LINE_PATTERNS
        ):
            return True
        return not re.search(r"[.!?。！？]", line)

    @staticmethod
    def _validate_grounding(
        request: RiskAnalysisRequest,
        response: RiskAnalysisResponse,
    ) -> None:
        selected_personas = set(request.persona_codes)
        selected_rule_codes = set(request.rule_codes)
        selected_evidence = set(request.selected_evidence_document_ids)
        contexts_by_chunk_id = {
            context.chunk_id: context for context in request.retrieved_contexts
        }

        for finding in response.findings:
            if finding.policy_rule_code not in selected_rule_codes:
                raise ValueError("Finding contains an unselected policy rule.")
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
            if known_fact_ids and not finding.known_fact_ids:
                raise ValueError("Finding is missing a known fact reference.")
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
