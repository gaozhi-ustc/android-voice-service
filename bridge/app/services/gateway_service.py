import httpx
from app.core.config import settings


class GatewayService:
    async def send_text(self, payload: dict) -> dict:
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


gateway_service = GatewayService()
