from threading import Lock

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
        self._attempts: dict[tuple[int, str], int] = {}
        self._attempt_lock = Lock()

    def analyze(self, request: RiskAnalysisRequest) -> RiskAnalysisResponse:
        attempt_number = self._next_attempt(request)
        payload = self.fixture_loader.load(request.scenario_code, attempt_number)
        try:
            response = RiskAnalysisResponse.model_validate(payload)
        except ValidationError as error:
            raise FixtureInvalidError(
                f"Fixture response does not match the contract: {error}"
            ) from error

        return self._adapt_to_request_selections(request, response)

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
