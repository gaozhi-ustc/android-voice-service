# 03. Bridge API Design

> Bridge 服务职责、接口协议与 FastAPI 代码骨架

---

# 1. Bridge 服务职责

Bridge 是语音终端的统一接入层，负责：

- 验证安卓设备身份
- 校验请求格式
- request_id 幂等去重
- 限流
- 记录日志
- 可选 ASR 转写
- 转发 OpenClaw Gateway
- 标准化响应格式

---

# 2. 推荐目录结构

```text
bridge/
├─ app/
│  ├─ main.py
│  ├─ api/
│  │  ├─ health.py
│  │  └─ voice.py
│  ├─ core/
│  │  ├─ auth.py
│  │  └─ config.py
│  ├─ models/
│  │  ├─ request.py
│  │  └─ response.py
│  └─ services/
│     ├─ dedupe_service.py
│     ├─ gateway_service.py
│     └─ asr_service.py
└─ requirements.txt
```

---

# 3. API 列表

## 3.1 健康检查

- `GET /v1/health`

## 3.2 文字指令

- `POST /v1/voice/command`

## 3.3 音频转写

- `POST /v1/asr/transcribe`

## 3.4 音频上传并直接执行

- `POST /v1/voice/audio-command`

---

# 4. 请求与响应协议

## 4.1 文本指令请求

### Endpoint

`POST /v1/voice/command`

### Headers

```http
Authorization: Bearer <device_token>
Content-Type: application/json
X-Device-Id: android-phone-01
X-Request-Id: 4f4ddf3a-0f30-4d1a-9a68-123456789abc
```

### Body

```json
{
  "device_id": "android-phone-01",
  "session_target": "main",
  "text": "帮我看看明天的日程",
  "trigger_type": "wakeword",
  "trigger_word": "你好助手",
  "lang": "zh-CN",
  "timestamp": "2026-04-05T19:33:00+08:00",
  "meta": {
    "app_version": "1.0.0",
    "network_type": "wifi",
    "audio_duration_ms": 4200,
    "asr_latency_ms": 1100
  }
}
```

### Response

```json
{
  "ok": true,
  "request_id": "4f4ddf3a-0f30-4d1a-9a68-123456789abc",
  "message_id": "gw-20260405-001",
  "reply_text": "明天上午十点有一个会议，下午两点要跟进销售数据。",
  "should_tts": true,
  "tts_text": "明天上午十点有一个会议，下午两点要跟进销售数据。",
  "display_text": "明天上午十点有一个会议，下午两点要跟进销售数据。",
  "latency_ms": 1830
}
```

## 4.2 音频转写请求

### Endpoint

`POST /v1/asr/transcribe`

### Form 字段

- `device_id`
- `session_target`
- `trigger_type`
- `timestamp`
- `audio_file`

### Response

```json
{
  "ok": true,
  "text": "帮我打开今天的待办",
  "confidence": 0.94,
  "language": "zh-CN",
  "latency_ms": 980
}
```

## 4.3 音频上传并直接执行

### Endpoint

`POST /v1/voice/audio-command`

### Response

```json
{
  "ok": true,
  "recognized_text": "帮我总结一下今天的消息",
  "reply_text": "今天主要有三件事：第一，客户确认了需求；第二，明天要准备会议材料；第三，待补充预算表。",
  "should_tts": true,
  "latency_ms": 2500
}
```

---

# 5. 错误码设计

## 客户端错误

- `400 Bad Request`
- `401 Unauthorized`
- `403 Forbidden`
- `409 Conflict`
- `422 Unprocessable Entity`

## 服务端错误

- `429 Too Many Requests`
- `500 Internal Server Error`
- `502 Bad Gateway`
- `504 Gateway Timeout`

统一错误响应：

```json
{
  "ok": false,
  "error_code": "RATE_LIMITED",
  "message": "too many requests",
  "request_id": "4f4ddf3a-0f30-4d1a-9a68-123456789abc"
}
```

---

# 6. FastAPI 代码骨架

## 6.1 `app/main.py`

```python
from fastapi import FastAPI
from app.api.health import router as health_router
from app.api.voice import router as voice_router

app = FastAPI(title="OpenClaw Voice Bridge", version="0.1.0")

app.include_router(health_router, prefix="/v1")
app.include_router(voice_router, prefix="/v1")
```

## 6.2 `app/core/config.py`

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    bridge_token: str = "change-me"
    openclaw_gateway_url: str = "http://127.0.0.1:8080/internal/openclaw/ingest"
    openclaw_gateway_token: str = "change-me-too"
    request_timeout_seconds: int = 15

    class Config:
        env_file = ".env"

settings = Settings()
```

## 6.3 `app/core/auth.py`

```python
from fastapi import Header, HTTPException, status
from app.core.config import settings


