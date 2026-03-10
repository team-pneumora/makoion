# Desktop Companion

`projects/apps/desktop-companion` is a minimal local HTTP endpoint for the Android `Direct HTTP` bridge mode.

Current scope:
- JDK-only local server using `com.sun.net.httpserver.HttpServer`
- `GET /health` readiness endpoint
- `POST /api/v1/transfers` receiver for Android transfer manifests
- `POST /api/v1/transfers` zip archive receiver for Android direct HTTP payload uploads, including streaming archive mode for large batches
- Optional trusted-secret validation through `X-MobileClaw-Trusted-Secret`
- Inbox persistence that materializes each accepted transfer into its own folder
- Per-transfer `manifest.json`, `summary.txt`, extracted file payloads, `received.txt`, and placeholder entries when the request is manifest-only
- JSON responses include versioned delivery metadata so Android can validate companion receipts in the outbox
- Debug validation modes are available through `X-MobileClaw-Debug-Receipt-Mode` (`partial_receipt`, `malformed_receipt`, `retry_once`, `delayed_ack`, `timeout_once`, `disconnect_once`) for transport testing

Run on Windows:
```powershell
cd projects/apps/desktop-companion
.\run-companion.ps1
```

Validate debug fault modes locally:
```powershell
cd projects/apps/desktop-companion
pwsh -NoProfile -File scripts\validate-fault-modes.ps1
```

Prepare a physical Android device for `adb reverse` transport validation:
```powershell
cd projects/apps/desktop-companion
pwsh -NoProfile -File scripts\prepare-adb-reverse.ps1
```

Optional environment variables:
- `MOBILECLAW_COMPANION_HOST`
- `MOBILECLAW_COMPANION_PORT`
- `MOBILECLAW_COMPANION_INBOX`
- `MOBILECLAW_COMPANION_SECRET`

Notes:
- Android debug builds default Direct HTTP devices to `http://10.0.2.2:8787/api/v1/transfers`, which maps the Android emulator to the host machine.
- For physical Android devices over USB, run `scripts\prepare-adb-reverse.ps1` and then choose `Use adb reverse localhost` from the Devices tab.
- If you set `MOBILECLAW_COMPANION_SECRET`, use the same QR secret that was shown when the device pairing session was approved.
- JSON manifest-only requests are still supported and materialize placeholder files when Android cannot resolve source URIs for binary upload.
- Archive uploads may be tagged as `archive_zip` or `archive_zip_streaming`, and the companion echoes that delivery mode back in its receipt payload.
