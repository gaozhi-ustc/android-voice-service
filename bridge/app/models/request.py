from typing import Optional, Literal, Dict, Any
from pydantic import BaseModel, Field


class VoiceCommandRequest(BaseModel):
    device_id: str
    session_target: str = "main"
    text: str = Field(min_length=1)
    trigger_type: Literal["wakeword", "manual", "button", "headset"] = "manual"
    trigger_word: Optional[str] = None
    lang: str = "zh-CN"
    timestamp: str
    meta: Dict[str, Any] = {}
