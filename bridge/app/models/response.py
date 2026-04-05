from typing import Optional
from pydantic import BaseModel


class VoiceCommandResponse(BaseModel):
    ok: bool
    request_id: str
    message_id: Optional[str] = None
    reply_text: Optional[str] = None
    should_tts: bool = True
    tts_text: Optional[str] = None
    display_text: Optional[str] = None
    latency_ms: int = 0


class ErrorResponse(BaseModel):
    ok: bool = False
    error_code: str
    message: str
    request_id: Optional[str] = None
