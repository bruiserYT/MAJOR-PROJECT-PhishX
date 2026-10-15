# PhishX

Privacy-conscious phishing URL checking monorepo. The Android app sends only the submitted URL to the configured API; the API disables access logs and does not persist submitted URLs. Network enrichment is time-bounded and optional. No extension source was present in the initial workspace; place it in `extension/` and update its API base URL to the deployed `/predict` endpoint.

## Backend

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log
```

`POST /predict` accepts `{"url":"https://example.com"}` and returns `{"verdict":"safe|phishing","confidence":0.0}`. `GET /health` returns service health. The baseline detector is a transparent heuristic, not a trained production model. `PHISHX_LOOKUP_TIMEOUT_SECONDS` limits optional DNS/WHOIS enrichment. No raw URL logging or persistence is configured.

Build/run container: `docker build -t phishx-api ./backend` then `docker run --rm -p 8000:8000 phishx-api`. For local API checks use `http://127.0.0.1:8000` (Android emulator: `http://10.0.2.2:8000`). Public deployment requires your Render/Cloud Run account and a configured HTTPS service URL; set that URL in Android `BuildConfig.API_BASE_URL` before release.

## Android

Open `android/` in Android Studio (Android Gradle Plugin project, min SDK 26). The default debug endpoint uses emulator loopback and permits cleartext only in debug builds. Features include share/paste URL extraction, Room SHA-256 cache (never raw URLs), interceptor confirmation, opt-in notification listener with phishing-only alerts, settings, and allowlisting. Notification listener access and default link-handler role must be enabled by the user in Android settings. To use a physical phone, set `API_BASE_URL` to your laptop LAN address and use the debug network security config only on a trusted network, or use HTTPS.

## Limits / evaluation

The included detector is a starter heuristic and can produce false positives/negatives. Do not represent its output as authoritative. Add a validated model and assess against a dated, labeled dataset before deployment. The repository does not include a signed release key, cloud credentials, Android Studio/emulator, or physical device. Avoid opening known malicious URLs; submit them only to the API during evaluation.
