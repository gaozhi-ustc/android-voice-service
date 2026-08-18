"""Late-reply outbox.

When an agent reply lands after the request has already returned an interim
response, the file sits in the replies directory. This watcher moves such
late replies (mtime > PUSH_AFTER_SECONDS) into an outbox directory, from
which the phone picks them up by polling GET /v1/voice/poll over WiFi.

No USB/adb dependency — the phone polls the bridge directly.
"""
import asyncio
import glob
import json
import logging
import os
import time

logger = logging.getLogger(__name__)

TASK_DIR = os.environ.get("VOICE_AGENT_TASK_DIR", "/tmp/voice-bridge-tasks")
REPLY_DIR = os.path.join(TASK_DIR, "replies")
OUTBOX_DIR = os.path.join(REPLY_DIR, "outbox")

# Replies younger than this are still owned by the request handler
# (fast <10s or grace <=12s). Anything older is "late" and goes to the
# outbox for phone polling.
PUSH_AFTER_SECONDS = 13


async def late_reply_outboxer() -> None:
    """Watch for late agent replies and move them into the outbox."""
    os.makedirs(OUTBOX_DIR, exist_ok=True)
    while True:
        await asyncio.sleep(1)
        for path in glob.glob(os.path.join(REPLY_DIR, "*.json")):
            try:
                if time.time() - os.path.getmtime(path) < PUSH_AFTER_SECONDS:
                    continue
                with open(path, encoding="utf-8") as f:
                    data = json.load(f)
                text = data.get("reply_text", "") if isinstance(data, dict) else str(data)
                if not text.strip():
                    continue
                dest = os.path.join(OUTBOX_DIR, os.path.basename(path))
                os.rename(path, dest)
                logger.info("Late reply moved to outbox for phone polling: '%s'", text[:60])
            except FileNotFoundError:
                continue  # consumed by the request handler
            except Exception as e:
                logger.error("late reply outbox error: %s", e)


def claim_outbox_reply() -> str:
    """Atomically claim the oldest outbox reply. Returns '' if none."""
    if not os.path.isdir(OUTBOX_DIR):
        return ""
    for path in sorted(glob.glob(os.path.join(OUTBOX_DIR, "*.json"))):
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            text = data.get("reply_text", "") if isinstance(data, dict) else str(data)
            os.unlink(path)
            if text.strip():
                return text
        except FileNotFoundError:
            continue  # another poller claimed it
        except Exception:
            continue
    return ""
