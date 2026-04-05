from typing import Optional
from fastapi import Header, HTTPException, status
from app.core.config import settings


def verify_token(authorization: Optional[str] = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
        )
    token = authorization.replace("Bearer ", "", 1).strip()
    if token != settings.bridge_token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid token",
        )
    return True
