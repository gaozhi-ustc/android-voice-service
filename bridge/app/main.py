import asyncio
import logging

from fastapi import FastAPI
from app.api.health import router as health_router
from app.api.voice import router as voice_router
from app.services.late_reply_pusher import late_reply_outboxer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="OpenClaw Voice Bridge", version="0.1.0")

app.include_router(health_router, prefix="/v1")
app.include_router(voice_router, prefix="/v1")


@app.on_event("startup")
async def _start_pusher() -> None:
    logging.info("Late reply outbox watcher started")
    asyncio.get_running_loop().create_task(late_reply_outboxer())

