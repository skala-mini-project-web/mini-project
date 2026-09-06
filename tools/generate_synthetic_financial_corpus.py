#!/usr/bin/env python3
"""Generate the ARGUS synthetic multi-page financial-document corpus.

The corpus intentionally resembles common document *structures* without copying any
real financial company, product, contract, regulation, or consumer data.
"""
from __future__ import annotations

import hashlib
import json
import shutil
from dataclasses import dataclass
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parents[1]
CORPUS_ROOT = ROOT / "data" / "synthetic-financial-corpus"
DOCUMENTS_ROOT = CORPUS_ROOT / "documents"
FONT_PATH = Path("/System/Library/Fonts/Supplemental/AppleGothic.ttf")
CORPUS_VERSION = "1.0.0"


@dataclass(frozen=True)
class Product:
    code: str
    name: str
    category: str
    term: str
    return_structure: str
    risk_statement: str
    liquidity: str
    fee: str
    target: str
    key_terms: tuple[str, ...]


PRODUCTS = (
    Product(
        "HORIZON-INCOME-NOTE",
        "호라이즌 인컴 노트",
        "지수연동형 모의 투자상품",
        "18개월",
        "월별 기준지수 성과에 따라 분배금이 달라지는 변동 수익 구조",
        "기준지수 하락 또는 조기 종료 시 원금의 전부 또는 일부를 잃을 수 있습니다.",
        "중도 환매는 가능하나 신청 시점과 시장가치 조정에 따라 지급액이 달라집니다.",
        "90일 이내 환매 시 2.4%, 91~180일 1.2%, 이후 0.4%의 정률 비용과 시장가치 조정이 적용됩니다.",
        "변동 수익 구조와 원금손실 가능성을 함께 검토하려는 성인 고객",
        ("기준지수", "분배금", "원금손실", "중도 환매", "시장가치 조정"),
    ),
    Product(
        "RIVER-FLEX-SAVINGS",
        "리버 플렉스 적립 플랜",
        "자유적립형 모의 저축상품",
        "24개월",
        "우대 조건 충족 여부에 따라 기본·우대 적용률이 달라지는 적립 구조",
        "우대 조건이 충족되지 않으면 안내된 최대 적용률이 적용되지 않을 수 있습니다.",
        "중도 해지 시 약정 기간에 따른 적용률 조정과 세전 이자 계산 방식이 달라집니다.",
        "중도 해지 비용은 없으나, 약정 적용률 대신 중도 해지 산식이 적용될 수 있습니다.",
        "월별 적립과 우대 조건을 확인하려는 고객",
        ("기본 적용률", "우대 조건", "적립일", "중도 해지", "세전 이자"),
    ),
    Product(
        "BLUE-LINE-CREDIT",
        "블루라인 상환 플랜",
        "변동금리형 모의 신용대출상품",
        "36개월",
        "기준금리와 개인 약정 조건에 따라 월 상환액이 변동될 수 있는 구조",
        "금리 변동, 상환 지연 및 중도상환 조건에 따라 총 부담액이 증가할 수 있습니다.",
        "중도상환은 가능하나 잔존 기간과 약정에 따라 중도상환 수수료가 발생할 수 있습니다.",
        "중도상환 수수료는 잔존 기간별로 최대 1.1%이며, 연체 시 별도 지연 비용이 발생할 수 있습니다.",
        "상환 일정과 변동 비용을 비교하려는 고객",
        ("기준금리", "월 상환액", "중도상환", "연체 비용", "상환일"),
    ),
    Product(
        "SAFE-HARBOR-COVER",
        "세이프 하버 보장 플랜",
        "정기형 모의 보장상품",
        "12개월 갱신형",
        "보장 항목·면책 조건·갱신 시점에 따라 지급 범위가 달라지는 구조",
        "모든 사고·상태가 보장되는 것은 아니며 면책·감액·대기 조건을 확인해야 합니다.",
        "계약 철회와 해지는 가능하나 환급액·보장 종료 시점이 납입 상태에 따라 달라집니다.",
        "계약 초기 철회와 중도 해지의 환급 기준은 납입 회차와 보장 사용 여부에 따라 달라집니다.",
        "보장 범위와 제외 조건을 확인하려는 고객",
        ("보장 항목", "면책", "감액", "갱신", "환급 기준"),
    ),
    Product(
        "GREEN-STEP-GOAL",
        "그린스텝 목표 적립 플랜",
        "목표연계형 모의 적립상품",
        "30개월",
        "목표 달성 구간과 납입 유지 여부에 따라 추가 적립 혜택이 달라지는 구조",
        "추가 적립 혜택은 조건부이며 조기 해지 또는 납입 누락 시 일부 또는 전부가 적용되지 않을 수 있습니다.",
        "목표 변경과 해지는 가능하나 변경 횟수·시점·혜택 정산 기준을 확인해야 합니다.",
        "목표 변경은 분기 1회 가능하며, 조기 해지 시 추가 적립분 정산 기준이 별도로 적용됩니다.",
        "목표 기반 적립과 조건부 혜택을 비교하려는 고객",
        ("목표 금액", "추가 적립", "납입 누락", "목표 변경", "정산 기준"),
    ),
    Product(
        "NOVA-CASH-RESERVE",
        "노바 캐시 리저브",
        "단기 유동성 모의 운용상품",
        "6개월",
        "보유 기간과 운용 결과에 따라 일별 반영 수익이 달라지는 단기 운용 구조",
        "운용 결과는 확정되지 않으며, 시장 상황에 따라 평가액이 감소할 수 있습니다.",
        "환매 신청은 영업일 기준 처리되며, 접수 시점·휴장일·시장 상황에 따라 지급일이 달라질 수 있습니다.",
        "환매 비용은 없으나 시장가치 반영과 지급일 조정이 발생할 수 있습니다.",
        "단기 자금 사용 계획과 환매 처리 시점을 확인하려는 고객",
        ("일별 반영", "평가액", "환매 신청", "지급일", "시장가치"),
    ),
)

