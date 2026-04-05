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
