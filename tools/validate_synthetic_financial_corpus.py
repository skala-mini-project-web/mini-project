#!/usr/bin/env python3
"""Validate immutable expectations for the synthetic financial PDF corpus."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
CORPUS_ROOT = ROOT / "data" / "synthetic-financial-corpus"
BANNED_REAL_BRANDS = ("국민은행", "신한은행", "하나은행", "우리은행", "농협은행", "카카오뱅크", "토스뱅크")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    manifest = json.loads((CORPUS_ROOT / "manifest.v1.json").read_text(encoding="utf-8"))
    expected = json.loads((CORPUS_ROOT / "expected-extraction.v1.json").read_text(encoding="utf-8"))
    assert manifest["synthetic"] is True and manifest["demoOnly"] is True
    assert manifest["documentCount"] >= 30
    assert manifest["pageCount"] >= 100
    assert len(manifest["documents"]) == manifest["documentCount"]

    expected_by_id = {case["logicalId"]: case for case in expected["extractionCases"]}
    assert len(expected_by_id) == manifest["documentCount"]
    observed_pages = 0
    observed_hashes: set[str] = set()
    for document in manifest["documents"]:
        path = CORPUS_ROOT / document["path"]
        assert path.is_file(), path
        assert sha256(path) == document["sha256"], document["logicalId"]
        assert document["sha256"] not in observed_hashes, f"duplicate document bytes: {document['logicalId']}"
        observed_hashes.add(document["sha256"])

        reader = PdfReader(str(path))
        text = "\n".join(page.extract_text() or "" for page in reader.pages)
        assert len(reader.pages) == document["pages"], document["logicalId"]
        assert text.strip(), f"no text layer: {document['logicalId']}"
        assert "완전 합성 데모 문서" in text, document["logicalId"]
        for banned in BANNED_REAL_BRANDS:
            assert banned not in text, f"real brand leaked: {banned} in {document['logicalId']}"
        for term in expected_by_id[document["logicalId"]]["requiredTerms"]:
            assert term in text, f"missing {term} in {document['logicalId']}"
        observed_pages += len(reader.pages)

    assert observed_pages == manifest["pageCount"]
    print(json.dumps({
        "outcome": "PASS",
        "documents": manifest["documentCount"],
        "pages": observed_pages,
        "uniqueSha256": len(observed_hashes),
        "textLayer": "PDFBox-compatible born-digital text expected",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
