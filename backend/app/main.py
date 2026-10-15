"""Small stateless phishing URL scoring API. URLs are never logged or persisted."""
from __future__ import annotations

import ipaddress
import os
import re
import socket
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from urllib.parse import urlsplit

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_URL_LENGTH = 2048
LOOKUP_TIMEOUT = max(0.05, min(float(os.getenv("PHISHX_LOOKUP_TIMEOUT_SECONDS", "0.35")), 2.0))
_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="url-enrichment")
app = FastAPI(title="PhishX API", version="1.0.0")


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=1, max_length=MAX_URL_LENGTH)

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        value = value.strip()
        if not value or any(ord(c) < 32 for c in value):
            raise ValueError("url must be a non-empty URL without control characters")
        parts = urlsplit(value)
        if parts.scheme not in {"http", "https"} or not parts.hostname:
            raise ValueError("url must be an absolute http or https URL")
        return value


class PredictResponse(BaseModel):
    verdict: str
    confidence: float


def _dns_resolves(host: str) -> bool:
    socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    return True


def score_url(raw: str) -> PredictResponse:
    """Explainable starter signals; enrichment failures/timeouts never fail prediction."""
    parts = urlsplit(raw)
    host = (parts.hostname or "").lower().rstrip(".")
    signals = 0
    if parts.scheme != "https":
        signals += 1
    if "@" in parts.netloc:
        signals += 2
    try:
        ipaddress.ip_address(host)
        signals += 2
    except ValueError:
        pass
    if host.startswith("xn--") or ".xn--" in host:
        signals += 2
    if len(host) > 50 or host.count("-") >= 4:
        signals += 1
    if len(parts.path) > 90 or len(parts.query) > 180:
        signals += 1
    if re.search(r"(login|verify|secure|account|update|wallet|password)", raw, re.I):
        signals += 1
    # DNS is advisory only: unknown hosts are not automatically labeled malicious.
    try:
        _pool.submit(_dns_resolves, host).result(timeout=LOOKUP_TIMEOUT)
    except (FutureTimeout, OSError, ValueError):
        pass
    phishing = signals >= 3
    confidence = min(0.99, 0.72 + 0.06 * max(0, signals - 3)) if phishing else max(0.55, 0.94 - 0.05 * signals)
    return PredictResponse(verdict="phishing" if phishing else "safe", confidence=round(confidence, 2))


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    return score_url(request.url)
