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
    def __init__(
        self,
        message: str = "The configured Ollama provider rejected the request.",
    ) -> None:
        super().__init__(
            error_code="AI_PROVIDER_REQUEST_REJECTED",
            message=message,
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
    OLLAMA_PROMPT_VERSION = "ollama-rag-grounded-v17"
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
        re.compile(r"^(?:.+\s)?(?:내부\s*정책|내부정책|비권위 선언)$"),
        re.compile(
            r"^(?:합성 조문 해설|발췌 범위|출처|데모 적용 예|"
            r"합성 금융소비자 설명의무 규정 발췌)$"
        ),
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
            "the full evidence documents. Do not use unstated facts. "
            "Return 0 to 20 fully grounded findings. Return an empty findings "
            "array only when there is no supported finding in the selected "
            "scope; it does not assert safety or legal compliance. When "
            "findings is empty, omit riskScore or set it to null, never zero "
            "or another number. A non-empty findings array may have a null "
            "or omitted diagnostic riskScore. Findings under distinct selected "
            "rules are distinct risks. Do not repeat the same rule and source "
            "claim as multiple findings. Treat the two citation roles as "
            "separate. evidenceSpanOptionIds are only POLICY_REQUIREMENT "
            "citations to retrieved normative or source-policy text. Return "
            "only exact evidenceSpanOptionIds values from "
            "allowedEvidenceSpanOptions supplied in the user message. Never "
            "invent, alter, or duplicate an option ID within a finding, and "
            "select at least one option for every finding. The same option ID "
            "may ground different findings. Do not return "
            "retrievedContextChunkIds, evidenceSpans, or any free-text "
            "evidence excerpt; the server derives citation chunk IDs and maps "
            "selected opaque option IDs to exact source excerpts. "
            "Never put confirmedText, product text, or known facts into "
            "evidenceSpanOptionIds. Do not cite unknown or unretrieved "
            "contexts. knownFactIds are separate DOCUMENT_CLAIM provenance. "
            "When a supplied known fact records product wording containing "
            "the claim criticized by a finding, cite that actual applicable "
            "factId. CONFIRMED_DOCUMENT verification confirms the wording and "
            "source, not the truth or compliance of a marketing claim; citing "
            "the fact does not endorse the claim. knownFactIds may be empty "
            "when no supplied known fact contains or applies to the criticized "
            "claim, even when knownFacts is non-empty. Any selected "
            "knownFactIds must be exact applicable factId values supplied in "
            "knownFacts. Never invent, alter, or substitute a fact reference, "
            "and never select the first fact merely because it was supplied. "
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
        if not excerpt_options:
            raise OllamaRequestRejectedError(
                "The request contains no usable source evidence excerpts."
            )
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
                            "Return 0 to 20 fully grounded findings. Return an "
                            "empty findings array only when there is no "
                            "supported finding in the selected scope; it does "
                            "not assert safety or legal compliance. For an "
                            "empty findings array, omit riskScore or set it to "
                            "null. A non-empty findings array may have a null "
                            "or omitted diagnostic riskScore. Findings under "
                            "distinct selected rules are distinct risks; do "
                            "not repeat the same rule and source claim. Select "
                            "only exact "
                            "evidenceSpanOptionIds from the allowed "
                            "source-only options below, with at least one "
                            "selected option for every finding. These options "
                            "are only POLICY_REQUIREMENT citations to "
                            "retrieved normative or source-policy text. Never "
                            "put confirmedText, product text, or known facts "
                            "into evidenceSpanOptionIds. Do not return "
                            "retrievedContextChunkIds, evidenceSpans, or any "
                            "free-text evidence excerpt. The server derives "
                            "citation chunk IDs and exact evidence spans from "
                            "the selected options. "
                            "Do not invent, alter, or duplicate option IDs "
                            "within a finding; the same option may be used by "
                            "different findings. knownFactIds are separate "
                            "DOCUMENT_CLAIM provenance. When a supplied known "
                            "fact records product wording containing the claim "
                            "criticized by a finding, cite that actual "
                            "applicable factId. CONFIRMED_DOCUMENT verification "
                            "confirms the wording and source, not the truth or "
                            "compliance of a marketing claim; citing the fact "
                            "does not endorse the claim. knownFactIds may be "
                            "empty when no supplied known fact contains or "
                            "applies to the criticized claim, even when "
                            "knownFacts is non-empty. Any selected knownFactIds "
                            "must be exact applicable factId values from "
                            "knownFacts. Do not invent, alter, or substitute a "
                            "fact reference, and never select the first fact "
                            "merely because it was supplied. The "
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

        try:
            return RiskAnalysisResponse.model_validate(
                {
                    **response.model_dump(),
                    "model_version": self.ollama_model,
                    "prompt_version": self.OLLAMA_PROMPT_VERSION,
                }
            )
        except ValidationError:
            raise ProviderResponseInvalidError() from None

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
                    r".+?(?:(?<!\d)[.!?。！？]+(?:[\"'”’」』]+)?"
                    r"(?=\s|$)|$)",
                    line,
                )
                for candidate in candidates:
                    for excerpt in cls._bounded_repair_excerpts(
                        candidate,
                        context_budget,
                    ):
                        encoded_size = len(excerpt.encode("utf-8"))
                        excerpts.append(excerpt)
                        context_budget -= encoded_size
                        remaining_bytes -= encoded_size
                        if (
                            len(excerpts)
                            == cls.MAX_REPAIR_EXCERPTS_PER_CHUNK
                        ):
                            break
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

    @classmethod
    def _bounded_repair_excerpts(
        cls,
        source: str,
        byte_budget: int,
    ) -> list[str]:
        excerpts: list[str] = []
        cursor = 0
        while (
            cursor < len(source)
            and byte_budget > 0
            and len(excerpts) < cls.MAX_REPAIR_EXCERPTS_PER_CHUNK
        ):
            while cursor < len(source) and source[cursor].isspace():
                cursor += 1
            if cursor == len(source):
                break

            end = min(cursor + cls.MAX_REPAIR_EXCERPT_CHARS, len(source))
            while (
                end > cursor
                and len(source[cursor:end].encode("utf-8")) > byte_budget
            ):
                end -= 1
            if end == cursor:
                break

            if end < len(source):
                boundary = max(
                    source.rfind(" ", cursor, end),
                    source.rfind("\t", cursor, end),
                )
                if boundary > cursor:
                    end = boundary

            excerpt = source[cursor:end].strip()
            if not excerpt:
                break
            excerpts.append(excerpt)
            byte_budget -= len(excerpt.encode("utf-8"))
            cursor = end
        return excerpts

    @staticmethod
    def _map_ollama_response(
        response: OllamaRiskAnalysisResponse,
        excerpt_options: list[dict[str, Any]],
    ) -> RiskAnalysisResponse:
        options_by_id = {
            option["optionId"]: option for option in excerpt_options
        }
        mapped_findings: list[FindingPayload] = []
        for finding in response.findings:
            selected_options: list[dict[str, Any]] = []
            selected_option_ids: set[str] = set()
            for option_id in finding.evidence_span_option_ids:
                if option_id in selected_option_ids:
                    raise ValueError(
                        "Finding contains a duplicate evidence option id."
                    )
                selected_option_ids.add(option_id)
                option = options_by_id.get(option_id)
                if option is None:
                    raise ValueError(
                        "Finding contains an unknown evidence option id."
                    )
                selected_options.append(option)
            if not selected_options:
                raise ValueError("Finding is missing a source evidence option.")

            selected_chunk_ids = list(
                dict.fromkeys(option["chunkId"] for option in selected_options)
            )

            mapped_findings.append(
                FindingPayload(
                    statement=finding.statement,
                    severity=finding.severity,
                    policy_rule_code=finding.policy_rule_code,
                    affected_persona_codes=finding.affected_persona_codes,
                    retrieved_context_chunk_ids=selected_chunk_ids,
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
            )
        return RiskAnalysisResponse(
            risk_score=response.risk_score,
            model_version=response.model_version,
            prompt_version=response.prompt_version,
            findings=mapped_findings,
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
        return False

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