DOC_TYPES = (
    ("product-overview", "상품설명서", 4),
    ("key-information", "핵심정보 확인서", 3),
    ("risk-disclosure", "위험·제한 고지서", 3),
    ("fee-termination", "비용·중도해지 안내서", 3),
    ("precontract-checklist", "가입 전 확인서", 4),
)


class DeterministicCanvas(Canvas):
    def __init__(self, *args, **kwargs):
        kwargs["invariant"] = 1
        super().__init__(*args, **kwargs)


def setup_fonts() -> str:
    if not FONT_PATH.is_file():
        raise RuntimeError(f"Korean PDF font is unavailable: {FONT_PATH}")
    pdfmetrics.registerFont(TTFont("ArgusKorean", str(FONT_PATH)))
    return "ArgusKorean"


def styles(font: str) -> dict[str, ParagraphStyle]:
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle("title", parent=base["Title"], fontName=font, fontSize=18, leading=25, alignment=TA_LEFT, textColor=colors.HexColor("#172033")),
        "heading": ParagraphStyle("heading", parent=base["Heading2"], fontName=font, fontSize=12, leading=18, spaceBefore=4, spaceAfter=6, textColor=colors.HexColor("#183F86")),
        "body": ParagraphStyle("body", parent=base["BodyText"], fontName=font, fontSize=9.4, leading=16, spaceAfter=7),
        "small": ParagraphStyle("small", parent=base["BodyText"], fontName=font, fontSize=7.8, leading=11, textColor=colors.HexColor("#536174")),
        "center": ParagraphStyle("center", parent=base["BodyText"], fontName=font, fontSize=8.5, leading=13, alignment=TA_CENTER),
    }


def p(text: str, style: ParagraphStyle) -> Paragraph:
    return Paragraph(text.replace("\n", "<br/>"), style)


def document_number(product: Product, doc_type: str) -> str:
    return f"SYN-{product.code}-{doc_type.upper()}-2026-01"


