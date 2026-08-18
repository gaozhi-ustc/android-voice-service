from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    bridge_token: str = "change-me"
    openclaw_gateway_url: str = "ws://127.0.0.1:18789/ws"
    openclaw_gateway_token: str = "93c75e14b762b9ceac7e2f6476ea2a2632b2e35013f47436"
    openclaw_cli_path: str = "/Users/gaozhi/.nvm/versions/node/v24.10.0/bin/openclaw"
    home_dir: str = "/Users/gaozhi"
    request_timeout_seconds: int = 60
    # ASR model: tiny / base / small / medium / large-v3
    asr_model: str = "medium"
    # File queue directory for the agent session
    voice_agent_task_dir: str = "/tmp/voice-bridge-tasks"
    # Voice reply timing (seconds from task queue):
    #  - reply ready before agent_interim_seconds        -> speak the answer
    #  - reply ready in [interim, grace_deadline)        -> at grace_deadline speak
    #    "正在查询，请稍后。" + the answer (keeps late-but-quick answers)
    #  - nothing by grace_deadline                        -> speak the interim only;
    #    the final reply file is delivered on the next voice turn
    agent_interim_seconds: int = 5
    agent_grace_deadline_seconds: int = 12

    class Config:
        env_file = ".env"
        # Unknown env vars (e.g. system noise) must not crash startup
        extra = "ignore"


settings = Settings()
