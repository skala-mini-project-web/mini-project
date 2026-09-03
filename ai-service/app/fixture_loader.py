import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from app.errors import AiServiceError, FixtureInvalidError, ScenarioNotFoundError


class FixtureLoader:
    def __init__(self, fixture_directory: Path | None = None) -> None:
        self.fixture_directory = fixture_directory or (
            Path(__file__).resolve().parent.parent / "fixtures"
        )

    def load(self, scenario_code: str, attempt_number: int = 1) -> dict[str, Any]:
        error_config = self._load_error_scenarios().get(scenario_code)
        if error_config is not None:
            failures_before_success = error_config.get("failuresBeforeSuccess")
            if failures_before_success is not None:
                if (
                    isinstance(failures_before_success, bool)
                    or not isinstance(failures_before_success, int)
                    or failures_before_success < 0
                ):
                    raise FixtureInvalidError(
                        f"Invalid failuresBeforeSuccess for scenario '{scenario_code}'"
                    )
                success_fixture = error_config.get("successFixture")
                if not isinstance(success_fixture, str) or not success_fixture:
                    raise FixtureInvalidError(
                        f"Missing successFixture for scenario '{scenario_code}'"
                    )
                if attempt_number > failures_before_success:
                    return self._read_json(success_fixture)

            raise AiServiceError(
                error_code=error_config["errorCode"],
                message=error_config["message"],
                retryable=error_config["retryable"],
                status_code=error_config["httpStatus"],
            )

        fixture_file = self._scenario_files().get(scenario_code)
        if fixture_file is None:
            raise ScenarioNotFoundError(scenario_code)

        return self._read_json(fixture_file)

    def tracks_attempts(self, scenario_code: str) -> bool:
        error_config = self._load_error_scenarios().get(scenario_code)
        return error_config is not None and "failuresBeforeSuccess" in error_config

    @staticmethod
    def _scenario_files() -> dict[str, str]:
        return {
            "GUARANTEE_MISUNDERSTANDING_HIGH": "guarantee_high.json",
            "EARLY_TERMINATION_COST_MEDIUM": "termination_cost.json",
            "ACCESSIBILITY_LOW": "accessibility.json",
        }

    def _load_error_scenarios(self) -> dict[str, dict[str, Any]]:
        return self._read_json("mock_errors.json")

    @lru_cache(maxsize=16)
    def _read_json(self, file_name: str) -> dict[str, Any]:
        fixture_path = self.fixture_directory / file_name
        try:
            with fixture_path.open(encoding="utf-8") as fixture_file:
                payload = json.load(fixture_file)
        except (OSError, json.JSONDecodeError) as error:
            raise FixtureInvalidError(
                f"Unable to load fixture '{file_name}': {error}"
            ) from error

        if not isinstance(payload, dict):
            raise FixtureInvalidError(
                f"Fixture '{file_name}' must contain a JSON object"
            )
        return payload
