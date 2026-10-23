"""PhishX API using the OpenPhish community feed for URL checks."""
from __future__ import annotations

import os
import threading
import time
from urllib.error import URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field, field_validator

FEED_URL = os.getenv(
    "OPENPHISH_FEED_URL",
    "https://openphish.com/feed.txt",
)
FEED_REFRESH_SECONDS = 12 * 60 * 60
FEED_TIMEOUT_SECONDS = 20
MAX_URL_LENGTH = 2048

app = FastAPI(title="PhishX API", version="1.1.0")

_feed_lock = threading.Lock()
_feed_urls: set[str] = set()
_last_feed_refresh = 0.0
_last_feed_error: str | None = None


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=1, max_length=MAX_URL_LENGTH)

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        value = value.strip()
        parts = urlsplit(value)

        if (
            not value
            or any(ord(char) < 32 for char in value)
            or parts.scheme.lower() not in {"http", "https"}
            or not parts.hostname
        ):
            raise ValueError("url must be an absolute http or https URL")

        return value


class PredictResponse(BaseModel):
    verdict: str
    confidence: float


def normalize_url(value: str) -> str | None:
    """Normalize feed entries and submitted URLs for comparison."""
    try:
        parts = urlsplit(value.strip())
        host = (parts.hostname or "").encode("idna").decode("ascii").lower().rstrip(".")
        if not host:
            return None

        port = parts.port
        if port and not (
            (parts.scheme.lower() == "http" and port == 80)
            or (parts.scheme.lower() == "https" and port == 443)
        ):
            host = f"{host}:{port}"

        path = parts.path or "/"
        return urlunsplit(("", host, path, parts.query, ""))
    except (UnicodeError, ValueError):
        return None


def refresh_feed_if_needed() -> bool:
    """Refresh the in-memory feed when due; retain an older copy on errors."""
    global _feed_urls, _last_feed_refresh, _last_feed_error

    with _feed_lock:
        if _feed_urls and time.time() - _last_feed_refresh < FEED_REFRESH_SECONDS:
            return True

        request = Request(
            FEED_URL,
            headers={"User-Agent": "PhishX/1.1"},
        )

        try:
            with urlopen(request, timeout=FEED_TIMEOUT_SECONDS) as response:
                feed_text = response.read().decode("utf-8", errors="replace")

            urls = {
                normalized
                for line in feed_text.splitlines()
                if (normalized := normalize_url(line))
            }

            if not urls:
                raise ValueError("OpenPhish feed was empty or contained no valid URLs")

            _feed_urls = urls
            _last_feed_refresh = time.time()
            _last_feed_error = None
            return True

        except (OSError, URLError, TimeoutError, ValueError) as error:
            _last_feed_error = f"{type(error).__name__}: {error}"
            # Keep using the last successfully downloaded feed if available.
            return bool(_feed_urls)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    feed_available = refresh_feed_if_needed()

    if not feed_available:
        return PredictResponse(verdict="unknown", confidence=0.0)

    normalized = normalize_url(request.url)
    if normalized is None:
        return PredictResponse(verdict="unknown", confidence=0.0)

    if normalized in _feed_urls:
        return PredictResponse(verdict="phishing", confidence=1.0)

    # OpenPhish feed absence does not establish that a URL is safe.
    return PredictResponse(verdict="unknown", confidence=0.0)