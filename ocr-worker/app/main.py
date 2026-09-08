import csv
import hashlib
import hmac
import io
import math
import os
import re
import subprocess
import tempfile
import threading
from functools import lru_cache
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

MAX_REQUEST_BYTES = int(os.getenv("OCR_MAX_REQUEST_BYTES", "10485760"))
MAX_IMAGE_BYTES = min(int(os.getenv("OCR_MAX_IMAGE_BYTES", "9437184")), MAX_REQUEST_BYTES)
MAX_PIXELS = int(os.getenv("OCR_MAX_PIXELS", "40000000"))
OCR_TIMEOUT_SECONDS = float(os.getenv("OCR_TIMEOUT_SECONDS", "30"))
MAX_OUTPUT_BYTES = int(os.getenv("OCR_MAX_OUTPUT_BYTES", "20971520"))
LANGUAGE = "kor+eng"
ALLOWED_MEDIA_TYPES = {"image/png", "image/jpeg", "image/webp", "image/tiff"}
IMAGE_FORMAT_MEDIA_TYPES = {
    "PNG": "image/png",
    "JPEG": "image/jpeg",
    "WEBP": "image/webp",
    "TIFF": "image/tiff",
}
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
OCR_PERMIT = threading.BoundedSemaphore(value=1)

app = FastAPI(title="Private Korean page OCR worker", docs_url=None, redoc_url=None, openapi_url=None)


class OcrReservation:
    RESERVED = "reserved"
    RUNNING = "running"
    RELEASED = "released"

    def __init__(self) -> None:
        self._state = self.RESERVED
        self._lock = threading.Lock()

    @classmethod
    def reserve(cls):
        if not OCR_PERMIT.acquire(blocking=False):
            return None
        return cls()

    def begin(self) -> bool:
        with self._lock:
            if self._state != self.RESERVED:
                return False
            self._state = self.RUNNING
            return True

    def release_reserved(self) -> None:
        with self._lock:
            if self._state != self.RESERVED:
                return
            self._state = self.RELEASED
        OCR_PERMIT.release()

    def release_running(self) -> None:
        with self._lock:
            if self._state != self.RUNNING:
                return
            self._state = self.RELEASED
        OCR_PERMIT.release()


def failure(status: int, code: str, message: str, retryable: bool = False) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message, "retryable": retryable}},
    )


@app.middleware("http")
async def protect_private_endpoint(request: Request, call_next):
    if request.url.path != "/internal/v1/ocr/pages":
        return failure(404, "NOT_FOUND", "Resource not found")

    configured_token = os.getenv("OCR_WORKER_TOKEN", "")
    supplied = request.headers.get("authorization", "")
    expected = f"Bearer {configured_token}"
    if not configured_token:
        return failure(503, "WORKER_NOT_CONFIGURED", "Worker authentication is not configured", True)
    if not hmac.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        return failure(401, "UNAUTHORIZED", "Authentication is required")

    content_length = request.headers.get("content-length")
    if content_length is None:
        return failure(411, "CONTENT_LENGTH_REQUIRED", "Content-Length is required")
    try:
        length = int(content_length)
    except ValueError:
        return failure(400, "INVALID_CONTENT_LENGTH", "Content-Length is invalid")
    if length <= 0 or length > MAX_REQUEST_BYTES:
        return failure(413, "REQUEST_TOO_LARGE", "Request exceeds the configured byte limit")

    reservation = OcrReservation.reserve()
    if reservation is None:
        return failure(503, "OCR_BUSY", "OCR worker is already processing a page", True)
    request.state.ocr_reservation = reservation
    try:
        return await call_next(request)
    finally:
        reservation.release_reserved()


@app.exception_handler(RequestValidationError)
async def validation_failure(_request: Request, _exception: RequestValidationError):
    return failure(400, "INVALID_REQUEST", "Request metadata or image part is invalid")


@app.exception_handler(Exception)
async def unexpected_failure(_request: Request, _exception: Exception):
    return failure(500, "OCR_INTERNAL_ERROR", "OCR processing failed", True)


class OcrFailure(Exception):
    def __init__(self, status: int, code: str, message: str, retryable: bool = False):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable


