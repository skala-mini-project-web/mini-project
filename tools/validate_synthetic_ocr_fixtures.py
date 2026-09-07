#!/usr/bin/env python3
"""Validate integrity, structure, expectations, and provenance of OCR QA fixtures."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURES = ROOT / "data" / "synthetic-ocr-fixtures"
GENERATOR = ROOT / "tools" / "generate_synthetic_ocr_fixtures.py"
GENERATOR_ID = "argus-synthetic-korean-ocr-v1"
EXPECTED = {
    "born-digital-ko-en.pdf": ("born-digital-korean-english", 1, ["PDFBOX_TEXT"]),
    "image-only-korean-scan.pdf": ("image-only-korean-scan", 1, ["OCR_KOR_ENG"]),
    "mixed-three-page.pdf": ("mixed-three-page", 3, ["PDFBOX_TEXT", "OCR_KOR_ENG", "PDFBOX_TEXT"]),
    "low-confidence-korean-scan.pdf": ("low-confidence-scan", 1, ["OCR_KOR_ENG"]),
    "blank-image-page.pdf": ("blank-image-page", 1, []),
    "corrupt.pdf": ("corrupt", None, []),
}
HASH = re.compile(r"^[0-9a-f]{64}$")
PAGE = re.compile(rb"/Type\s*/Page(?!s)\b")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_manifest(manifest: dict) -> list[dict]:
    require(manifest.get("schema_version") == 1, "manifest schema_version must be 1")
    require(manifest.get("generator") == GENERATOR_ID, "untrusted fixture generator")
    require(manifest.get("generator_sha256") == digest(GENERATOR.read_bytes()), "generator source hash does not match manifest provenance")
    require(manifest.get("deterministic_seed") == 870031, "deterministic seed changed")
    provenance = manifest.get("provenance") or {}
    require(provenance.get("classification") == "SYNTHETIC_ONLY", "fixtures are not classified SYNTHETIC_ONLY")
    require(provenance.get("contains_real_customer_data") is False, "real customer data provenance is not explicitly false")
    require(provenance.get("contains_real_company_or_product_data") is False, "real company/product provenance is not explicitly false")
    require("fictional" in str(provenance.get("notice", "")).lower(), "synthetic provenance notice is missing")
    font = manifest.get("font") or {}
    require(isinstance(font.get("file_name"), str) and font["file_name"], "font provenance file_name is missing")
    require(bool(HASH.fullmatch(str(font.get("sha256", "")))), "font provenance hash is invalid")
    fixtures = manifest.get("fixtures")
    require(isinstance(fixtures, list), "fixtures must be a list")
    require({entry.get("file") for entry in fixtures} == set(EXPECTED), "fixture inventory differs from the approved synthetic set")
    return fixtures


def validate_entry(root: Path, entry: dict) -> None:
    name = entry.get("file")
    require(entry.get("synthetic") is True, f"{name}: synthetic flag must be true")
    require(isinstance(name, str) and Path(name).name == name, f"{name}: unsafe fixture path")
    path = root / name
    require(path.is_file() and not path.is_symlink(), f"{name}: missing, non-file, or symlink")
    payload = path.read_bytes()
    require(entry.get("bytes") == len(payload) and len(payload) > 0, f"{name}: byte count mismatch")
    require(bool(HASH.fullmatch(str(entry.get("sha256", "")))), f"{name}: invalid SHA-256")
    require(entry["sha256"] == digest(payload), f"{name}: SHA-256 mismatch")
    require(payload.startswith(b"%PDF-"), f"{name}: PDF signature missing")

    scenario, page_count, routes = EXPECTED[name]
    expectations = entry.get("expectations") or {}
    require(expectations.get("scenario") == scenario, f"{name}: scenario changed")
    require(expectations.get("page_count") == page_count, f"{name}: expected page count changed")
    require(expectations.get("expected_page_routes") == routes, f"{name}: page-route contract changed")
    require(isinstance(expectations.get("expected_outcome"), str), f"{name}: expected outcome missing")

    if name == "corrupt.pdf":
        require(b"ARGUS SYNTHETIC CORRUPT FIXTURE 87" in payload, "corrupt fixture marker missing")
        require(b"%%EOF" not in payload, "corrupt fixture unexpectedly has an EOF trailer")
        return

    require(payload.rstrip().endswith(b"%%EOF"), f"{name}: complete PDF trailer missing")
    require(len(PAGE.findall(payload)) == page_count, f"{name}: physical PDF page count mismatch")
    has_image = b"/Subtype /Image" in payload
    has_text_operator = b" Tj" in payload or b" TJ" in payload
    if name in {"image-only-korean-scan.pdf", "low-confidence-korean-scan.pdf", "blank-image-page.pdf"}:
        require(has_image and not has_text_operator, f"{name}: scan must contain an image and no PDF text operators")
    elif name == "mixed-three-page.pdf":
        require(has_image and has_text_operator, "mixed fixture must contain image and born-digital text content")
    elif name == "born-digital-ko-en.pdf":
        require(has_text_operator and not has_image, "born-digital fixture must contain selectable text and no page image")
    if "OCR_KOR_ENG" in routes:
        require(expectations.get("required_ocr_language") == "kor+eng", f"{name}: OCR language expectation missing")
    if name == "low-confidence-korean-scan.pdf":
        require(expectations.get("expected_confidence_band") == "LOW", "low-confidence expectation changed")
        require(expectations.get("expected_confidence_below") == 70, "low-confidence threshold changed")
        require(expectations.get("requires_reviewer_confirmation") is True, "low-confidence reviewer gate changed")
        require(expectations.get("expected_outcome") == "READY_UNCONFIRMED", "low-confidence fixture must remain confirmable")
    if name == "blank-image-page.pdf":
        require(expectations.get("expected_outcome") == "FAILED_NO_TEXT", "blank image page must remain a no-text failure")
    if name in {"born-digital-ko-en.pdf", "image-only-korean-scan.pdf", "mixed-three-page.pdf"}:
        required_text = expectations.get("required_text")
        require(isinstance(required_text, list) and all(isinstance(item, str) and item for item in required_text), f"{name}: required OCR assertions missing")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    args = parser.parse_args()
    root = args.fixtures.resolve()
    manifest_path = root / "manifest.json"
    require(manifest_path.is_file() and not manifest_path.is_symlink(), "manifest.json is missing or is a symlink")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    fixtures = validate_manifest(manifest)
    actual_files = {path.name for path in root.iterdir() if path.is_file()}
    require(actual_files == set(EXPECTED) | {"manifest.json"}, "fixture directory contains undocumented files")
    for entry in fixtures:
        validate_entry(root, entry)
    print(f"Validated {len(fixtures)} deterministic SYNTHETIC_ONLY OCR fixtures at {root}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"Synthetic OCR fixture validation failed: {error}") from error
