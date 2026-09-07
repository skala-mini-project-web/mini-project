#!/usr/bin/env python3
"""Generate deterministic, synthetic-only Korean OCR QA fixtures."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "data" / "synthetic-ocr-fixtures"
GENERATOR_ID = "argus-synthetic-korean-ocr-v1"
SEED = 870031
FONT_CANDIDATES = (
    ROOT / "tools" / "fonts" / "NotoSansKR-Regular.ttf",
    Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    Path("/usr/share/fonts/opentype/noto/NotoSansCJKkr-Regular.otf"),
    Path("/System/Library/Fonts/AppleSDGothicNeo.ttc"),
    Path("/System/Library/Fonts/Supplemental/AppleGothic.ttf"),
    Path("C:/Windows/Fonts/malgun.ttf"),
)

DIGITAL_LINES = (
    "합성 전용 상품설명서 / SYNTHETIC PRODUCT DISCLOSURE",
    "상품명: 가온 테스트 적립 플랜 / GAON TEST SAVINGS PLAN",
    "이 문서는 실제 회사, 상품, 고객 또는 계약을 나타내지 않습니다.",
    "기본 적용률은 연 2.10%이며 조건 충족 시 연 0.40%가 추가됩니다.",
    "중도 해지 시 약정 적용률 대신 별도 산식이 적용될 수 있습니다.",
    "원금 손실 가능성과 비용 조건을 확인한 뒤 의사결정하십시오.",
    "Fixture marker: ARGUS-OCR-SYNTHETIC-87",
)
SCAN_LINES = (
    "합성 스캔 문서",
    "가온 테스트 적립 플랜",
    "실제 금융회사나 고객 정보가 아닙니다.",
    "기본 적용률 연 2.10 퍼센트",
    "우대 조건 충족 시 연 0.40 퍼센트 추가",
    "중도 해지 산식과 원금 손실 가능성을 확인하십시오.",
    "ARGUS OCR SYNTHETIC 87",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_font() -> Path:
    for path in FONT_CANDIDATES:
        if path.is_file():
            return path
    raise SystemExit(
        "A Korean font is required. Install fonts-noto-cjk or place "
        "NotoSansKR-Regular.ttf at tools/fonts/."
    )


def metadata(canvas: Canvas, title: str) -> None:
    canvas.setTitle(title)
    canvas.setAuthor("ARGUS synthetic fixture generator")
    canvas.setSubject("Synthetic-only OCR QA; no real person, company, or product data")
    canvas.setCreator(GENERATOR_ID)
    canvas.setKeywords("synthetic,ocr,korean,qa,issue-87")


def draw_digital_page(canvas: Canvas, font_name: str, heading: str, lines: tuple[str, ...]) -> None:
    width, height = A4
    canvas.setFont(font_name, 18)
    canvas.drawString(54, height - 70, heading)
    canvas.setFont(font_name, 11)
    y = height - 112
    for line in lines:
        canvas.drawString(54, y, line)
        y -= 28
    canvas.setFont("Helvetica", 8)
    canvas.drawString(54, 34, "SYNTHETIC ONLY | deterministic seed 870031 | issue 87")


def scan_png(font_path: Path, *, degraded: bool = False) -> bytes:
    image = Image.new("L", (1240, 1754), 255 if not degraded else 224)
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(str(font_path), 46)
    small = ImageFont.truetype(str(font_path), 34)
    foreground = 18 if not degraded else 177
    y = 145
    for index, line in enumerate(SCAN_LINES):
        draw.text((95, y), line, font=font if index < 2 else small, fill=foreground)
        y += 112 if index < 2 else 94
    draw.rectangle((70, 105, 1170, 1000), outline=50 if not degraded else 185, width=3)
    if degraded:
        rng = random.Random(SEED)
        pixels = image.load()
        for _ in range(115_000):
            x = rng.randrange(image.width)
            y = rng.randrange(image.height)
            pixels[x, y] = rng.randrange(145, 256)
        for y in range(180, 1500, 173):
            draw.line((45, y, 1195, y + 11), fill=190, width=7)
    output = io.BytesIO()
    image.save(output, format="PNG", compress_level=9, optimize=False)
    return output.getvalue()


def image_pdf(png: bytes, title: str) -> bytes:
    output = io.BytesIO()
    canvas = Canvas(output, pagesize=A4, invariant=1, pageCompression=0)
    metadata(canvas, title)
    canvas.drawImage(ImageReader(io.BytesIO(png)), 0, 0, width=A4[0], height=A4[1], preserveAspectRatio=False)
    canvas.showPage()
    canvas.save()
    return output.getvalue()


def digital_pdf(font_name: str) -> bytes:
    output = io.BytesIO()
    canvas = Canvas(output, pagesize=A4, invariant=1, pageCompression=0)
    metadata(canvas, "Synthetic born-digital Korean English fixture")
    draw_digital_page(canvas, font_name, "합성 전용 디지털 PDF", DIGITAL_LINES)
    canvas.showPage()
    canvas.save()
    return output.getvalue()


def mixed_pdf(font_name: str, png: bytes) -> bytes:
    output = io.BytesIO()
    canvas = Canvas(output, pagesize=A4, invariant=1, pageCompression=0)
    metadata(canvas, "Synthetic mixed-route Korean OCR fixture")
    draw_digital_page(canvas, font_name, "1쪽: 디지털 약관", DIGITAL_LINES[:5])
    canvas.showPage()
    canvas.drawImage(ImageReader(io.BytesIO(png)), 0, 0, width=A4[0], height=A4[1], preserveAspectRatio=False)
    canvas.showPage()
    draw_digital_page(canvas, font_name, "3쪽: 디지털 확인사항", DIGITAL_LINES[2:])
    canvas.showPage()
    canvas.save()
    return output.getvalue()


def blank_pdf() -> bytes:
    output = io.BytesIO()
    canvas = Canvas(output, pagesize=A4, invariant=1, pageCompression=0)
    metadata(canvas, "Synthetic blank PDF fixture")
    canvas.showPage()
    canvas.save()
    return output.getvalue()


def fixture_specs(font_name: str, font_path: Path) -> list[tuple[str, bytes, dict]]:
    clean_scan = scan_png(font_path)
    degraded_scan = scan_png(font_path, degraded=True)
    return [
        ("born-digital-ko-en.pdf", digital_pdf(font_name), {
            "scenario": "born-digital-korean-english", "page_count": 1,
            "expected_outcome": "READY_UNCONFIRMED", "expected_page_routes": ["PDFBOX_TEXT"],
            "required_text": ["가온 테스트 적립 플랜", "ARGUS-OCR-SYNTHETIC-87"],
        }),
        ("image-only-korean-scan.pdf", image_pdf(clean_scan, "Synthetic image-only Korean scan"), {
            "scenario": "image-only-korean-scan", "page_count": 1,
            "expected_outcome": "READY_UNCONFIRMED", "expected_page_routes": ["OCR_KOR_ENG"],
            "required_ocr_language": "kor+eng", "required_text": ["가온", "적립"],
        }),
        ("mixed-three-page.pdf", mixed_pdf(font_name, clean_scan), {
            "scenario": "mixed-three-page", "page_count": 3,
            "expected_outcome": "READY_UNCONFIRMED",
            "expected_page_routes": ["PDFBOX_TEXT", "OCR_KOR_ENG", "PDFBOX_TEXT"],
            "required_ocr_language": "kor+eng", "required_text": ["가온", "ARGUS-OCR-SYNTHETIC-87"],
        }),
        ("low-confidence-korean-scan.pdf", image_pdf(degraded_scan, "Synthetic degraded Korean scan"), {
            "scenario": "low-confidence-scan", "page_count": 1,
            "expected_outcome": "READY_LOW_CONFIDENCE_OR_FAILED_LOW_SIGNAL",
            "expected_page_routes": ["OCR_KOR_ENG"], "expected_confidence_band": "LOW",
        }),
        ("blank.pdf", blank_pdf(), {
            "scenario": "blank", "page_count": 1, "expected_outcome": "FAILED_NO_TEXT",
            "expected_page_routes": [],
        }),
        ("corrupt.pdf", b"%PDF-1.7\n% ARGUS SYNTHETIC CORRUPT FIXTURE 87\n1 0 obj\n", {
            "scenario": "corrupt", "page_count": None, "expected_outcome": "FAILED_CORRUPT_PDF",
            "expected_page_routes": [],
        }),
    ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    font_path = find_font()
    if font_path.suffix.lower() == ".ttc":
        pdfmetrics.registerFont(UnicodeCIDFont("HYSMyeongJo-Medium"))
        pdf_font_name = "HYSMyeongJo-Medium"
    else:
        pdfmetrics.registerFont(TTFont("SyntheticKorean", str(font_path), subfontIndex=0))
        pdf_font_name = "SyntheticKorean"
    entries = []
    for name, payload, expectations in fixture_specs(pdf_font_name, font_path):
        path = output / name
        path.write_bytes(payload)
        entries.append({
            "file": name, "bytes": len(payload), "sha256": sha256(payload),
            "synthetic": True, "expectations": expectations,
        })

    source = Path(__file__).read_bytes()
    manifest = {
        "schema_version": 1,
        "generator": GENERATOR_ID,
        "generator_sha256": sha256(source),
        "deterministic_seed": SEED,
        "provenance": {
            "classification": "SYNTHETIC_ONLY",
            "contains_real_customer_data": False,
            "contains_real_company_or_product_data": False,
            "notice": "Programmatically generated fictional Korean/English QA content only.",
        },
        "font": {"file_name": font_path.name, "sha256": sha256(font_path.read_bytes())},
        "fixtures": entries,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"Generated {len(entries)} synthetic OCR fixtures at {output}")


if __name__ == "__main__":
    main()