@app.exception_handler(OcrFailure)
async def ocr_failure(_request: Request, exception: OcrFailure):
    return failure(exception.status, exception.code, exception.message, exception.retryable)


def require_sha256(value: str, name: str) -> None:
    if not SHA256_PATTERN.fullmatch(value):
        raise OcrFailure(400, "INVALID_METADATA", f"{name} must be a lowercase SHA-256 digest")


def recognized_codepoints(text: str) -> int:
    return sum(1 for character in text if not character.isspace())


def page_confidence(raw_tsv: str) -> float:
    try:
        rows = csv.DictReader(io.StringIO(raw_tsv), delimiter="\t")
        required = {"level", "page_num", "block_num", "par_num", "line_num", "word_num", "left", "top", "width", "height", "conf", "text"}
        if rows.fieldnames is None or not required.issubset(rows.fieldnames):
            raise ValueError("missing TSV columns")
        weighted_sum = 0.0
        recognized = 0
        for row in rows:
            text = row.get("text") or ""
            weight = recognized_codepoints(text)
            if weight == 0:
                continue
            confidence = float(row["conf"])
            if not math.isfinite(confidence):
                raise ValueError("non-finite confidence")
            if confidence < 0:
                continue
            if confidence > 100:
                raise ValueError("confidence above 100")
            weighted_sum += confidence * weight
            recognized += weight
    except (TypeError, ValueError, csv.Error) as exception:
        raise OcrFailure(500, "OCR_OUTPUT_INVALID", "OCR engine produced invalid TSV", True) from exception
    if recognized == 0:
        raise OcrFailure(422, "OCR_NO_TEXT", "No text was recognized on the page")
    return round(weighted_sum / recognized, 4)


@lru_cache(maxsize=1)
def engine_version() -> str:
    try:
        completed = subprocess.run(
            ["tesseract", "--version"],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=2,
            text=True,
        )
        first_line = completed.stdout.splitlines()[0]
        match = re.fullmatch(r"tesseract ([0-9A-Za-z.+~-]+)", first_line)
        if match is None:
            raise ValueError("unexpected version output")
        return match.group(1)
    except (OSError, subprocess.SubprocessError, IndexError, ValueError) as exception:
        raise OcrFailure(503, "OCR_ENGINE_UNAVAILABLE", "OCR engine is unavailable", True) from exception


def prepare_image(image_bytes: bytes, media_type: str, target: Path) -> tuple[int, int]:
    if media_type not in ALLOWED_MEDIA_TYPES or b"%PDF-" in image_bytes[:1024]:
        raise OcrFailure(415, "UNSUPPORTED_IMAGE", "Exactly one PNG, JPEG, WebP, or TIFF page image is required")
    try:
        with Image.open(io.BytesIO(image_bytes)) as image:
            if IMAGE_FORMAT_MEDIA_TYPES.get(image.format) != media_type:
                raise OcrFailure(400, "IMAGE_MEDIA_TYPE_MISMATCH", "Image data does not match its media type")
            if getattr(image, "n_frames", 1) != 1:
                raise OcrFailure(400, "MULTI_PAGE_IMAGE", "Exactly one rendered page image is required")
            width, height = image.size
            if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                raise OcrFailure(413, "PIXEL_LIMIT_EXCEEDED", "Image exceeds the configured pixel limit")
            image.load()
            if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
                rgba = image.convert("RGBA")
                flattened = Image.new("RGB", rgba.size, "white")
                flattened.paste(rgba, mask=rgba.getchannel("A"))
                normalized = flattened
            else:
                normalized = image.convert("RGB")
            normalized.save(target, format="PNG", optimize=False)
            return width, height
    except OcrFailure:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError) as exception:
        raise OcrFailure(400, "INVALID_IMAGE", "Image data is invalid") from exception


