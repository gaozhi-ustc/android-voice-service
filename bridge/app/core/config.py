from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    bridge_token: str = "change-me"
    openclaw_gateway_url: str = "http://127.0.0.1:8080/internal/openclaw/ingest"
    openclaw_gateway_token: str = "change-me-too"
    request_timeout_seconds: int = 15

    class Config:
        env_file = ".env"


settings = Settings()
