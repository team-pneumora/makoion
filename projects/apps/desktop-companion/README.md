# Desktop Companion

`projects/apps/desktop-companion` is a minimal local HTTP endpoint for the Android `Direct HTTP` bridge mode.

Current scope:
- JDK-only local server using `com.sun.net.httpserver.HttpServer`
- `GET /health` readiness endpoint
- `GET /api/v1/mcp/discovery` MCP connector discovery endpoint for Android chat-first resource sync, including tool schemas, skill bundles, and workflow inventory
- `POST /api/v1/transfers` receiver for Android transfer manifests
- `POST /api/v1/session/notify` receiver for desktop notification probes
- `POST /api/v1/app/open` receiver for `inbox`, `latest_transfer`, and `actions_folder` probes
- `POST /api/v1/workflow/run` receiver for allowlisted desktop workflow probes (`open_latest_transfer`, `open_actions_folder`)
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

Validate `session.notify` local display/materialization:
```powershell
cd projects/apps/desktop-companion
pwsh -NoProfile -File scripts\validate-session-notify.ps1
```

Validate `workflow.run` locally:
```powershell
cd projects/apps/desktop-companion
pwsh -NoProfile -File scripts\validate-workflow-run.ps1 -WorkflowId open_latest_transfer -RunMode best_effort
```

Inspect MCP discovery locally:
```powershell
Invoke-WebRequest http://127.0.0.1:8787/api/v1/mcp/discovery | Select-Object -ExpandProperty Content
```

Validate `app.open` targets locally:
```powershell
cd projects/apps/desktop-companion
pwsh -NoProfile -File scripts\validate-app-open.ps1 -TargetKind latest_transfer -OpenMode best_effort
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

Bootstrap the debug Android shell against that reversed endpoint:
```powershell
cd projects/apps/android
pwsh -NoProfile -File scripts\bootstrap-transport-validation.ps1
```

Run repeatable synthetic draft validation across Direct HTTP fault modes:
```powershell
cd projects/apps/android
pwsh -NoProfile -File scripts\validate-direct-http-drafts.ps1
```

Run repeatable archive payload validation across small zip and streaming zip deliveries:
```powershell
cd projects/apps/android
pwsh -NoProfile -File scripts\validate-direct-http-archives.ps1
```

Run repeatable archive payload fault-mode validation across zip and streaming zip deliveries:
```powershell
cd projects/apps/android
pwsh -NoProfile -File scripts\validate-direct-http-archive-faults.ps1
```

Optional environment variables:
- `MOBILECLAW_COMPANION_HOST`
- `MOBILECLAW_COMPANION_PORT`
- `MOBILECLAW_COMPANION_INBOX`
- `MOBILECLAW_COMPANION_SECRET`

Notes:
- Android debug builds default Direct HTTP devices to `http://10.0.2.2:8787/api/v1/transfers`, which maps the Android emulator to the host machine.
- Android debug builds also enable a debug-only cleartext network security config so `127.0.0.1` and `10.0.2.2` companion traffic can be probed without HTTPS during transport validation.
- For physical Android devices over USB, run `scripts\prepare-adb-reverse.ps1` and then choose `Use adb reverse localhost` from the Devices tab.
- On 2026-03-10, `scripts\prepare-adb-reverse.ps1` together with Android `scripts\bootstrap-transport-validation.ps1` was verified on a physical Android device up to successful `/health` probing.
- On 2026-03-10, Android `scripts\validate-direct-http-drafts.ps1` also verified synthetic manifest-only draft sends for `normal`, `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, and `delayed_ack` recovery against this companion.
- On 2026-03-10, Android `scripts\validate-direct-http-archives.ps1` verified both `archive_zip` and `archive_zip_streaming` payloads against this companion, including extracted `files/`, `manifest.json`, `summary.txt`, and `received.txt`.
- On 2026-03-10, Android `scripts\validate-direct-http-archive-faults.ps1` verified real-byte archive fault modes against this companion for `archive_zip` and `archive_zip_streaming`, including `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, and `delayed_ack`.
- On 2026-03-12, Android `scripts\validate-direct-http-companion-actions.ps1` verified `app.open` in both `record_only` and `best_effort` modes across `inbox`, `latest_transfer`, and `actions_folder`.
- On 2026-03-13, local `scripts\validate-session-notify.ps1` verified `notification_displayed=true` together with companion action materialization.
- On 2026-03-13, local `scripts\validate-workflow-run.ps1` verified `best_effort` execution for both `open_latest_transfer` and `open_actions_folder`.
- On 2026-03-13, Android `scripts\validate-direct-http-companion-actions.ps1` verified `session.notify` desktop notification display plus `workflow.run` `best_effort` execution across `open_latest_transfer` and `open_actions_folder`.
- If you set `MOBILECLAW_COMPANION_SECRET`, use the same QR secret that was shown when the device pairing session was approved.
- JSON manifest-only requests are still supported and materialize placeholder files when Android cannot resolve source URIs for binary upload.
- Archive uploads may be tagged as `archive_zip` or `archive_zip_streaming`, and the companion echoes that delivery mode back in its receipt payload.
