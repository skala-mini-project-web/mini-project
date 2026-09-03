from pydantic import ValidationError

from app.errors import FixtureInvalidError
from app.fixture_loader import FixtureLoader
from app.schemas import (
    EvidenceReference,
    FindingPayload,
    RiskAnalysisRequest,
    RiskAnalysisResponse,
)


class RiskAnalysisService:
    def __init__(self, fixture_loader: FixtureLoader | None = None) -> None:
        self.fixture_loader = fixture_loader or FixtureLoader()

    def analyze(self, request: RiskAnalysisRequest) -> RiskAnalysisResponse:
        payload = self.fixture_loader.load(request.scenario_code)
        try:
            response = RiskAnalysisResponse.model_validate(payload)
        except ValidationError as error:
            raise FixtureInvalidError(
                f"Fixture response does not match the contract: {error}"
            ) from error

        return self._adapt_to_request_selections(request, response)

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