def header_footer(canvas, doc) -> None:
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#D8DEE8"))
    canvas.line(16 * mm, 282 * mm, 194 * mm, 282 * mm)
    canvas.setFont("ArgusKorean", 7)
    canvas.setFillColor(colors.HexColor("#536174"))
    canvas.drawString(16 * mm, 286 * mm, "ARGUS | SYNTHETIC FINANCIAL DOCUMENT CORPUS | 실제 금융상품·회사·법령이 아님")
    canvas.line(16 * mm, 15 * mm, 194 * mm, 15 * mm)
    canvas.drawString(16 * mm, 10 * mm, "완전 합성 데모 문서 · 판매·가입·법률·투자 판단에 사용할 수 없음")
    canvas.drawRightString(194 * mm, 10 * mm, f"페이지 {doc.page}")
    canvas.restoreState()


def info_table(product: Product, font: str, page_label: str) -> Table:
    rows = [
        [p("문서 구분", ParagraphStyle("label", fontName=font, fontSize=8)), p(page_label, ParagraphStyle("value", fontName=font, fontSize=8))],
        [p("상품명", ParagraphStyle("label2", fontName=font, fontSize=8)), p(product.name, ParagraphStyle("value2", fontName=font, fontSize=8))],
        [p("상품 유형", ParagraphStyle("label3", fontName=font, fontSize=8)), p(product.category, ParagraphStyle("value3", fontName=font, fontSize=8))],
        [p("문서번호", ParagraphStyle("label4", fontName=font, fontSize=8)), p(document_number(product, page_label.split()[0].lower()), ParagraphStyle("value4", fontName=font, fontSize=8))],
    ]
    table = Table(rows, colWidths=[35 * mm, 143 * mm])
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CFD7E3")),
        ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#F1F4F8")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    return table


def term_table(product: Product, font: str) -> Table:
    rows = [[p("확인 항목", ParagraphStyle("th1", fontName=font, fontSize=8.2)), p("합성 문서 기준 설명", ParagraphStyle("th2", fontName=font, fontSize=8.2))]]
    pairs = [
        ("기간", product.term),
        ("수익·적용 구조", product.return_structure),
        ("유동성", product.liquidity),
        ("비용·정산", product.fee),
        ("핵심 용어", ", ".join(product.key_terms)),
    ]
    for left, right in pairs:
        rows.append([p(left, ParagraphStyle(f"l{left}", fontName=font, fontSize=8)), p(right, ParagraphStyle(f"r{left}", fontName=font, fontSize=8, leading=12))])
    table = Table(rows, colWidths=[42 * mm, 136 * mm], repeatRows=1)
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CFD7E3")),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183F86")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (0, -1), colors.HexColor("#F7F9FC")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def section(story: list, title: str, paragraphs: list[str], s: dict[str, ParagraphStyle]) -> None:
    story.append(p(title, s["heading"]))
    for text in paragraphs:
        story.append(p(text, s["body"]))


