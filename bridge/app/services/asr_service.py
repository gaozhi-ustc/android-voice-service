import io
import os
import time
import logging
import tempfile

from app.core.config import settings

logger = logging.getLogger(__name__)

# base is fast but weak on Mandarin; medium is much more accurate and still
# real-time on Apple Silicon / server CPUs. Configure via ASR_MODEL in .env.
MODEL_NAME = settings.asr_model

_model = None


def _get_model():
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        logger.info("Loading Whisper model (%s, int8, cpu)...", MODEL_NAME)
        _model = WhisperModel(MODEL_NAME, device="cpu", compute_type="int8")
        logger.info("Whisper model loaded")
    return _model


class AsrService:
    async def transcribe(self, file_bytes: bytes, filename: str) -> dict:
        start = time.time()

        # Write audio to temp file (faster-whisper needs a file path or numpy array)
        suffix = ".wav" if filename and filename.endswith(".wav") else ".bin"
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

            text_parts = []
            for segment in segments:
                text_parts.append(segment.text.strip())

            text = "".join(text_parts)
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
        finally:
            os.unlink(tmp.name)


asr_service = AsrService()
