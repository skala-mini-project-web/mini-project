from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class AnalysisProvider(StrEnum):
    FIXTURE = "fixture"
    OLLAMA = "ollama"


class PersonaCode(StrEnum):
    FINANCIAL_BEGINNER = "FINANCIAL_BEGINNER"
    SENIOR = "SENIOR"
    LOSS_EXPERIENCED = "LOSS_EXPERIENCED"
    SHORT_TERM_LIQUIDITY = "SHORT_TERM_LIQUIDITY"
    SELF_EMPLOYED = "SELF_EMPLOYED"


class RedTeamRuleCode(StrEnum):
    RETURN_FRAMING = "RETURN_FRAMING"
    LOSS_SOFTENING = "LOSS_SOFTENING"
    COST_OMISSION = "COST_OMISSION"
    STABILITY_KEYWORD = "STABILITY_KEYWORD"
    FORMAL_CONFIRMATION = "FORMAL_CONFIRMATION"
    COGNITIVE_ACCESSIBILITY = "COGNITIVE_ACCESSIBILITY"


class RedTeamPackCode(StrEnum):
    CORE_FINANCIAL_RISK_V1 = "CORE_FINANCIAL_RISK_V1"


class EvidenceSourceType(StrEnum):
    INTERNAL_POLICY = "INTERNAL_POLICY"
    REGULATION = "REGULATION"
    PRODUCT_POLICY = "PRODUCT_POLICY"


class Severity(StrEnum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class RetrievedContext(ApiModel):
    chunk_id: int = Field(gt=0)
    evidence_document_id: int = Field(gt=0)
    source_type: EvidenceSourceType
    title: str = Field(min_length=1, max_length=255)
    chunk_text: str = Field(min_length=1)
    rank: int = Field(gt=0)
    similarity: float = Field(ge=-1, le=1)

    @field_validator("title", "chunk_text")
    @classmethod
    def context_text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("retrieved context text must not be blank")
        return value


class KnownFact(ApiModel):
    fact_id: int = Field(gt=0)
    text: str = Field(min_length=1)

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("known fact text must not be blank")
        return value


class RiskAnalysisRequest(ApiModel):
    analysis_id: int = Field(gt=0)
    scenario_code: str = Field(min_length=1, max_length=80)
    confirmed_text: str = Field(min_length=1)
    persona_codes: list[PersonaCode] = Field(min_length=1, max_length=4)
    red_team_pack_code: RedTeamPackCode
    rule_codes: list[RedTeamRuleCode] = Field(min_length=1)
    selected_evidence_document_ids: list[int] = Field(
        min_length=1, max_length=3
    )
    retrieved_contexts: list[RetrievedContext] = Field(min_length=1)
    known_facts: list[KnownFact] = Field(default_factory=list)

    @field_validator("confirmed_text")
    @classmethod
    def confirmed_text_must_not_be_blank(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("confirmedText must not be blank")
        return stripped

    @field_validator("persona_codes", "rule_codes")
    @classmethod
    def codes_must_be_unique(cls, value: list[StrEnum]) -> list[StrEnum]:
        if len(value) != len(set(value)):
            raise ValueError("duplicate codes are not allowed")
        return value

    @field_validator("selected_evidence_document_ids")
    @classmethod
    def selected_evidence_document_ids_must_be_valid(
        cls, value: list[int]
    ) -> list[int]:
        if any(identifier <= 0 for identifier in value):
            raise ValueError("evidence document ids must be positive")
        if len(value) != len(set(value)):
            raise ValueError("duplicate evidence document ids are not allowed")
        return value

    @field_validator("retrieved_contexts")
    @classmethod
    def retrieved_contexts_must_be_ranked(
        cls, value: list[RetrievedContext]
    ) -> list[RetrievedContext]:
        chunk_ids = [context.chunk_id for context in value]
        if len(chunk_ids) != len(set(chunk_ids)):
            raise ValueError("duplicate retrieved chunk ids are not allowed")
        expected_ranks = list(range(1, len(value) + 1))
        if [context.rank for context in value] != expected_ranks:
            raise ValueError("retrieved contexts must be ordered by contiguous rank")
        return value

    @field_validator("known_facts")
    @classmethod
    def known_facts_must_be_unique(
        cls, value: list[KnownFact]
    ) -> list[KnownFact]:
        identifiers = [fact.fact_id for fact in value]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("duplicate known fact ids are not allowed")
        return value

    @model_validator(mode="after")
    def scenario_must_match_selected_rules(self) -> "RiskAnalysisRequest":
        required_rule_by_scenario = {
            "GUARANTEE_MISUNDERSTANDING_HIGH": RedTeamRuleCode.STABILITY_KEYWORD,
            "EARLY_TERMINATION_COST_MEDIUM": RedTeamRuleCode.COST_OMISSION,
            "ACCESSIBILITY_LOW": RedTeamRuleCode.COGNITIVE_ACCESSIBILITY,
            "PROVIDER_RATE_LIMITED_THEN_SUCCESS": RedTeamRuleCode.STABILITY_KEYWORD,
        }
        required_rule = required_rule_by_scenario.get(self.scenario_code)
        if required_rule is not None and required_rule not in self.rule_codes:
            raise ValueError(
                f"scenarioCode '{self.scenario_code}' requires "
                f"ruleCode '{required_rule.value}'"
            )
        selected = set(self.selected_evidence_document_ids)
        if any(
            context.evidence_document_id not in selected
            for context in self.retrieved_contexts
        ):
            raise ValueError(
                "retrieved contexts must belong to selected evidence documents"
            )
        return self


class FindingPayload(ApiModel):
    statement: str = Field(min_length=1, max_length=1000)
    severity: Severity
    affected_persona_codes: list[PersonaCode] = Field(min_length=1)
    retrieved_context_chunk_ids: list[int] = Field(min_length=1)
    known_fact_ids: list[int] = Field(default_factory=list)
    recommendation: str | None = Field(default=None, max_length=1000)

    @field_validator("affected_persona_codes")
    @classmethod
    def affected_personas_must_be_unique(
        cls, value: list[PersonaCode]
    ) -> list[PersonaCode]:
        if len(value) != len(set(value)):
            raise ValueError("duplicate affected persona codes are not allowed")
        return value

    @field_validator("retrieved_context_chunk_ids")
    @classmethod
    def retrieved_context_chunk_ids_must_be_valid(
        cls, value: list[int]
    ) -> list[int]:
        if any(identifier <= 0 for identifier in value):
            raise ValueError("retrieved context chunk ids must be positive")
        if len(value) != len(set(value)):
            raise ValueError(
                "duplicate retrieved context chunk ids are not allowed"
            )
        return value

    @field_validator("known_fact_ids")
    @classmethod
    def known_fact_ids_must_be_unique(cls, value: list[int]) -> list[int]:
        if len(value) != len(set(value)):
            raise ValueError("duplicate known fact ids are not allowed")
        return value


class RiskAnalysisResponse(ApiModel):
    risk_score: int = Field(ge=0, le=100)
    model_version: str = Field(min_length=1, max_length=100)
    prompt_version: str = Field(min_length=1, max_length=100)
    findings: list[FindingPayload] = Field(min_length=1)


class HealthResponse(ApiModel):
    status: str
    provider: AnalysisProvider


class ErrorResponse(ApiModel):
    error_code: str
    message: str
    retryable: bool
