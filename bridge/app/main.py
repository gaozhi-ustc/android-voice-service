from fastapi import FastAPI
from app.api.health import router as health_router
from app.api.voice import router as voice_router

app = FastAPI(title="OpenClaw Voice Bridge", version="0.1.0")

app.include_router(health_router, prefix="/v1")
app.include_router(voice_router, prefix="/v1")
