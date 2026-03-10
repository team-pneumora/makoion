# Android Shell

`projects/apps/android` is the native Android shell for Phase 1.

Current scope:
- Kotlin + Jetpack Compose application shell
- Overview, Files, Approvals, and Devices surfaces
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
- Paired devices can now arm validation fault modes (`partial`, `malformed`, `retry once`, `delayed ack`, `timeout once`, `disconnect once`) directly from the Devices tab when testing direct HTTP behaviour
- Direct HTTP device cards now expose endpoint presets for `adb reverse localhost` and `emulator host`, so the same shell can switch between physical-device and emulator validation targets
- Devices tab now includes manual bridge controls for refresh, immediate outbox drain, and failed-draft requeue during transport testing
- Devices tab now exposes retry timing, receipt-review issues, and a transport audit trace so bridge validation state can be inspected without leaving the shell
- Devices tab can now probe the selected Direct HTTP companion health endpoint and surface the latest result alongside bridge diagnostics
- Transfer drafts now recover stale `Sending` states, apply per-draft backoff, and schedule delayed retries for retryable HTTP/network failures
- Gradle wrapper included so the app can be built locally once Android SDK is configured

Planned next:
- richer background refresh and resilient task recovery
- pull-based companion recovery for transfers that still cannot stream binary payloads
- on-device validation of the delete consent path across MediaStore and SAF edge cases
- delayed-ack, timeout, and malformed receipt scenarios on real device / emulator transport paths
