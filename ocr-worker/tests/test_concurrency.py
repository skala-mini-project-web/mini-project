import asyncio
import hashlib
import io
import threading
import time
import unittest
from unittest.mock import patch

from fastapi import Request, UploadFile
from starlette.datastructures import Headers

from app import main


IMAGE_BYTES = b"mock-image"
TSV = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
    "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\ttext\n"
)


class OcrConcurrencyTest(unittest.IsolatedAsyncioTestCase):
    async def recognize(self):
        reservation = main.OcrReservation.reserve()
        if reservation is None:
            raise main.OcrFailure(503, "OCR_BUSY", "OCR worker is already processing a page", True)
        request = Request({"type": "http", "method": "POST", "path": "/internal/v1/ocr/pages"})
        request.state.ocr_reservation = reservation
        digest = hashlib.sha256(IMAGE_BYTES).hexdigest()
        image = UploadFile(
            file=io.BytesIO(IMAGE_BYTES),
            filename="page.png",
            headers=Headers({"content-type": "image/png"}),
        )
        try:
            return await main.recognize_page(
                request=request,
                image=image,
                page_number=1,
                source_hash="a" * 64,
                artifact_key="artifact/page-1.png",
                artifact_hash=digest,
                idempotency_key="request-1",
                idempotency_key_header="request-1",
                x_ocr_language="kor+eng",
            )
        finally:
            reservation.release_reserved()

    async def test_busy_admission_does_not_consume_request_body(self):
        held = main.OcrReservation.reserve()
        self.assertIsNotNone(held)
        body_reads = 0

        async def receive():
            nonlocal body_reads
            body_reads += 1
            return {"type": "http.request", "body": b"unread", "more_body": False}

        request = Request(
            {
                "type": "http",
                "method": "POST",
                "path": "/internal/v1/ocr/pages",
                "headers": [
                    (b"authorization", b"Bearer test-token"),
                    (b"content-length", b"6"),
                ],
            },
            receive,
        )
        call_next_called = False

        async def call_next(_request):
            nonlocal call_next_called
            call_next_called = True
            await _request.body()

        try:
            with patch.dict(main.os.environ, {"OCR_WORKER_TOKEN": "test-token"}):
                response = await main.protect_private_endpoint(request, call_next)
            self.assertEqual(response.status_code, 503)
            self.assertIn(b'"OCR_BUSY"', response.body)
            self.assertFalse(call_next_called)
            self.assertEqual(body_reads, 0)
        finally:
            held.release_reserved()

    async def test_error_before_worker_start_releases_reservation(self):
        request = Request(
            {
                "type": "http",
                "method": "POST",
                "path": "/internal/v1/ocr/pages",
                "headers": [
                    (b"authorization", b"Bearer test-token"),
                    (b"content-length", b"6"),
                ],
            }
        )

        async def fail_before_dispatch(_request):
            raise RuntimeError("request parsing failed")

        with patch.dict(main.os.environ, {"OCR_WORKER_TOKEN": "test-token"}):
            with self.assertRaisesRegex(RuntimeError, "request parsing failed"):
                await main.protect_private_endpoint(request, fail_before_dispatch)

        reservation = main.OcrReservation.reserve()
        self.assertIsNotNone(reservation)
        reservation.release_reserved()

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

    async def test_cancellation_before_worker_start_invalidates_delayed_dispatch(self):
        dispatch_blocked = threading.Event()
        dispatch_release = threading.Event()
        dispatch_finished = threading.Event()
        prepare_calls = 0
        dispatch_calls = 0
        delayed_task = None

        def prepare(_image_bytes, _media_type, _target):
            nonlocal prepare_calls
            prepare_calls += 1
            return 10, 10

        async def delayed_threadpool(function, *args):
            nonlocal dispatch_calls, delayed_task
            dispatch_calls += 1
            if dispatch_calls > 1:
                return await asyncio.to_thread(function, *args)

            def queued():
                dispatch_blocked.set()
                dispatch_release.wait(timeout=1)
                try:
                    return function(*args)
                finally:
                    dispatch_finished.set()

            delayed_task = asyncio.create_task(asyncio.to_thread(queued))
            return await asyncio.shield(delayed_task)

        with (
            patch.object(main, "prepare_image", side_effect=prepare),
            patch.object(main, "run_tesseract", return_value=("text", TSV)),
            patch.object(main, "engine_version", return_value="5.3.0"),
            patch.object(main, "run_in_threadpool", side_effect=delayed_threadpool),
        ):
            request = asyncio.create_task(self.recognize())
            self.assertTrue(await asyncio.to_thread(dispatch_blocked.wait, 1))
            request.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await request

            response = await self.recognize()
            self.assertEqual(response["text"], "text")

            dispatch_release.set()
            self.assertTrue(await asyncio.to_thread(dispatch_finished.wait, 1))
            with self.assertRaises(main.OcrFailure) as raised:
                await delayed_task
            self.assertEqual(raised.exception.code, "OCR_BUSY")
            self.assertEqual(prepare_calls, 1)

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
