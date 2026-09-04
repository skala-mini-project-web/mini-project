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


class EvidenceDocument(ApiModel):
    id: int = Field(gt=0)
    source_type: EvidenceSourceType
    title: str = Field(min_length=1, max_length=255)
    content: str = Field(min_length=1)


class RiskAnalysisRequest(ApiModel):
    analysis_id: int = Field(gt=0)
    scenario_code: str = Field(min_length=1, max_length=80)
    confirmed_text: str = Field(min_length=1)
    persona_codes: list[PersonaCode] = Field(min_length=1, max_length=3)
    red_team_pack_code: RedTeamPackCode
    rule_codes: list[RedTeamRuleCode] = Field(min_length=1)
    evidence_documents: list[EvidenceDocument] = Field(min_length=1, max_length=3)

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

    @field_validator("evidence_documents")
    @classmethod
    def evidence_documents_must_be_unique(
        cls, value: list[EvidenceDocument]
    ) -> list[EvidenceDocument]:
        identifiers = [document.id for document in value]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("duplicate evidence document ids are not allowed")
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
        return self


class EvidenceReference(ApiModel):
    evidence_document_id: int = Field(gt=0)
    excerpt: str = Field(min_length=1)


class FindingPayload(ApiModel):
    statement: str = Field(min_length=1, max_length=1000)
    severity: Severity
    affected_persona_codes: list[PersonaCode] = Field(min_length=1)
    evidence_references: list[EvidenceReference] = Field(default_factory=list)
    recommendation: str | None = Field(default=None, max_length=1000)

    @field_validator("affected_persona_codes")
    @classmethod
    def affected_personas_must_be_unique(
        cls, value: list[PersonaCode]
    ) -> list[PersonaCode]:
        if len(value) != len(set(value)):
            raise ValueError("duplicate affected persona codes are not allowed")
        return value

    @model_validator(mode="after")
    def high_severity_requires_evidence(self) -> "FindingPayload":
        if self.severity == Severity.HIGH and not self.evidence_references:
            raise ValueError("HIGH finding requires at least one evidence reference")
        return self


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
