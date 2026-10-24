"""PhishX API using the OpenPhish community feed and intelligent multi-signal URL detector."""
from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen

import joblib
from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.detector import classify_url

FEED_URL = os.getenv(
    "OPENPHISH_FEED_URL",
    "https://openphish.com/feed.txt",
)
FEED_REFRESH_SECONDS = 12 * 60 * 60
FEED_TIMEOUT_SECONDS = 10
MAX_URL_LENGTH = 2048

app = FastAPI(title="PhishX API", version="1.2.0")
MODEL_PATH = Path(__file__).with_name("url_model.joblib")

try:
    _model_bundle = joblib.load(MODEL_PATH) if MODEL_PATH.exists() else None
except Exception as error:
    _model_bundle = None
    _model_load_error = f"{type(error).__name__}: {error}"
else:
    _model_load_error = None

_feed_lock = threading.Lock()
_feed_urls: set[str] = set()
_last_feed_refresh = 0.0
_last_feed_error: str | None = None
_is_refreshing = False


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=1, max_length=MAX_URL_LENGTH)

    @field_validator("url")
    @classmethod
    def validate_url(cls, value: str) -> str:
        value = value.strip()
        if not value.startswith(("http://", "https://")):
            value = "https://" + value
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
    reason: str | None = None


def normalize_feed_url(value: str) -> str | None:
    """Normalize feed entries for comparison."""
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


def _do_feed_fetch() -> None:
    global _feed_urls, _last_feed_refresh, _last_feed_error, _is_refreshing

    try:
        request = Request(
            FEED_URL,
            headers={"User-Agent": "PhishX/1.2"},
        )
        with urlopen(request, timeout=FEED_TIMEOUT_SECONDS) as response:
            feed_text = response.read().decode("utf-8", errors="replace")

        urls = {
            normalized
            for line in feed_text.splitlines()
            if (normalized := normalize_feed_url(line))
        }

        if urls:
            with _feed_lock:
                _feed_urls = urls
                _last_feed_refresh = time.time()
                _last_feed_error = None
    except (OSError, URLError, TimeoutError, ValueError) as error:
        with _feed_lock:
            _last_feed_error = f"{type(error).__name__}: {error}"
            _last_feed_refresh = time.time()
    finally:
        with _feed_lock:
            _is_refreshing = False


def trigger_feed_refresh() -> None:
    """Trigger background refresh if due, without blocking active predictions."""
    global _is_refreshing

    with _feed_lock:
        if _is_refreshing:
            return
        if _feed_urls and time.time() - _last_feed_refresh < FEED_REFRESH_SECONDS:
            return
        _is_refreshing = True

    thread = threading.Thread(target=_do_feed_fetch, daemon=True)
    thread.start()


@app.on_event("startup")
def startup_event():
    trigger_feed_refresh()


@app.get("/health")
def health() -> dict[str, str]:
    model_status = "loaded" if _model_bundle is not None else "unavailable"
    with _feed_lock:
        feed_status = f"{len(_feed_urls)} entries" if _feed_urls else "not_loaded"
    return {
        "status": "ok",
        "model": model_status,
        "feed": feed_status,
    }


@app.post("/predict", response_model=PredictResponse)
def predict(request: PredictRequest) -> PredictResponse:
    trigger_feed_refresh()

    with _feed_lock:
        feed_snapshot = set(_feed_urls)

    verdict, confidence, reason = classify_url(
        url=request.url,
        model_bundle=_model_bundle,
        openphish_urls=feed_snapshot,
    )

    return PredictResponse(
        verdict=verdict,
        confidence=confidence,
        reason=reason,
    )
