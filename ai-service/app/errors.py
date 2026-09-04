class AiServiceError(Exception):
    def __init__(
        self,
        *,
        error_code: str,
        message: str,
        retryable: bool,
        status_code: int,
    ) -> None:
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.retryable = retryable
        self.status_code = status_code


class ScenarioNotFoundError(AiServiceError):
    def __init__(self, scenario_code: str) -> None:
        super().__init__(
            error_code="SCENARIO_NOT_FOUND",
            message=f"Unsupported scenarioCode: {scenario_code}",
            retryable=False,
            status_code=404,
        )


class FixtureInvalidError(AiServiceError):
    def __init__(self, message: str) -> None:
        super().__init__(
            error_code="FIXTURE_INVALID",
            message=message,
            retryable=False,
            status_code=500,
        )
