import httpx
from app.core.config import settings


class GatewayService:
    async def send_text(self, payload: dict) -> dict:
        try:
            headers = {
                "Authorization": f"Bearer {settings.openclaw_gateway_token}",
                "Content-Type": "application/json",
            }
            async with httpx.AsyncClient(timeout=settings.request_timeout_seconds) as client:
                resp = await client.post(
                    settings.openclaw_gateway_url, json=payload, headers=headers
                )
                resp.raise_for_status()
                return resp.json()
        except Exception:
            # Mock response when Gateway is not available
            text = payload.get("text", "")
            return {
                "ok": True,
                "reply_text": f"已收到你的指令：{text}",
                "message_id": "mock-001",
            }


gateway_service = GatewayService()