def control_record_table(product: Product, stage: str, s: dict[str, ParagraphStyle]) -> Table:
    rows = [
        [p("통제 항목", s["small"]), p("합성 문서 기록 기준", s["small"])],
        [p("검토 단계", s["small"]), p(stage, s["small"])],
        [p("문서 version", s["small"]), p("2026.1 · 문서번호·발행 범위·핵심 조건을 같은 version으로 확인", s["small"])],
        [p("핵심 확인", s["small"]), p(f"{product.risk_statement} {product.liquidity}", s["small"])],
        [p("질문·정정", s["small"]), p("이해가 어려운 문구·수치·예외를 기록하고, 정정 전에는 확정·게시 상태로 전환하지 않음", s["small"])],
    ]
    table = Table(rows, colWidths=[42 * mm, 136 * mm], repeatRows=1)
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CFD7E3")),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183F86")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (0, -1), colors.HexColor("#F7F9FC")),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def overview_pages(product: Product, s: dict[str, ParagraphStyle]) -> list:
    story = [p(product.name + " 상품설명서", s["title"]), Spacer(1, 4 * mm), info_table(product, "ArgusKorean", "product-overview 상품설명서"), Spacer(1, 5 * mm)]
    section(story, "1. 문서 목적", [
        f"이 문서는 {product.name}의 구조와 주요 확인사항을 설명하기 위한 완전 합성 예시입니다. 대상은 {product.target}입니다.",
        "본 문서는 가입 권유, 투자 자문, 대출 승인, 보장 약속 또는 실제 계약 조건을 구성하지 않습니다. 실제 판매 과정에서는 최신 계약서와 독립적인 설명 자료를 확인해야 합니다.",
    ], s)
    section(story, "2. 상품 구조 요약", [product.return_structure, product.risk_statement], s)
    section(story, "3. 문서 관리·확인 기준", [
        "상품명·문서번호·기간·핵심 위험·비용 조건은 서로 다른 화면이나 문서에서 모순되지 않아야 합니다.",
        "이 문서의 테이블과 확인 기록은 실제 계약 체결 기록이 아니라 corpus 검증용 구조화 예시입니다.",
    ], s)
    story.append(control_record_table(product, "상품설명서 최초 검토", s))
    story.append(PageBreak())
    section(story, "4. 주요 조건", ["조건의 의미와 적용 시점은 아래 표를 기준으로 확인합니다. 하나의 강조 문구만으로 수익·보장·비용 조건을 판단해서는 안 됩니다."], s)
    story.append(term_table(product, "ArgusKorean"))
    section(story, "5. 조건별 적용 확인", [
        "기간·적용 구조·유동성·비용은 독립 항목처럼 보이지만 실제 결과에서는 함께 작동할 수 있습니다.",
        "특히 중도 종료 또는 조건 변경 시에는 최초 안내 문구가 아니라 해당 시점의 정산·처리 기준을 다시 확인해야 합니다.",
    ], s)
    story.append(control_record_table(product, "조건표·수수료·유동성 교차 확인", s))
    story.append(PageBreak())
    section(story, "6. 운용·적용 결과의 변동", [
        f"{product.return_structure} 실제 결과는 문서 발행 시점에 확정되지 않습니다. {product.risk_statement}",
        "표시된 예시는 산식·안내 구조를 설명하기 위한 합성 수치입니다. 과거·예시·목표 값은 미래 결과를 보장하지 않습니다.",
        "고객은 중요한 조건이 본인의 목적·기간·유동성 계획과 맞는지 별도로 확인해야 합니다.",
    ], s)
    section(story, "7. 핵심 위험 확인", [product.risk_statement, product.liquidity, product.fee], s)
    story.append(control_record_table(product, "위험·비용 표현 균형 확인", s))
    story.append(PageBreak())
    section(story, "8. 상담·확인 절차", [
        "상담자는 핵심 이익 표현과 손실·비용·해지·기간 조건을 같은 설명 흐름에서 안내해야 합니다.",
        "이해 여부 확인은 단순 checkbox 또는 일괄 동의가 아니라, 고객이 중요한 조건을 확인할 수 있는 시간과 질문 경로를 제공해야 합니다.",
        "이 문서는 ARGUS RAG 검색·출처 추적·문서 추출 시연을 위해 작성된 합성 corpus입니다.",
    ], s)
    section(story, "9. 문서 사용·보관 유의사항", [
        "확정 전 문서와 수정 문서는 같은 파일명만으로 구분하지 않으며, source hash와 revision을 함께 기록합니다.",
        "ARGUS 분석 결과는 이 문서 revision의 근거와 함께 보관되며, 이후 수정 문서의 결과를 과거 결과에 덮어쓰지 않습니다.",
    ], s)
    story.append(control_record_table(product, "상담·질문·정정 이력 확인", s))
    return story


def key_information_pages(product: Product, s: dict[str, ParagraphStyle]) -> list:
    story = [p(product.name + " 핵심정보 확인서", s["title"]), Spacer(1, 4 * mm), info_table(product, "ArgusKorean", "key-information 핵심정보"), Spacer(1, 5 * mm)]
    section(story, "1. 가입·이용 전 확인", [
        f"약정 또는 기준 기간은 {product.term}입니다.",
        f"수익·적용 구조: {product.return_structure}",
        f"핵심 위험: {product.risk_statement}",
    ], s)
    section(story, "2. 고객 확인 질문", [
        "상품의 기간, 비용, 해지·환매 가능 시점, 적용률 또는 지급 조건을 본인의 언어로 설명할 수 있는지 확인합니다.",
        "이 문서는 고객 답변을 수집하지 않으며, 실제 고객 정보·신용 정보·건강 정보·민감 정보를 포함하지 않습니다.",
    ], s)
    story.append(PageBreak())
    section(story, "3. 중요 불이익·제한", [product.risk_statement, product.liquidity, product.fee], s)
    section(story, "4. 정보 표시 기준", [
        "이익·우대·안정성 표현은 손실 가능성·비용·제한 조건보다 늦게 또는 눈에 띄지 않게 표시되어서는 안 됩니다.",
        "중요 정보는 각주만으로 대체하지 않으며, 고객이 확인할 수 있는 크기와 순서로 제공해야 합니다.",
    ], s)
    story.append(PageBreak())
    section(story, "5. 확인 기록", [
        "문서 버전, 확인일, 설명한 중요 항목, 질문 또는 추가 설명 요청 여부를 기록합니다.",
        "본 합성 확인서는 실제 금융소비자보호 절차·법령·내부통제 기준을 대체하지 않습니다.",
    ], s)
    return story


