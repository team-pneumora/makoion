# Android Shell

`projects/apps/android` is the native Android shell for Phase 1.

Current scope:
- Kotlin + Jetpack Compose application shell
- Chat, Dashboard, History, and Settings product surfaces
- Advanced capability tools for file/device validation now live inside Settings and stay collapsed by default
- Chat now exposes quick-start prompts that submit real agent turns for refresh, summarize, organize, dashboard routing, companion health/notification/workflow probes, and companion-opening flows
- Companion target selection now defaults to the newest Direct HTTP device, while tapping a device in Settings pins it until the same card is tapped again to return to auto-select
- Assistant messages in Chat now link the current turn task back into the conversation so linked task/approval context, follow-up guidance, and inline approve/deny/retry actions stay visible after each turn
- Transfer and companion chat contexts now route follow-up actions such as `health`, `session.notify`, `workflow.run`, `latest action`, `latest transfer`, `companion inbox`, and `actions folder` back through real chat turns instead of bypassing the task runtime
- Settings bridge controls still keep direct remote-action buttons for diagnostics, but those actions now sit behind an extra collapsed toggle so Chat remains the primary surface
- MediaStore-backed local file indexing entry point with runtime permission flow
- SAF document-root attachment and document tree indexing
- Approval inbox actions backed by local SQLite persistence
- Local audit trail surfaced in the shell after approvals and execution events
- Quick actions notification and SpeechRecognizer-based foreground voice transcription
- File graph action panel with local preview, summarize, dry-run organize planning, and Android share execution
- Dry-run organize plans can now be submitted into the approval inbox and execute into managed MediaStore folders after approval
- Organize execution now verifies destination copies and distinguishes moved, copied-only, delete-consent-required, and failed outcomes in the shell
- Delete-consent-required organize results can now open the actual Android delete consent prompt and reconcile the execution summary afterward
- Pairing session UI, paired-device selection, and local send-to-device transfer draft queue
- WorkManager-backed bridge drain that advances transfer drafts in the background and on app restart
- Device-level bridge mode controls for loopback fallback and direct HTTP companion transport
- Direct HTTP companion uploads now stream archive payloads for actual file materialization on the desktop seed
- Transfer receipts are persisted back into the outbox, with large or unknown-size payloads upgrading to streaming zip delivery and unresolved payloads still falling back to manifest-only
- Direct HTTP receipts now carry versioned metadata, file counts, and receipt validation hints so malformed or partial acknowledgements surface as review-needed drafts
- Paired devices can now arm validation fault modes (`partial`, `malformed`, `retry once`, `delayed ack`, `timeout once`, `disconnect once`) directly from Settings advanced tools when testing direct HTTP behaviour
- Direct HTTP device cards now expose endpoint presets for `adb reverse localhost` and `emulator host`, so the same shell can switch between physical-device and emulator validation targets
- Settings advanced tools now include manual bridge controls for refresh, immediate outbox drain, and failed-draft requeue during transport testing
- Settings advanced tools now expose retry timing, receipt-review issues, and a transport audit trace so bridge validation state can be inspected without leaving the shell
- Settings advanced tools can now probe the selected Direct HTTP companion health endpoint and surface the latest result alongside bridge diagnostics
- Direct HTTP companion health probes now refresh the paired device's advertised capability snapshot from `/health`
- Chat and Settings advanced tools can now trigger companion `health`, `session.notify`, `app.open`, and allowlisted `workflow.run` desktop companion probes from the phone
- `app.open` now supports `inbox`, `latest_transfer`, `actions_folder`, and `latest_action` targets from Settings advanced tools
- Allowlisted `workflow.run` now covers `open_latest_transfer`, `open_actions_folder`, and `open_latest_action`
- Settings advanced tools now gate remote companion actions on the latest advertised capability snapshot and prompt a health check when the snapshot is empty
- App foreground entry now re-runs a shell recovery coordinator that refreshes approvals, devices, organize executions, and audit state while re-arming transfer recovery
- Settings advanced tools now surface the latest shell recovery result so foreground/manual recovery can be validated without leaving the app
- `Refresh device state` now routes through that same shell recovery path instead of a narrower device-only refresh
- Settings advanced tools now also surface the latest quick-actions notification button action so `Voice` / `Approvals` can be validated without digging through the audit list
- Manual shell recovery now writes an audit event so recovery validation is also preserved in the Approval/Audit surface
- Dashboard now exposes a top-level `Jump to audit trail` shortcut and latest-audit summary so long approval lists do not hide recovery/audit evidence
- Transfer background drain now writes summary / worker retry / worker failure audit events so delayed retry recovery can be verified after the fact
- Debug builds now expose an adb-triggerable transport receiver plus `scripts\bootstrap-transport-validation.ps1` for faster physical-device validation setup
- Debug builds now expose `scripts\validate-direct-http-drafts.ps1` for repeatable end-to-end synthetic draft validation across Direct HTTP fault modes
- Debug builds now expose `scripts\validate-direct-http-archives.ps1` for repeatable real-byte archive payload validation across `archive_zip` and `archive_zip_streaming`
- Debug builds now expose `scripts\validate-direct-http-archive-faults.ps1` for repeatable real-byte archive fault-mode validation across `archive_zip` and `archive_zip_streaming`
- Debug builds now expose `scripts\validate-direct-http-companion-actions.ps1` for repeatable `session.notify`, `app.open`, and allowlisted `workflow.run` validation against the desktop companion
- Debug builds now expose `scripts\validate-shell-recovery.ps1` for repeatable stale sending, due retry, and delayed retry shell-recovery validation against the desktop companion
- Debug builds now expose `scripts\validate-shell-lifecycle-recovery.ps1` for repeatable process-death and background/foreground lifecycle recovery validation against the desktop companion
- Debug builds now expose `scripts\validate-shell-recovery-soak.ps1` for repeatable multi-iteration or duration-based smoke/soak validation across manual shell recovery and lifecycle recovery paths, with per-check timeouts plus persisted artifacts/summary output
- Debug validation cleanup now removes the temporary paired device, pairing session, and transfer drafts created by recovery validation runs so repeated soak passes do not keep inflating local shell state
- Debug builds now also ship a debug-only cleartext network security config so `127.0.0.1` and `10.0.2.2` Direct HTTP companion probes work during adb reverse / emulator validation
- Debug builds now expose a debug-only FileProvider-backed archive payload generator so archive transport can be validated without depending on MediaStore fixtures
- Physical-device `adb reverse -> bootstrap -> Devices health probe` validation was confirmed on 2026-03-10 against a Samsung Android handset
- Physical-device synthetic manifest-only draft validation was confirmed on 2026-03-10 for `normal`, `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, and `delayed_ack -> normal recovery`
- Physical-device archive payload validation was confirmed on 2026-03-10 for:
  - `archive_zip` with 2 x 64 KiB payload files
  - `archive_zip_streaming` with 1 x 18 MiB payload file
  - companion inbox materialization (`files/`, `manifest.json`, `summary.txt`, `received.txt`)
- Physical-device archive payload fault-mode validation was confirmed on 2026-03-10 for:
  - `archive_zip`: `normal`, `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, `delayed_ack`
  - `archive_zip_streaming`: `normal`, `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, `delayed_ack`
- Physical-device `validate-direct-http-companion-actions.ps1` validation was confirmed on 2026-03-12 for:
  - `session.notify`
  - `app.open` `record_only` across `inbox`, `latest_transfer`, and `actions_folder`
  - `app.open` `best_effort` across `inbox`, `latest_transfer`, and `actions_folder`
  - `workflow.run` (`record_only`) across `open_latest_transfer` and `open_actions_folder`
- Physical-device `validate-direct-http-companion-actions.ps1` validation was confirmed on 2026-03-13 for:
  - `session.notify` with desktop notification display confirmed through companion summary and Android audit `delivered`
  - `workflow.run` `best_effort` across `open_latest_transfer` and `open_actions_folder`, with companion summary `Executed: true` and Android audit `completed`
- Physical-device `validate-direct-http-companion-actions.ps1` validation was confirmed on 2026-03-14 for:
  - `app.open` `record_only` and `best_effort` across `latest_action`, with companion summary `Target label: Latest action folder` and Android audit `recorded` / `opened`
  - `workflow.run` `record_only` and `best_effort` across `open_latest_action`, with companion summary `Workflow label: Open latest action` and Android audit `recorded` / `completed`
- Physical-device delete-consent rejection surface validation was confirmed on 2026-03-13 for:
  - Files action console `Request Android delete consent (8)` button plus persisted rejection note
  - workflow snapshot `Organize copied files but still needs Android delete consent` with `Action needed`
  - `Latest organize execution` card summary, persisted rejection note, and per-entry delete-consent detail
- Physical-device `validate-shell-recovery.ps1` validation was confirmed on 2026-03-12 for stale `Sending`, due retry, and delayed retry recovery against the desktop companion over `adb reverse`
- Physical-device `validate-shell-lifecycle-recovery.ps1` validation was confirmed on 2026-03-12 for process-death stale sending recovery, background/foreground due retry resilience, and process-death delayed retry recovery over `adb reverse`
- Physical-device `validate-shell-recovery-soak.ps1` smoke validation was confirmed on 2026-03-12 for 2 combined iterations of manual shell recovery plus lifecycle recovery over `adb reverse`
- Physical-device `validate-shell-recovery-soak.ps1` post-cleanup validation was confirmed on 2026-03-14 for 8 combined iterations of manual shell recovery plus lifecycle recovery over `adb reverse`, while paired-device / pairing-session / transfer-outbox counts remained flat after each cleanup
- Physical-device `validate-shell-recovery-soak.ps1` 30-minute duration rerun was confirmed on 2026-03-14 for 19 iterations / 37 checks / 0 failures over `adb reverse`
- `scripts\\validate-shell-recovery.ps1` now suppresses debug-command auto-open during adb-driven recovery validation so stale-sending manual recovery no longer races with an unintended foreground recovery
- Physical-device USB reconnect can clear `adb reverse`; if chat-triggered `Check companion health`, `Send notification`, or `workflow.run` suddenly fail with `127.0.0.1:8787` connection errors, rerun `adb reverse tcp:8787 tcp:8787` or `scripts\bootstrap-transport-validation.ps1 -EndpointPreset adb_reverse`
- The foreground due-retry smoke path now backgrounds the app before queueing the draft so the `Queued -> foreground -> Delivered` transition stays stable across repeated runs
- Transfer drafts now recover stale `Sending` states, apply per-draft backoff, and schedule delayed retries for retryable HTTP/network failures
- Gradle wrapper included so the app can be built locally once Android SDK is configured
- When the repo lives under OneDrive on Windows, Gradle build outputs are redirected to `%LOCALAPPDATA%\\Makoion\\android-gradle-build` by default to avoid sync-lock failures; set `MAKOION_ANDROID_BUILD_ROOT` to override that location

Planned next:
- multi-hour physical-device soak validation of foreground recovery and retry/task restoration using the duration-based `scripts\validate-shell-recovery-soak.ps1` harness
- additional `workflow.run` allowlist expansion and follow-up `app.open` target polish beyond `open_latest_action`
- pull-based companion recovery for transfers that still cannot stream binary payloads
- delete-consent/source-delete edge-case coverage across MediaStore and SAF mixed-source batches

Long-run soak example:

```powershell
pwsh -NoProfile -File projects/apps/android/scripts/validate-shell-recovery-soak.ps1 `
  -Serial <adb-serial> `
  -EndpointPreset adb_reverse `
  -DurationMinutes 240 `
  -Iterations 0 `
  -StepTimeoutMinutes 20 `
  -ArtifactDirectory results/shell-recovery-soak-20260314 `
  -SummaryPath results/shell-recovery-soak-20260314/summary.json
```

- `Iterations 0` removes the iteration cap when a duration-based soak run is used.
- If `SummaryPath` is omitted, the script now writes `summary.json` into the artifact directory automatically.
