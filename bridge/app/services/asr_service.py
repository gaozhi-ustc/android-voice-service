import asyncio
import time
import logging
import tempfile
import os

from app.core.config import settings

logger = logging.getLogger(__name__)

# base is fast but weak on Mandarin; medium is much more accurate and still
# real-time on Apple Silicon / server CPUs. Configure via asr_model in .env.
MODEL_NAME = settings.asr_model
# Cap ctranslate2 worker threads (avoids thread oversubscription on big
# servers, which can wedge inference).
CPU_THREADS = settings.asr_cpu_threads

_model = None


def _get_model():
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        logger.info("Loading Whisper model (%s, int8, cpu, threads=%d)...",
                    MODEL_NAME, CPU_THREADS)
        _model = WhisperModel(MODEL_NAME, device="cpu", compute_type="int8",
                              cpu_threads=CPU_THREADS)
        logger.info("Whisper model loaded")
    return _model


def _transcribe_sync(file_bytes: bytes, suffix: str) -> tuple:
    """Blocking inference. Runs in a worker thread (see transcribe)."""
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    try:
        tmp.write(file_bytes)
        tmp.flush()
        tmp.close()

        model = _get_model()
        segments, info = model.transcribe(
            tmp.name,
            language="zh",
            beam_size=5,
            vad_filter=True,
            initial_prompt="以下是普通话语音转写，请使用简体中文，不要使用繁体字。",
        )
        text = "".join(seg.text.strip() for seg in segments)
        return text, info
    finally:
        os.unlink(tmp.name)


class AsrService:
    async def transcribe(self, file_bytes: bytes, filename: str) -> dict:
        start = time.time()
        suffix = ".wav" if filename and filename.endswith(".wav") else ".bin"

        try:
            # Run blocking inference in the thread pool so a slow/hung ASR
            # cannot freeze the event loop (health/poll must stay responsive).
            text, info = await asyncio.to_thread(_transcribe_sync, file_bytes, suffix)
            latency_ms = int((time.time() - start) * 1000)

            logger.info(
                "ASR result: '%s' (lang=%s, prob=%.2f, %dms)",
                text[:100], info.language, info.language_probability, latency_ms
            )

            return {
                "text": text,
                "confidence": round(info.language_probability, 2),
                "language": info.language,
                "latency_ms": latency_ms,
            }
        except Exception as e:
            latency_ms = int((time.time() - start) * 1000)
            logger.error("ASR failed: %s", e)
            return {
                "text": "",
                "confidence": 0,
                "language": "unknown",
                "latency_ms": latency_ms,
            }


asr_service = AsrService()
