from pydantic import ValidationError

from app.errors import FixtureInvalidError
from app.fixture_loader import FixtureLoader
from app.schemas import RiskAnalysisRequest, RiskAnalysisResponse


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

        self._validate_references(request, response)
        return response

    @staticmethod
    def _validate_references(
        request: RiskAnalysisRequest,
        response: RiskAnalysisResponse,
    ) -> None:
        selected_personas = set(request.persona_codes)
        selected_evidence_ids = {
            document.id for document in request.evidence_documents
        }

        for finding in response.findings:
            unknown_personas = (
                set(finding.affected_persona_codes) - selected_personas
            )
            if unknown_personas:
                values = ", ".join(sorted(unknown_personas))
                raise FixtureInvalidError(
                    f"Finding contains unselected persona codes: {values}"
                )

            unknown_evidence_ids = {
                reference.evidence_document_id
                for reference in finding.evidence_references
            } - selected_evidence_ids
            if unknown_evidence_ids:
                values = ", ".join(map(str, sorted(unknown_evidence_ids)))
                raise FixtureInvalidError(
                    f"Finding contains unselected evidence ids: {values}"
                )