def risk_disclosure_pages(product: Product, s: dict[str, ParagraphStyle]) -> list:
    story = [p(product.name + " 위험·제한 고지서", s["title"]), Spacer(1, 4 * mm), info_table(product, "ArgusKorean", "risk-disclosure 위험고지"), Spacer(1, 5 * mm)]
    section(story, "1. 핵심 위험", [
        product.risk_statement,
        "시장·금리·지수·납입·계약 조건은 문서의 상품 유형과 구조에 따라 서로 다른 결과를 만들 수 있습니다. 하나의 위험 설명이 모든 경우를 포괄하지 않습니다.",
    ], s)
    section(story, "2. 손실·비용·기간의 관계", [
        "손실 가능성, 비용, 기간, 지급·상환·환매 시점은 함께 판단해야 합니다. 특정 조건을 충족하지 못하면 안내된 혜택·적용률·보장 범위가 달라질 수 있습니다.",
    ], s)
    story.append(PageBreak())
    section(story, "3. 강조 표현 정정", [
        "안정, 보장, 확정, 우대, 즉시, 무료와 같은 표현은 적용 범위와 예외·제한을 같은 전달 단위에서 설명해야 합니다.",
        f"이 상품의 경우 반드시 함께 확인할 내용: {product.risk_statement}",
        f"또한 확인할 내용: {product.fee}",
    ], s)
    section(story, "4. 적합성 판단의 한계", [
        "이 합성 문서는 고객별 재무상태·목표·손실 감내도·건강·가족 상황을 평가하지 않습니다. ARGUS Persona lens는 문서 설명을 점검하는 synthetic scenario일 뿐 고객 profile이 아닙니다.",
    ], s)
    story.append(PageBreak())
    section(story, "5. 문의·정정", [
        "문서 내용이 불명확하거나 핵심 조건이 빠졌다고 판단되면 판매·게시 전에 정정과 재검토가 필요합니다.",
        "본 문서의 모든 기관명·상품명·수치·연락처·문서번호는 합성 데이터이며 실제 계약·민원·분쟁에 사용할 수 없습니다.",
    ], s)
    return story