def verify_token(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token")
    token = authorization.replace("Bearer ", "", 1).strip()
    if token != settings.bridge_token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid token")
    return True
```

## 6.4 `app/models/request.py`

```python
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
```

## 6.5 `app/models/response.py`

```python
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
```

## 6.6 `app/services/dedupe_service.py`

```python
class DedupeService:
    def __init__(self):
        self._seen = set()

    def check_and_mark(self, request_id: str) -> bool:
        if request_id in self._seen:
            return False
        self._seen.add(request_id)
        return True

dedupe_service = DedupeService()
```

> 正式版建议换成 Redis + TTL。

## 6.7 `app/services/gateway_service.py`

```python
import httpx
from app.core.config import settings

class GatewayService:
    async def send_text(self, payload: dict) -> dict:
        headers = {
            "Authorization": f"Bearer {settings.openclaw_gateway_token}",
            "Content-Type": "application/json",
        }
        async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
            resp = await client.post(settings.openclaw_gateway_url, json=payload, headers=headers)
            resp.raise_for_status()
            return resp.json()

gateway_service = GatewayService()
```

## 6.8 `app/services/asr_service.py`

```python
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
```

## 6.9 `app/api/health.py`

```python
from fastapi import APIRouter

router = APIRouter()

@router.get("/health")
async def health():
    return {"ok": True, "service": "voice-bridge"}
```

## 6.10 `app/api/voice.py`

```python
import time
from fastapi import APIRouter, Depends, Header, HTTPException, UploadFile, File, Form
from app.core.auth import verify_token
from app.models.request import VoiceCommandRequest
from app.models.response import VoiceCommandResponse
from app.services.dedupe_service import dedupe_service
from app.services.gateway_service import gateway_service
from app.services.asr_service import asr_service

router = APIRouter()

@router.post("/voice/command", response_model=VoiceCommandResponse)
async def voice_command(
    body: VoiceCommandRequest,
    _: bool = Depends(verify_token),
    x_request_id: str = Header(default=""),
):
    started = time.time()
    request_id = x_request_id or f"req-{int(started * 1000)}"

    if not dedupe_service.check_and_mark(request_id):
        raise HTTPException(status_code=409, detail="duplicate request")

    payload = {
        "source": "voice-bridge",
        "channel": "voice",
        "user_id": "gaozhi",
        "session_target": body.session_target,
        "text": body.text,
        "context": {
            "device_id": body.device_id,
            "trigger_type": body.trigger_type,
            "trigger_word": body.trigger_word,
            "lang": body.lang,
            "request_id": request_id,
            "meta": body.meta,
        },
    }

    result = await gateway_service.send_text(payload)
    latency_ms = int((time.time() - started) * 1000)

    reply_text = result.get("reply_text", "")
    return VoiceCommandResponse(
        ok=True,
        request_id=request_id,
        message_id=result.get("message_id"),
        reply_text=reply_text,
        should_tts=True,
        tts_text=reply_text,
        display_text=reply_text,
        latency_ms=latency_ms,
    )

@router.post("/asr/transcribe")
async def asr_transcribe(
    device_id: str = Form(...),
    session_target: str = Form("main"),
    trigger_type: str = Form("manual"),
    timestamp: str = Form(...),
    audio_file: UploadFile = File(...),
    _: bool = Depends(verify_token),
):
    file_bytes = await audio_file.read()
    result = await asr_service.transcribe(file_bytes, audio_file.filename)
    return {
        "ok": True,
        "device_id": device_id,
        "session_target": session_target,
        "trigger_type": trigger_type,
        "timestamp": timestamp,
        **result,
    }

@router.post("/voice/audio-command")
async def audio_command(
    device_id: str = Form(...),
    session_target: str = Form("main"),
    trigger_type: str = Form("manual"),
    timestamp: str = Form(...),
    audio_file: UploadFile = File(...),
    _: bool = Depends(verify_token),
    x_request_id: str = Header(default=""),
):
    started = time.time()
    request_id = x_request_id or f"req-{int(started * 1000)}"

    if not dedupe_service.check_and_mark(request_id):
        raise HTTPException(status_code=409, detail="duplicate request")

    file_bytes = await audio_file.read()
    asr_result = await asr_service.transcribe(file_bytes, audio_file.filename)
    recognized_text = asr_result["text"]

    payload = {
        "source": "voice-bridge",
        "channel": "voice",
        "user_id": "gaozhi",
        "session_target": session_target,
        "text": recognized_text,
        "context": {
            "device_id": device_id,
            "trigger_type": trigger_type,
            "request_id": request_id,
        },
    }

    result = await gateway_service.send_text(payload)
    latency_ms = int((time.time() - started) * 1000)
    reply_text = result.get("reply_text", "")

    return {
        "ok": True,
        "request_id": request_id,
        "recognized_text": recognized_text,
        "reply_text": reply_text,
        "should_tts": True,
        "tts_text": reply_text,
        "latency_ms": latency_ms,
    }
```

## 6.11 `requirements.txt`

```txt
fastapi
uvicorn[standard]
httpx
python-multipart
pydantic
pydantic-settings
```

---

# 7. 后续增强建议

- Redis 做 request_id 去重和 TTL
- 设备白名单与多 token 支持
- HMAC 签名
- 访问日志与 metrics
- ASR 服务异步化
- 结果缓存
