import asyncio
import hashlib
import io
import threading
import time
import unittest
from unittest.mock import patch

from fastapi import UploadFile
from starlette.datastructures import Headers

from app import main


IMAGE_BYTES = b"mock-image"
TSV = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
    "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\ttext\n"
)


class OcrConcurrencyTest(unittest.IsolatedAsyncioTestCase):
    async def recognize(self):
        digest = hashlib.sha256(IMAGE_BYTES).hexdigest()
        image = UploadFile(
            file=io.BytesIO(IMAGE_BYTES),
            filename="page.png",
            headers=Headers({"content-type": "image/png"}),
        )
        return await main.recognize_page(
            image=image,
            page_number=1,
            source_hash="a" * 64,
            artifact_key="artifact/page-1.png",
            artifact_hash=digest,
            idempotency_key="request-1",
            idempotency_key_header="request-1",
            x_ocr_language="kor+eng",
        )

    async def test_slow_ocr_does_not_block_event_loop(self):
        started = threading.Event()
        release = threading.Event()

        def slow_tesseract(_image_path, _output_base):
            started.set()
            release.wait(timeout=1)
            return "text", TSV

        timer = threading.Timer(0.5, release.set)
        timer.start()
        try:
            with (
                patch.object(main, "prepare_image", return_value=(10, 10)),
                patch.object(main, "run_tesseract", side_effect=slow_tesseract),
                patch.object(main, "engine_version", return_value="5.3.0"),
            ):
                before = time.monotonic()
                request = asyncio.create_task(self.recognize())
                self.assertTrue(await asyncio.to_thread(started.wait, 1))
                self.assertLess(time.monotonic() - before, 0.25)
                before = time.monotonic()
                await asyncio.sleep(0.01)
                self.assertLess(time.monotonic() - before, 0.25)
                release.set()
                await request
        finally:
            release.set()
            timer.cancel()

    async def test_concurrent_admission_is_rejected(self):
        started = threading.Event()
        release = threading.Event()

        def slow_tesseract(_image_path, _output_base):
            started.set()
            release.wait(timeout=1)
            return "text", TSV

        with (
            patch.object(main, "prepare_image", return_value=(10, 10)),
            patch.object(main, "run_tesseract", side_effect=slow_tesseract),
            patch.object(main, "engine_version", return_value="5.3.0"),
        ):
            first = asyncio.create_task(self.recognize())
            try:
                self.assertTrue(await asyncio.to_thread(started.wait, 1))
                with self.assertRaises(main.OcrFailure) as raised:
                    await self.recognize()
                self.assertEqual(raised.exception.status, 503)
                self.assertEqual(raised.exception.code, "OCR_BUSY")
                self.assertTrue(raised.exception.retryable)
            finally:
                release.set()
                await first

    async def test_permit_is_returned_after_processing_error(self):
        calls = 0

        def prepare(_image_bytes, _media_type, _target):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise main.OcrFailure(400, "INVALID_IMAGE", "Image data is invalid")
            return 10, 10

        with (
            patch.object(main, "prepare_image", side_effect=prepare),
            patch.object(main, "run_tesseract", return_value=("text", TSV)),
            patch.object(main, "engine_version", return_value="5.3.0"),
        ):
            with self.assertRaises(main.OcrFailure) as raised:
                await self.recognize()
            self.assertEqual(raised.exception.code, "INVALID_IMAGE")
            response = await self.recognize()
            self.assertEqual(response["text"], "text")

    async def test_cancellation_does_not_release_permit_before_processing_finishes(self):
        started = threading.Event()
        release = threading.Event()
        finished = threading.Event()
        call_count = 0
        active = 0
        maximum_active = 0
        lock = threading.Lock()

        def tesseract(_image_path, _output_base):
            nonlocal call_count, active, maximum_active
            with lock:
                call_count += 1
                current_call = call_count
                active += 1
                maximum_active = max(maximum_active, active)
            try:
                if current_call == 1:
                    started.set()
                    release.wait(timeout=1)
                return "text", TSV
            finally:
                with lock:
                    active -= 1
                if current_call == 1:
                    finished.set()

        with (
            patch.object(main, "prepare_image", return_value=(10, 10)),
            patch.object(main, "run_tesseract", side_effect=tesseract),
            patch.object(main, "engine_version", return_value="5.3.0"),
        ):
            first = asyncio.create_task(self.recognize())
            self.assertTrue(await asyncio.to_thread(started.wait, 1))
            first.cancel()
            await asyncio.sleep(0)
            with self.assertRaises(main.OcrFailure) as raised:
                await self.recognize()
            self.assertEqual(raised.exception.code, "OCR_BUSY")

            release.set()
            self.assertTrue(await asyncio.to_thread(finished.wait, 1))
            with self.assertRaises(asyncio.CancelledError):
                await first
            for _attempt in range(100):
                if main.OCR_PERMIT.acquire(blocking=False):
                    main.OCR_PERMIT.release()
                    break
                await asyncio.sleep(0.01)
            else:
                self.fail("OCR permit was not returned after background processing finished")
            response = await self.recognize()
            self.assertEqual(response["text"], "text")
            self.assertEqual(maximum_active, 1)


if __name__ == "__main__":
    unittest.main()