def fee_pages(product: Product, s: dict[str, ParagraphStyle]) -> list:
    story = [p(product.name + " 비용·중도해지 안내서", s["title"]), Spacer(1, 4 * mm), info_table(product, "ArgusKorean", "fee-termination 비용안내"), Spacer(1, 5 * mm)]
    section(story, "1. 비용과 정산 기준", [product.fee, "비용·정산 기준은 상품 구조, 보유·납입 기간, 처리일, 적용 조건에 따라 달라질 수 있습니다."], s)
    rows = [[p("확인 시점", s["small"]), p("합성 예시", s["small"]), p("주의사항", s["small"])]]
    rows += [
        [p("가입·신청 전", s["small"]), p("기간·적용률·면책·상환 조건", s["small"]), p("핵심 용어와 예외를 함께 확인", s["small"])],
        [p("중도 해지·환매", s["small"]), p(product.fee, s["small"]), p("표시된 혜택·적용률이 달라질 수 있음", s["small"])],
        [p("처리 완료", s["small"]), p("접수·영업일·시장 조건에 따른 지급/정산", s["small"]), p("실제 처리일과 금액을 별도 확인", s["small"])],
    ]
    table = Table(rows, colWidths=[35 * mm, 88 * mm, 55 * mm], repeatRows=1)
    table.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CFD7E3")), ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#183F86")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white), ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 5), ("RIGHTPADDING", (0, 0), (-1, -1), 5), ("TOPPADDING", (0, 0), (-1, -1), 5), ("BOTTOMPADDING", (0, 0), (-1, -1), 5)]))
    story.append(table)
    story.append(PageBreak())
    section(story, "2. 중도해지·환매 유의사항", [product.liquidity, product.fee, "비용이 없다는 표현만으로 중도 종료의 모든 불이익이 없다고 해석해서는 안 됩니다."], s)
    section(story, "3. 합성 산식 예시", [
        "예시 기준금액 10,000,000원에서 특정 기간의 정률 비용 또는 시장가치 조정이 반영될 수 있습니다. 이 예시는 산식 이해 목적이며 실제 지급·상환 금액을 예측하지 않습니다.",
    ], s)
    story.append(PageBreak())
    section(story, "4. 확인 기록", [
        "상담·게시 화면에서는 비용, 중도 종료 조건, 처리 시점, 시장 또는 조건 변화 가능성을 같은 흐름에서 표시해야 합니다.",
        "ARGUS corpus에서는 이 페이지의 비용·중도해지 문장을 RAG retrieval과 위험 표현 검증의 expected anchor로 사용합니다.",
    ], s)
    return story


def checklist_pages(product: Product, s: dict[str, ParagraphStyle]) -> list:
    story = [p(product.name + " 가입 전 확인서", s["title"]), Spacer(1, 4 * mm), info_table(product, "ArgusKorean", "precontract-checklist 사전확인"), Spacer(1, 5 * mm)]
    section(story, "1. 확인 목적", [
        "본 확인서는 고객이 중요한 문서를 읽고 질문할 기회를 가졌는지 점검하기 위한 합성 양식입니다. 체크 자체가 이해·적합성·설명 완료를 의미하지 않습니다.",
    ], s)
    checks = [
        "기간·만기·갱신 또는 상환 일정을 확인했습니다.",
        "수익·적용률·지급 구조가 확정되지 않을 수 있음을 확인했습니다.",
        "손실, 비용, 중도해지·환매, 처리 시점의 제한을 확인했습니다.",
        "이해하기 어려운 용어·계산·예외에 대해 질문할 경로를 확인했습니다.",
    ]
    for item in checks:
        story.append(p("□ " + item, s["body"]))
    story.append(PageBreak())
    section(story, "2. 중요 문서 교차 확인", [
        "상품설명서, 핵심정보 확인서, 위험·제한 고지서, 비용·중도해지 안내서를 같은 version으로 확인합니다.",
        f"이 문서에서 특히 확인할 구조: {product.return_structure}",
        f"이 문서에서 특히 확인할 위험: {product.risk_statement}",
    ], s)
    story.append(PageBreak())
    section(story, "3. 설명 접근성", [
        "전자 문서·긴 문장·전문용어·표·각주가 이해를 어렵게 만들 수 있습니다. 필요한 경우 용어 설명, 계산 예시, 충분한 읽기 시간, 별도 질문을 제공해야 합니다.",
        "연령, 직업, 성별 또는 개인 상황만으로 이해 능력을 단정하지 않습니다. ARGUS는 문서가 상황별 설명 요구를 충족하는지만 점검합니다.",
    ], s)
    story.append(PageBreak())
    section(story, "4. 합성 데이터 고지", [
        "이 양식의 체크 항목, 상품명, 수치, 문서번호, 조건은 전부 합성입니다.",
        "실제 금융상품 가입, 투자·대출·보험 판단, 법률·규제 적합성 판단 또는 고객 동의 기록에 사용할 수 없습니다.",
    ], s)
    return story