def run_tesseract(image_path: Path, output_base: Path) -> tuple[str, str]:
    try:
        subprocess.run(
            [
                "tesseract",
                str(image_path),
                str(output_base),
                "-l",
                LANGUAGE,
                "--oem",
                "1",
                "--psm",
                "6",
                "txt",
                "tsv",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=OCR_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exception:
        raise OcrFailure(504, "OCR_TIMEOUT", "OCR processing exceeded the configured timeout", True) from exception
    except (OSError, subprocess.CalledProcessError) as exception:
        raise OcrFailure(503, "OCR_ENGINE_FAILURE", "OCR engine failed", True) from exception

    try:
        text_bytes = output_base.with_suffix(".txt").read_bytes()
        tsv_bytes = output_base.with_suffix(".tsv").read_bytes()
        if len(text_bytes) + len(tsv_bytes) > MAX_OUTPUT_BYTES:
            raise OcrFailure(413, "OCR_OUTPUT_TOO_LARGE", "OCR output exceeds the configured byte limit")
        return text_bytes.decode("utf-8"), tsv_bytes.decode("utf-8")
    except UnicodeDecodeError as exception:
        raise OcrFailure(500, "OCR_OUTPUT_INVALID", "OCR engine produced invalid UTF-8", True) from exception
    except OSError as exception:
        raise OcrFailure(500, "OCR_OUTPUT_MISSING", "OCR engine output is unavailable", True) from exception


def process_ocr(
    reservation: OcrReservation, image_bytes: bytes, media_type: str
) -> tuple[int, int, str, str, float, str]:
    if not reservation.begin():
        raise OcrFailure(503, "OCR_BUSY", "OCR request admission is no longer active", True)
    try:
        with tempfile.TemporaryDirectory(prefix="ocr-page-") as directory:
            directory_path = Path(directory)
            image_path = directory_path / "page.png"
            output_base = directory_path / "result"
            width, height = prepare_image(image_bytes, media_type, image_path)
            text, raw_tsv = run_tesseract(image_path, output_base)

        if not text.strip():
            raise OcrFailure(422, "OCR_NO_TEXT", "No text was recognized on the page")
        confidence = page_confidence(raw_tsv)
        version = engine_version()
        return width, height, text, raw_tsv, confidence, version
    finally:
        reservation.release_running()


@app.post("/internal/v1/ocr/pages")
async def recognize_page(
    request: Request,
    image: Annotated[UploadFile, File()],
    page_number: Annotated[int, Form(ge=1, le=1000000)],
    source_hash: Annotated[str, Form(min_length=64, max_length=64)],
    artifact_key: Annotated[str, Form(min_length=1, max_length=500)],
    artifact_hash: Annotated[str, Form(min_length=64, max_length=64)],
    idempotency_key: Annotated[str, Form(min_length=1, max_length=200)],
    idempotency_key_header: Annotated[
        str, Header(alias="Idempotency-Key", min_length=1, max_length=200)
    ],
    x_ocr_language: Annotated[str, Header(alias="X-Ocr-Language")],
):
    if x_ocr_language != LANGUAGE:
        raise OcrFailure(400, "UNSUPPORTED_LANGUAGE", "OCR language must be kor+eng")
    if not hmac.compare_digest(idempotency_key, idempotency_key_header):
        raise OcrFailure(400, "IDEMPOTENCY_KEY_MISMATCH", "Idempotency key metadata does not match")
    require_sha256(source_hash, "sourceHash")
    require_sha256(artifact_hash, "artifactHash")
    media_type = (image.content_type or "").lower()
    image_bytes = await image.read(MAX_IMAGE_BYTES + 1)
    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        raise OcrFailure(413, "IMAGE_TOO_LARGE", "Image exceeds the configured byte limit")
    calculated_hash = hashlib.sha256(image_bytes).hexdigest()
    if not hmac.compare_digest(calculated_hash, artifact_hash):
        raise OcrFailure(400, "ARTIFACT_HASH_MISMATCH", "Image does not match artifactHash")

    width, height, text, raw_tsv, confidence, version = await run_in_threadpool(
        process_ocr, request.state.ocr_reservation, image_bytes, media_type
    )
    return {
        "pageNumber": page_number,
        "sourceHash": source_hash,
        "artifactKey": artifact_key,
        "artifactHash": artifact_hash,
        "idempotencyKey": idempotency_key,
        "width": width,
        "height": height,
        "text": text,
        "rawTsv": raw_tsv,
        "confidence": confidence,
        "engine": {
            "name": "tesseract",
            "version": version,
            "tessdataVersion": os.getenv("OCR_TESSDATA_VERSION", "unknown"),
            "language": LANGUAGE,
            "languages": ["kor", "eng"],
        },
    }
