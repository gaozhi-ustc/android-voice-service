class AsrService:
    async def transcribe(self, file_bytes: bytes, filename: str) -> dict:
        # TODO: 在这里接 faster-whisper / 外部 ASR 服务
        return {
            "text": "测试语音文本",
            "confidence": 0.99,
            "language": "zh-CN",
            "latency_ms": 500,
        }


asr_service = AsrService()