def build_story(product: Product, doc_type: str, s: dict[str, ParagraphStyle]) -> list:
    return {
        "product-overview": overview_pages,
        "key-information": key_information_pages,
        "risk-disclosure": risk_disclosure_pages,
        "fee-termination": fee_pages,
        "precontract-checklist": checklist_pages,
    }[doc_type](product, s)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expected_terms(product: Product, doc_type: str) -> list[str]:
    by_type = {
        "product-overview": ["문서 목적", "상품 구조 요약", *product.key_terms[:2]],
        "key-information": ["가입·이용 전 확인", "중요 불이익·제한", "정보 표시 기준"],
        "risk-disclosure": ["핵심 위험", "강조 표현 정정", "적합성 판단의 한계"],
        "fee-termination": ["비용과 정산 기준", "중도해지·환매 유의사항", "합성 산식 예시"],
        "precontract-checklist": ["확인 목적", "중요 문서 교차 확인", "설명 접근성"],
    }
    return [product.name, *by_type[doc_type]]


def generate() -> None:
    font = setup_fonts()
    s = styles(font)
    if DOCUMENTS_ROOT.exists():
        shutil.rmtree(DOCUMENTS_ROOT)
    DOCUMENTS_ROOT.mkdir(parents=True)

    manifest_documents = []
    extraction_cases = []
    for product in PRODUCTS:
        product_dir = DOCUMENTS_ROOT / product.code.lower()
        product_dir.mkdir()
        for doc_type, label, expected_pages in DOC_TYPES:
            path = product_dir / f"{doc_type}.pdf"
            template = SimpleDocTemplate(
                str(path), pagesize=A4, leftMargin=16 * mm, rightMargin=16 * mm,
                topMargin=24 * mm, bottomMargin=22 * mm, title=f"{product.name} {label}",
                author="ARGUS Synthetic Financial Document Corpus",
            )
            template.build(
                build_story(product, doc_type, s),
                onFirstPage=header_footer,
                onLaterPages=header_footer,
                canvasmaker=DeterministicCanvas,
            )
            logical_id = f"synthetic.financial.{product.code.lower()}.{doc_type}"
            manifest_documents.append({
                "logicalId": logical_id,
                "productCode": product.code,
                "documentType": doc_type,
                "title": f"{product.name} {label}",
                "path": str(path.relative_to(CORPUS_ROOT)),
                "pages": expected_pages,
                "sha256": sha256(path),
                "synthetic": True,
                "demoOnly": True,
                "expectedTerms": expected_terms(product, doc_type),
            })
            extraction_cases.append({
                "logicalId": logical_id,
                "requiredTerms": [*expected_terms(product, doc_type), "완전 합성 데모 문서"],
                "minimumPages": expected_pages,
                "expectedExtractionMethod": "TEXT_LAYER",
            })

    total_pages = sum(item["pages"] for item in manifest_documents)
    manifest = {
        "schemaVersion": "1.0",
        "corpusId": "argus.synthetic-financial-documents.v1",
        "corpusVersion": CORPUS_VERSION,
        "synthetic": True,
        "demoOnly": True,
        "authorityDisclaimer": "모든 문서·회사·상품·수치·규칙은 합성입니다. 실제 금융사·상품·계약·법령·소비자·규제기관을 나타내지 않으며 금융·법률 자문으로 사용할 수 없습니다.",
        "documentCount": len(manifest_documents),
        "pageCount": total_pages,
        "documents": manifest_documents,
    }
    expected = {
        "schemaVersion": "1.0",
        "corpusId": manifest["corpusId"],
        "synthetic": True,
        "demoOnly": True,
        "extractionCases": extraction_cases,
        "ragScenarios": [
            {
                "scenarioId": "unqualified-guarantee-with-risk-disclosure",
                "queryTerms": ["보장", "원금손실", "중도해지", "비용"],
                "requiredDocumentTypes": ["risk-disclosure", "fee-termination", "precontract-checklist"],
                "mustRetainSyntheticDisclaimer": True,
            },
            {
                "scenarioId": "liquidity-and-repayment-conditions",
                "queryTerms": ["환매", "상환", "처리 시점", "시장가치"],
                "requiredDocumentTypes": ["key-information", "fee-termination"],
                "mustNotClaimGuaranteedOutcome": True,
            },
        ],
    }
    (CORPUS_ROOT / "manifest.v1.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (CORPUS_ROOT / "expected-extraction.v1.json").write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {manifest['documentCount']} PDFs across {manifest['pageCount']} pages.")


if __name__ == "__main__":
    generate()
