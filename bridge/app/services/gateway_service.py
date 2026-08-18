"""Agent task queue.

Instead of forwarding to an external OpenClaw Gateway, each voice command is
written as a JSON task file into a local queue. The live coding-agent session
on this Mac polls the queue (bridge/tools/wait_task.py), executes the task as
the agent, and writes the reply back to the replies directory. The bridge
picks it up and returns it to the phone.

Layout:
    /tmp/voice-bridge-tasks/
        pending/<task_id>.json     # task waiting for the agent
        processing/<task_id>.json  # claimed by the agent
        replies/<task_id>.json     # agent reply: {"reply_text": "..."}
"""
import asyncio
import json
import logging
import os
import time
import uuid

from app.core.config import settings

logger = logging.getLogger(__name__)

TASK_DIR = os.environ.get("VOICE_AGENT_TASK_DIR", "/tmp/voice-bridge-tasks")
PENDING_DIR = os.path.join(TASK_DIR, "pending")
REPLY_DIR = os.path.join(TASK_DIR, "replies")
POLL_INTERVAL = 0.3


class GatewayService:
    def __init__(self):
        os.makedirs(PENDING_DIR, exist_ok=True)
        os.makedirs(REPLY_DIR, exist_ok=True)

    async def send_text(self, payload: dict, request_id: str = None) -> dict:
        text = payload.get("text", "")
        if not request_id:
            request_id = str(uuid.uuid4())

        if not text.strip():
            return {
                "ok": True,
                "reply_text": "我没有听清，请再说一次",
                "message_id": request_id,
            }

        task = {
            "task_id": request_id,
            "text": text,
            "session_target": payload.get("session_target", "main"),
            "device_id": payload.get("context", {}).get("device_id", ""),
            "trigger_type": payload.get("context", {}).get("trigger_type", ""),
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

        task_path = os.path.join(PENDING_DIR, f"{request_id}.json")
        reply_path = os.path.join(REPLY_DIR, f"{request_id}.json")

        # Write atomically so the agent never sees a partial file
        tmp_path = task_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(task, f, ensure_ascii=False, indent=2)
        os.rename(tmp_path, task_path)

        logger.info("Task queued for agent session [%s]: '%s'", request_id[:8], text[:100])

        # Three-phase response (seconds from task queue):
        #   reply < interim                     -> return the answer now
        #   interim <= reply < grace_deadline   -> hold until grace_deadline,
        #                                          return interim + answer
        #   nothing by grace_deadline           -> return the interim only;
        #                                          answer (if it lands later) is
        #                                          consumed on the next voice turn
        interim_at = settings.agent_interim_seconds
        grace_at = max(settings.agent_grace_deadline_seconds, interim_at)
        interim_text = "正在查询，请稍后。"
        t0 = time.time()

        def _take_reply() -> str:
            with open(reply_path, encoding="utf-8") as f:
                data = json.load(f)
            return data.get("reply_text", "") if isinstance(data, dict) else str(data)

        while True:
            elapsed = time.time() - t0
            if os.path.exists(reply_path):
                try:
                    reply = _take_reply()
                except Exception as e:
                    logger.error("Failed to parse agent reply: %s", e)
                    return {
                        "ok": True,
                        "reply_text": "回复解析失败，请再说一次",
                        "message_id": request_id,
                    }
                try:
                    os.unlink(reply_path)
                except OSError:
                    pass
                if elapsed < interim_at:
                    logger.info("Agent reply for [%s] in %.1fs: '%s'", request_id[:8], elapsed, reply[:100])
                    return {
                        "ok": True,
                        "reply_text": reply or "任务已完成",
                        "message_id": request_id,
                    }
                # Reply landed in the grace zone: hold until the deadline, then
                # speak interim + answer so the quick answer is not lost.
                remaining = grace_at - elapsed
                if remaining > 0:
                    await asyncio.sleep(remaining)
                logger.info("Agent reply for [%s] in %.1fs (grace zone): '%s'", request_id[:8], time.time() - t0, reply[:100])
                return {
                    "ok": True,
                    "reply_text": interim_text + (reply or "任务已完成"),
                    "message_id": request_id,
                }
            if elapsed >= grace_at:
                # The HTTP response goes out now, so the request will never
                # consume the reply file. Mark the task released so the
                # outboxer can deliver a late reply to the phone immediately
                # (phone polling) instead of waiting PUSH_AFTER_SECONDS.
                marker = os.path.join(REPLY_DIR, f"released-{request_id}.marker")
                try:
                    with open(marker, "w") as f:
                        f.write("")
                except OSError:
                    pass
                logger.info("Interim response for [%s] at %.1fs, task still running (reply released for polling)", request_id[:8], elapsed)
                return {
                    "ok": True,
                    "reply_text": interim_text,
                    "message_id": request_id,
                }
            await asyncio.sleep(0.3)


gateway_service = GatewayService()
