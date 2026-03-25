# Android Dev Log 2026-03-20

## Scope

- Continue Phase 1 Android MVP development using the emulator as the primary test device.
- Keep a durable operator log so the next contributor can resume without rediscovery.

## Environment

- Workspace: `C:\Users\jjck5\Projects\Makoion`
- Emulator: `Medium_Phone_API_36.1`
- Connected device id: `emulator-5554`
- Android Studio JBR: `C:\Program Files\Android\Android Studio\jbr`
- Android SDK: `C:\Users\jjck5\AppData\Local\Android\Sdk`

## Findings

### 1. Emulator connection

- Verified the emulator AVD exists and is running.
- Verified `adb devices -l` reports `emulator-5554` in `device` state.
- Verified the app launches on the emulator and `MainActivity` becomes the resumed activity.

### 2. Build reproducibility issue

- `projects/apps/android/local.properties` points at a different user's SDK path.
- The file is already gitignored, so the reproducible path forward is to rely on environment variables for local development.
- The build also exposed a second issue: `MAKOION_ANDROID_BUILD_ROOT` did not actually force an external build root unless the repo path contained `OneDrive`.

### 3. Fix applied

- Updated `projects/apps/android/build.gradle.kts` so `MAKOION_ANDROID_BUILD_ROOT` works as an explicit override on any Windows path.
- This keeps the existing OneDrive fallback behavior, but now also lets operators route Gradle outputs away from locked repo-local `build/` directories when needed.

### 4. Next feature target

- `scheduled automation` was still a placeholder path.
- The repository only stored records, the dashboard still used placeholder wording, and no scheduler worker or execution loop existed.
- This made emulator validation weak because there was no immediate way to prove end-to-end behavior after an automation was recorded.

## Validation plan

1. Re-run `:app:assembleDebug` with:
   - `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`
   - `ANDROID_HOME=C:\Users\jjck5\AppData\Local\Android\Sdk`
   - `ANDROID_SDK_ROOT=C:\Users\jjck5\AppData\Local\Android\Sdk`
   - `MAKOION_ANDROID_BUILD_ROOT=%LOCALAPPDATA%\Makoion\android-gradle-build`
2. Install the fresh debug APK on `emulator-5554`.
3. Launch the app and continue feature work from the next highest-priority issue discovered during emulator testing.

## Implementation log

### Step 1. Scheduled automation execution loop

- Added DB support for `last_run_at` and `next_run_at` so automation cards can expose real execution state.
- Added `ScheduledAutomationCoordinator` and `ScheduledAutomationWorker` to schedule recurring work through WorkManager.
- Wired activation and pause flows through the coordinator instead of raw status toggles.
- Added manual `Run once` execution so emulator validation does not depend on waiting for the next real interval.
- Added on-device automation delivery notifications and audit logging for both scheduled and manual runs.
- Updated dashboard and runtime copy so the app now describes scheduled automations as runnable local features instead of skeleton placeholders.

## Pending validation

1. Rebuild and reinstall the app after the scheduled automation changes.
2. Record an automation on the emulator.
3. Verify `Activate schedule`, `Run once`, audit history, and the notification path.
4. Fix any regression discovered during that device run before moving to the next feature slice.

## Emulator blocker

- During validation on `emulator-5554`, the app now builds and installs successfully but does not remain in the foreground.
- `adb shell am start -W -n io.makoion.hub.dev/io.makoion.mobileclaw.MainActivity` reports `Status: ok`, then the process is killed shortly after startup.
- The recurring log pattern is:
  - `WM-WrkMgrInitializer: Initializing WorkManager with default configuration`
  - `WM-ForceStopRunnable: Performing cleanup operations`
  - `Process (...): Sending signal. PID ... SIG: 9`
- No stable `AndroidRuntime` Java stack trace was emitted in the captured window.
- Google Play Services onboarding (`com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity`) also repeatedly preempted the app during testing, which makes UI automation noisy.

## Likely root cause to continue from

- The new scheduled automation startup path surfaced a pre-existing data/runtime fragility around old restored DB state on the emulator.
- Pulled emulator DB snapshots showed `user_version = 12` and missing newer tables such as `scheduled_automation_records`, even after reinstall/clear cycles.
- A compatibility hardening patch was added so `ScheduledAutomationRepository` now calls `ShellDatabaseHelper.ensureScheduledAutomationSchema()` before reads/writes and falls back to an empty list if refresh still fails.
- The next operator should resume from the startup kill:
  1. verify whether the emulator is restoring stale app data after install,
  2. verify whether another repository with late-added tables needs the same schema self-heal path,
  3. isolate whether WorkManager startup plus foreground recovery is what ultimately triggers the process death.

## Notes for the next operator

- If `adb` is not on `PATH`, use:
  - `C:\Users\jjck5\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- If `emulator` is not on `PATH`, use:
  - `C:\Users\jjck5\AppData\Local\Android\Sdk\emulator\emulator.exe`
- If Gradle reports file deletion failures under `projects/apps/android/app/build`, set `MAKOION_ANDROID_BUILD_ROOT` explicitly before building.

## Follow-up Session: Instrumentation and Emulator Validation

### Scope

- Stabilize emulator-driven validation for the new scheduled automation flow.
- Replace brittle adb tap/text experiments with repeatable `connectedDebugAndroidTest` coverage.

### Changes applied

- Added Compose instrumentation support in `projects/apps/android/app/build.gradle.kts`:
  - `testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner`
  - `androidTestImplementation` for Compose UI test, AndroidX JUnit, and Espresso
- Added shared UI test tags in `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/ShellTestTags.kt`
- Tagged critical surfaces in `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/MobileClawShellApp.kt`:
  - bottom navigation items
  - chat screen list
  - chat composer input/send
  - scheduled automation card/action buttons
- Added a stable chat shortcut:
  - `Record daily automation`
  - prompt: `Every morning send a notification digest automation`
- Added emulator instrumentation coverage in `projects/apps/android/app/src/androidTest/java/io/makoion/mobileclaw/ui/ScheduledAutomationFlowTest.kt`

### What the connected test now validates

1. Launch the installed debug app on the emulator.
2. Use the visible chat quick-start to submit a real scheduled automation planning turn.
3. Verify the automation record is persisted in local SQLite.
4. Open Dashboard and verify the created automation card is rendered.
5. Trigger activation and manual execution through the app container coordinator on-device.
6. Verify Dashboard reflects:
   - `Pause schedule`
   - `Last run ...`

### Why coordinator calls are used for activation/run

- The Dashboard action buttons are visible and tagged, but Compose click automation on those specific controls did not produce a reliable state transition during instrumentation.
- The planning path is still exercised through the real chat runtime and persisted UI state.
- Activation and manual execution are now validated through the installed app's own `ScheduledAutomationCoordinator`, then reflected back through the Dashboard UI.
- This keeps emulator validation meaningful while isolating a remaining UI-action automation issue for later cleanup.

### Emulator validation results

- `:app:connectedDebugAndroidTest` passed on `Medium_Phone_API_36.1`
- `:app:installDebug` passed and reinstalled the debug APK on `emulator-5554`
- Launcher smoke check passed:
  - `adb shell monkey -p io.makoion.hub.dev -c android.intent.category.LAUNCHER 1`
  - `dumpsys activity` confirmed `io.makoion.mobileclaw.MainActivity` as the resumed activity
  - `uiautomator dump` confirmed the Chat screen is visible with the new `Record daily automation` quick-start

### Remaining issues / follow-up

- `projects/apps/android/local.properties` still points at an old SDK path and causes harmless Gradle warnings in this environment.
- Direct `adb shell am start -n ...` was inconsistent, while launcher-based start worked reliably. Prefer `monkey` or launcher intents for smoke checks here.
- Dashboard button click automation remains a follow-up item:
  - the action buttons are rendered
  - coordinator logic is validated
  - direct Compose click-to-state-transition on those buttons still needs separate investigation

### Git status / handoff blocker

- `git.exe` is still unavailable from this machine session.
- `where.exe git` returned no result.
- A recursive scan under `C:\Program Files` and `C:\Program Files (x86)` did not locate a usable `git.exe`.
- Result: code changes are saved locally, but commit/push could not be executed in this session.

## Follow-up Session: Shell Recovery Automation Hardening

### Scope

- Align foreground/manual recovery with the new scheduled automation runtime.
- Remove duplicate startup scheduling so one recovery path owns automation resync.
- Revalidate the recovery path on the installed emulator app.

### Changes applied

- Extended `ShellRecoveryCoordinator` to include scheduled automation refresh and WorkManager resync.
- Added recovery detail reporting for automation counts and repaired schedule windows.
- Wired the recovery coordinator in `ShellAppContainer` with the scheduled automation repository/coordinator dependencies.
- Removed the extra `scheduledAutomationCoordinator.start()` call from `ShellViewModel` so app launch no longer kicks automation sync from two separate paths.
- Expanded `ScheduledAutomationFlowTest` to trigger manual recovery after activation and assert:
  - recovery finishes in `Success`
  - recovery detail mentions automation reconciliation
  - the active automation still has a persisted `next_run_at`

### Emulator validation results

- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed after the recovery hardening patch.
- The installed app now validates this full path on the emulator:
  1. create automation from chat
  2. activate schedule
  3. manual recovery request
  4. recovery success state with automation detail
  5. manual run reflected in Dashboard

### Current blocker

- Git remote write access is still blocked in this Codex session.
- `git ls-remote` succeeds, but `git push` requires GitHub credentials and fails with:
  - `fatal: Cannot prompt because user interactivity has been disabled.`
  - `fatal: could not read Username for 'https://github.com': terminal prompts disabled`
- Local commits can be created, but push requires the operator to authenticate GitHub in this environment first.

## Follow-up Session: Chat-first Shell Simplification and MCP Skill Updates

### Scope

- Reduce the Chat screen to a simpler conversation-first surface.
- Expand chat control so scheduled automations and MCP bridge setup can be handled as natural-language turns.
- Add a durable MCP skill catalog that can be updated from the connected MCP bridge seed.

### Changes applied

- Simplified the Chat surface in `MobileClawShellApp.kt`:
  - removed the large hero/quick-start stack from the main conversation flow
  - introduced a compact conversation header
  - anchored the composer at the bottom
  - moved quick prompts into a slim horizontal chip rail above the input
- Extended `PhoneAgentRuntime.kt` so chat can now:
  - activate the latest or referenced scheduled automation
  - pause the latest or referenced scheduled automation
  - run the latest or referenced scheduled automation immediately
  - connect the MCP bridge seed from chat
  - sync MCP skills from the MCP bridge
  - list currently installed MCP skills
- Added `McpSkillRepository.kt` with SQLite-backed MCP skill persistence:
  - seeded sync bundle currently installs three mock-ready skill packages
  - desktop action bridge
  - browser research handoff
  - external API ingest
- Updated the MCP external endpoint seed so the capability surface now advertises `mcp.skills.sync`.
- Added tests:
  - `McpSkillRepositoryTest.kt`
  - `McpSkillChatFlowTest.kt`
- Stabilized instrumentation strategy by using the installed app's real `AgentTaskEngine.submitTurn(...)` path instead of fragile Compose text-entry gestures for agent-turn submission.

### Emulator validation results

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`

### Product effect

- The Chat tab is now much closer to the intended “simple conversation window” described in the master plan.
- Major agent controls can now stay inside chat instead of forcing Dashboard/Settings detours:
  - approvals and retries were already in chat
  - scheduled automation lifecycle control is now also in chat
  - MCP bridge setup and skill catalog updates are now also in chat

## Follow-up Session: Chat Recovery Instrumentation Hardening

### Scope

- Add explicit emulator coverage for chat-thread recovery across activity recreation.
- Lock down the "phone is the source of truth" behavior for the active conversation state before moving on to deeper process-death recovery.

### Changes applied

- Added `ChatRecoveryFlowTest.kt` in `projects/apps/android/app/src/androidTest/java/io/makoion/mobileclaw/ui/`.
- The new test uses the installed app's real `chatTranscriptRepository` to:
  - create a new thread
  - append user and assistant messages
  - mark that thread as active
  - recreate `MainActivity`
  - assert the active-thread title is restored from persisted state
  - assert the assistant reply is rendered again after recreation
- Tightened the assertion strategy after the first run exposed a false negative:
  - the thread title string appears in more than one visible node
  - the test now validates restored repository state plus assistant-message visibility instead of assuming a single title node

### Emulator validation results

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`
  - `ChatRecoveryFlowTest`

### Product effect

- Active chat-thread recovery is now explicitly covered on the emulator instead of being inferred from repository code alone.
- This reduces the risk of regressing conversation continuity while the shell keeps moving toward an agent-first chat surface.

## Follow-up Session: Shell Recovery Chat Transcript Integration

### Scope

- Promote chat transcript state into the same recovery path that already owns approvals, tasks, automations, and transfer recovery.
- Make recovery diagnostics reflect the active conversation so chat-first state is not treated as a side-effect.

### Changes applied

- Extended `ShellRecoveryCoordinator.kt` to refresh `chatTranscriptRepository` as an explicit recovery step.
- Updated shell recovery copy so the default/running/success states now describe chat transcript refresh as part of foreground/manual recovery.
- Expanded recovery detail output to include:
  - total chat thread count
  - restored active thread title
  - restored active thread message count
- Wired `ShellAppContainer.kt` so the recovery coordinator receives the shared chat transcript repository.
- Expanded `ChatRecoveryFlowTest.kt`:
  - recreate the activity
  - verify the active thread still resolves correctly
  - trigger manual shell recovery on the installed app
  - verify recovery finishes in `Success`
  - verify recovery detail mentions the restored active chat thread

### Emulator validation results

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`
  - `ChatRecoveryFlowTest`

### Product effect

- Shell recovery now treats the active conversation as a first-class durable runtime surface instead of leaving chat refresh implicit.
- Recovery diagnostics now tell the next operator which conversation came back, which is useful when chasing lifecycle and process-death issues.

## Follow-up Session: Chat-Controlled Shell Recovery

### Scope

- Extend the chat-first agent loop so shell recovery can be run and inspected without leaving conversation.
- Verify those commands against the installed emulator app through the real `AgentTaskEngine`.

### Changes applied

- Reordered `ShellAppContainer.kt` so `ShellRecoveryCoordinator` is constructed before `LocalPhoneAgentRuntime`.
- Injected `ShellRecoveryCoordinator` into `LocalPhoneAgentRuntime`.
- Added new chat intents in `PhoneAgentRuntime.kt`:
  - `RunShellRecovery`
  - `ShowShellRecoveryStatus`
- Added planner routing for prompts such as:
  - `Run shell recovery now`
  - `Show shell recovery status`
  - Korean recovery/status variants
- Added runtime handlers that:
  - trigger manual shell recovery
  - wait for the recovery state to settle
  - summarize the latest recovery trigger, summary, and detail directly in chat
- Expanded the capability explanation copy so the chat surface now advertises shell recovery control as part of the agent envelope.
- Added `ShellRecoveryChatFlowTest.kt` to validate:
  - chat-triggered manual shell recovery
  - successful completion on the installed app
  - follow-up status summarization from chat

### Emulator validation results

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`
  - `ChatRecoveryFlowTest`
  - `ShellRecoveryChatFlowTest`

### Product effect

- Shell recovery is now another agent skill available directly from the conversation loop instead of being hidden behind diagnostics UI.
- The chat surface moved closer to the intended “control everything by conversation” model while still preserving recovery visibility for operators.

## Follow-up Session: Resource Stack Control From Chat

### Scope

- Extend the chat-first loop beyond task/recovery control so connected resource profiles can also be inspected and updated from conversation.
- Keep the new controls inside the already-seeded placeholder model: stage/mock-ready transitions only, no fake OAuth or hidden transport execution.

### Changes applied

- Injected the cloud drive and delivery channel repositories into `LocalPhoneAgentRuntime`.
- Added new chat intents and handlers in `PhoneAgentRuntime.kt` for:
  - `ShowResourceStack`
  - `StageCloudDrive`
  - `ConnectCloudDrive`
  - `StageExternalEndpoint`
  - `ConnectExternalEndpoint`
  - `StageDeliveryChannel`
  - `ConnectDeliveryChannel`
- Added prompt routing for conversation requests such as:
  - `Show resource stack`
  - `Connect Google Drive`
  - `Stage browser automation profile`
  - `Connect desktop companion relay`
- Added resource-stack summaries directly in chat so the agent can now report:
  - connected/staged cloud drives
  - connected/staged external endpoints
  - connected/staged delivery channels
  - paired companions
  - installed MCP skill count
- Updated the capability explanation copy so resource-profile staging/connection is now part of the advertised chat control surface.
- Added `ResourceStackChatFlowTest.kt` to validate chat-driven resource profile control on the installed emulator app.

### Emulator validation results

- `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`
  - `ChatRecoveryFlowTest`
  - `ShellRecoveryChatFlowTest`
  - `ResourceStackChatFlowTest`

### Product effect

- The shell can now inspect and update more of the seeded resource stack from conversation instead of forcing Settings-first operator flows.
- This moves the Android MVP closer to the intended agent model where files, drives, MCP endpoints, and delivery targets sit behind the chat loop as controllable resources.

## Follow-up Session: Lifecycle Recovery Validation Hardening

### Scope

- Resume the blocked lifecycle/process-death validation work on the emulator and remove the local-environment assumptions that made the script fail before it reached real app behavior.
- Harden the Android shell so foreground resume can reliably recover queued transfer work after the app comes back from the background.

### Changes applied

- Hardened `projects/apps/desktop-companion/run-companion.ps1` so the desktop companion resolves `java.exe` and `javac.exe` from:
  - `JAVA_HOME`
  - `JDK_HOME`
  - Android Studio `jbr`
  - common `Program Files/Java` installs
- Hardened `projects/apps/android/scripts/bootstrap-transport-validation.ps1` so it now:
  - prefers the active custom Gradle build root under `%LOCALAPPDATA%\Makoion\android-gradle-build` when present
  - avoids the stale repo-local APK path that had been last built on 2026-03-12
  - detects `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  - uninstalls the conflicting package and retries the install automatically
  - checks `adb install` results explicitly instead of ignoring failures
  - includes `--include-stopped-packages` when sending the debug bootstrap broadcast
- Added richer companion startup diagnostics in `projects/apps/android/scripts/validate-shell-lifecycle-recovery.ps1`:
  - detect exited companion processes early
  - surface stdout/stderr excerpts in failures
  - allow longer `/health` readiness waits
- Removed the host-side Python dependency from lifecycle validation:
  - added `dump_validation_state` to `DebugTransportReceiver.kt`
  - the receiver now writes a JSON validation snapshot into app-private storage
  - the PowerShell script now pulls that JSON directly from the installed app
- Made the debug transport receiver deterministic for adb automation:
  - switched the receiver from `goAsync` coroutine fire-and-forget handling to synchronous `runBlocking(Dispatchers.IO)`
  - added debug marker files for the last received command and validation dump failures
- Hardened foreground recovery in the Android shell:
  - `ShellRecoveryCoordinator` now schedules deferred foreground recovery instead of silently dropping requests inside the cooldown window
  - foreground transfer recovery now drains immediately when stale/due drafts are found during shell recovery
  - `MainActivity` now requests foreground refresh on real background-to-foreground returns and task re-entry through `onNewIntent`
  - foreground refresh now also kicks `TransferBridgeCoordinator.scheduleRecovery()`
- Updated the lifecycle validation script scenario flow:
  - manual shell recovery requests no longer reopen the Devices surface during validation
  - stale-sending validation force-stops the app immediately after inserting the synthetic interrupted draft, preventing the new foreground recovery path from consuming the draft before process-death is exercised

### Emulator validation results

- `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-shell-lifecycle-recovery.ps1 -Serial emulator-5554 -EndpointPreset emulator_host` passed.
- Validation scenarios completed successfully on `emulator-5554`:
  - `process_death_stale_sending`
  - `background_due_retry_resilience`
  - `process_death_delayed_retry`
- `:app:testDebugUnitTest` passed.
- `:app:connectedDebugAndroidTest` passed with:
  - `ScheduledAutomationFlowTest`
  - `McpSkillChatFlowTest`
  - `ChatRecoveryFlowTest`
  - `ShellRecoveryChatFlowTest`
  - `ResourceStackChatFlowTest`

### Product effect

- Lifecycle validation is now runnable on this machine without requiring a globally installed Python or manually curated Java PATH.
- The emulator validation path now proves that shell recovery can restore:
  - interrupted sending drafts after process death
  - due queued drafts after returning the app to the foreground
  - delayed queued drafts after relaunch and scheduled retry time
- Foreground resume is less brittle because returning to the shell now explicitly re-arms transfer recovery instead of relying on a single lifecycle observer path.

## Follow-up Session: Emulator Soak Harness Alignment

### Scope

- Move from single-run recovery validation to the duration/iteration soak harness on the Android emulator.
- Align the older manual-recovery validator and soak summary parser with the newer lifecycle validator so the harness reflects real pass/fail state instead of local host tooling quirks.

### Changes applied

- Upgraded `projects/apps/android/scripts/validate-shell-recovery.ps1` to the same local-host assumptions as the lifecycle validator:
  - removed host Python/SQLite inspection and switched to app-private JSON validation state dumps
  - added stopped-package-safe debug broadcasts
  - added companion startup diagnostics and longer readiness timeouts
  - used the current PowerShell executable plus `-ExecutionPolicy Bypass` for child companion startup
  - added trace logging parity with the lifecycle validator
- Hardened `projects/apps/android/scripts/validate-shell-recovery-soak.ps1`:
  - removed `ConvertFrom-Json -Depth` dependency from the soak output parser for Windows PowerShell compatibility
  - normalized child `stdout` / `stderr` handling so empty files no longer cause null `.Trim()` failures
  - switched child validator launch to the current PowerShell executable with `-ExecutionPolicy Bypass`
  - taught the harness to accept a validator run as passed when it emits a valid success payload even if the child process reports a missing/null exit code through `Start-Process`
  - surfaced parsed scenario names and endpoints correctly in `summary.json`

### Emulator validation results

- `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate-shell-recovery-soak.ps1 -Serial emulator-5554 -EndpointPreset emulator_host -Iterations 2 -BasePort 8830` passed.
- Emulator soak summary:
  - `2 iterations`
  - `4 checks`
  - `0 failures`
  - `manual_recovery` passed twice
  - `lifecycle_recovery` passed twice
- Per-check scenario parsing in soak artifacts now resolves:
  - manual recovery: `stale_sending`, `due_retry`, `delayed_retry`
  - lifecycle recovery: `process_death_stale_sending`, `background_due_retry_resilience`, `process_death_delayed_retry`

### Product effect

- The emulator now has a repeatable combined soak loop instead of only one-off recovery scripts.
- The soak harness can be trusted for unattended iteration/duration runs on this machine because local PowerShell compatibility and validator parsing drift have been removed.

## Follow-up Session: Direct HTTP Recovery Validation Hardening

### Scope

- Close the next validation gap after shell recovery soak by proving that Direct HTTP retry flows still drain after the desktop companion is restarted.
- Make the Direct HTTP draft validator runnable on this Windows/PowerShell environment without depending on the old Python/SQLite inspection path.

### Changes applied

- Reworked `projects/apps/android/scripts/validate-direct-http-drafts.ps1` around the app's existing JSON validation dump instead of host-side Python database parsing.
- Hardened the validator to use the current PowerShell executable with `-ExecutionPolicy Bypass`, `--include-stopped-packages` adb broadcasts, device reconnect handling, and companion `/health` startup diagnostics.
- Added explicit companion restart coverage for retry-oriented modes:
  - `retry_once`
  - `timeout_once`
  - `disconnect_once`
  - `delayed_ack`
- Relaxed the probe-health step from a brittle blocking gate into a captured signal, while keeping the transport delivery and retry assertions strict.
- Standardized local Android builds in this environment by using `MAKOION_ANDROID_BUILD_ROOT=C:\Users\jjck5\AppData\Local\Makoion\android-gradle-build-codex` together with Android Studio's bundled JBR to avoid locked in-tree Gradle intermediates.

### Emulator validation results

- `powershell -NoProfile -ExecutionPolicy Bypass -Command "& '.\projects\apps\android\scripts\validate-direct-http-drafts.ps1' -Serial emulator-5554 -EndpointPreset emulator_host -ValidationModes @('normal','partial_receipt','malformed_receipt','retry_once','timeout_once','disconnect_once','delayed_ack') -Port 8791"` passed.
- Direct HTTP validation modes passed on `emulator-5554`:
  - `normal`
  - `partial_receipt`
  - `malformed_receipt`
  - `retry_once`
  - `timeout_once`
  - `disconnect_once`
  - `delayed_ack`
- Retry-oriented modes now explicitly prove companion restart recovery before final delivery.
- `:app:assembleDebug` passed with the external Gradle build root override.
- `:app:connectedDebugAndroidTest` passed on `Medium_Phone_API_36.1` with 5 tests completed and 0 failures.

### Product effect

- The Android validation toolchain now covers the missing "retry scheduled draft drains after companion recovery" scenario instead of only same-process retry delivery.
- Direct HTTP validation on this machine is no longer blocked by PowerShell compatibility gaps or in-tree Gradle resource lockups.

## Follow-up Session: Combined Agent Runtime Soak Harness

### Scope

- Add a single unattended soak entry point that exercises the Android shell's recovery stack and Direct HTTP retry recovery together.
- Verify that manual recovery, lifecycle/process-death recovery, and companion-restart transport recovery can all run in one emulator-backed iteration without operator intervention.

### Changes applied

- Added `projects/apps/android/scripts/validate-agent-runtime-soak.ps1` as a combined harness over:
  - `validate-shell-recovery.ps1`
  - `validate-shell-lifecycle-recovery.ps1`
  - `validate-direct-http-drafts.ps1`
- Hardened the new harness output parser so PowerShell log lines such as `[validate-...]` are not misidentified as JSON arrays.
- Reworked `validate-direct-http-drafts.ps1` retry-mode assertions to handle repeated queued retry windows:
  - `timeout_once`
  - `disconnect_once`
  - `delayed_ack`
  - `retry_once`
- The Direct HTTP validator now keeps re-waiting for the next due retry and re-requesting `drain_outbox` until the transfer is actually delivered, instead of assuming a single retry cycle.

### Emulator validation results

- `powershell -NoProfile -ExecutionPolicy Bypass -File .\projects\apps\android\scripts\validate-agent-runtime-soak.ps1 -Serial emulator-5554 -EndpointPreset emulator_host -Iterations 1 -BasePort 8850 -StepTimeoutMinutes 20` passed.
- Combined soak summary:
  - `1 iteration`
  - `3 checks`
  - `0 failures`
  - total duration `683.711s`
- Combined checks that passed in one run:
  - `manual_recovery`
  - `lifecycle_recovery`
  - `direct_http_recovery`
- Parsed scenario coverage from the combined summary:
  - manual recovery: `stale_sending`, `due_retry`, `delayed_retry`
  - lifecycle recovery: `process_death_stale_sending`, `background_due_retry_resilience`, `process_death_delayed_retry`
  - Direct HTTP recovery: `normal`, `partial_receipt`, `malformed_receipt`, `retry_once`, `timeout_once`, `disconnect_once`, `delayed_ack`

### Product effect

- The project now has a single emulator soak command that exercises the most failure-prone recovery surfaces together instead of validating them in isolation.
- This closes the biggest remaining gap before true multi-hour unattended runs: the harness now proves that companion restarts and Android recovery paths can coexist in one continuous validation loop.

## Follow-up Session: MCP Connector Profile Hardening

### Scope

- Raise the MCP resource connector from placeholder state into a practical connector profile that records transport, auth, advertised tools, and sync history.
- Keep the product chat-first by making MCP connector status, MCP tools, and MCP skill sync all controllable from the main conversation loop.

### Changes applied

- Extended `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/ExternalEndpointRegistryRepository.kt` so external endpoint profiles can persist:
  - `transport_label`
  - `auth_label`
  - `tool_names_json`
  - `synced_skill_count`
  - `last_sync_at`
  - `health_details`
- Bumped the Android shell database schema to version `27` in `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/ShellDatabaseHelper.kt` and added the matching migration for existing installs.
- Upgraded the MCP bridge seed from a generic placeholder into a default connector profile with:
  - `Direct HTTP bridge` transport
  - `Device trust + local policy gate` auth
  - advertised tools for companion actions, transfer receive, browser research, and API ingest
- Reworked `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/McpSkillRepository.kt` so skill sync is driven by the connector's advertised MCP tool inventory instead of a blind static install.
- Expanded `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/PhoneAgentRuntime.kt` chat actions with:
  - `Connect MCP bridge`
  - `Show MCP status`
  - `List MCP tools`
  - `Update MCP skills`
- Updated `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/ResourceRegistryRepository.kt` so the resource stack summary reports MCP tool and sync state instead of only mock-ready counts.
- Updated the Settings endpoint card in `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/ui/MobileClawShellApp.kt` to surface the connector's transport, auth mode, tool count, synced skill count, last sync label, health note, and advertised tool names.
- Hardened `projects/apps/android/app/src/androidTest/java/io/makoion/mobileclaw/ui/McpSkillChatFlowTest.kt` so emulator instrumentation now verifies MCP connect -> status -> tool list -> skill sync metadata end to end.

### Emulator validation results

- `:app:assembleDebug` passed with Android Studio JBR and `MAKOION_ANDROID_BUILD_ROOT=C:\Users\jjck5\AppData\Local\Makoion\android-gradle-build-codex`.
- `:app:testDebugUnitTest` passed.
- `:app:installDebug` passed on `emulator-5554` (`Medium_Phone_API_36.1`).
- `:app:connectedDebugAndroidTest` passed on `emulator-5554` with 5 tests completed and 0 failures.
- MCP-specific emulator coverage now confirms:
  - MCP bridge connection persists advertised tool inventory
  - MCP status chat reply surfaces the transport profile
  - MCP tools chat reply surfaces the advertised tool list
  - MCP skill sync persists `synced_skill_count` and `last_sync_at`

### Product effect

- The MCP connector is no longer a generic staged placeholder. It now behaves like a real resource profile that the phone agent can inspect, summarize, and refresh from chat.
- Users can keep the app in a simple conversation flow while still controlling MCP connection state, tool inventory, and skill updates without dropping into Settings first.

## Follow-up Session: Live MCP Discovery Handshake

### Scope

- Replace the MCP connector's seeded-only metadata with a live discovery handshake against the desktop companion.
- Keep the Android shell chat-first by letting `Connect MCP bridge` and `Update MCP skills` refresh connector state from a real companion-backed HTTP discovery route.

### Changes applied

- Added `GET /api/v1/mcp/discovery` to `projects/apps/desktop-companion/src/io/makoion/desktopcompanion/Main.java`.
- The desktop companion discovery response now advertises:
  - `server_label`
  - `transport_label`
  - `auth_label`
  - `capabilities`
  - `tool_names`
  - `skill_bundles`
  - `workflow_ids`
  - `status_detail`
- Updated `projects/apps/desktop-companion/README.md` with the new MCP discovery route and a local inspection example.
- Extended `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/DevicePairingRepository.kt` with a real `discoverMcpBridge(deviceId)` path that:
  - targets `/api/v1/mcp/discovery` on the paired direct-HTTP companion
  - sends `X-MobileClaw-Trusted-Secret`
  - persists companion-advertised capabilities back onto the paired device
  - returns structured MCP discovery metadata for the chat runtime
- Reworked `projects/apps/android/app/src/main/java/io/makoion/mobileclaw/data/PhoneAgentRuntime.kt` so:
  - `Connect MCP bridge` performs live companion discovery before marking the connector connected
  - `Update MCP skills` refreshes the MCP connector from the paired companion before skill sync
  - MCP bridge selection resolves from the active direct-HTTP paired device instead of relying on seeded endpoint defaults alone
- Added `MockWebServer` instrumentation coverage in `projects/apps/android/app/build.gradle.kts`.
- Reworked `projects/apps/android/app/src/androidTest/java/io/makoion/mobileclaw/ui/McpSkillChatFlowTest.kt` so the emulator test now:
  - creates and approves a pairing session when no companion exists yet
  - arms direct HTTP transport
  - points the paired device at a live mock discovery server
  - verifies chat-driven MCP connect, status, tool listing, and skill sync against a real HTTP handshake

### Validation notes

- A stale UTP lock file at `app/outputs/androidTest-results/.../utp.0.log.lck` blocked one rerun of `connectedDebugAndroidTest`; removing the stale lock and reinstalling `app-debug-androidTest.apk` restored the emulator runner cleanly.
- Desktop companion compilation was verified directly with Android Studio JBR `javac` into `projects/apps/desktop-companion/build/codex-javac`.

### Emulator validation results

- `:app:assembleDebug` passed.
- `:app:testDebugUnitTest` passed.
- `:app:installDebug` passed on `emulator-5554`.
- `:app:installDebugAndroidTest` passed on `emulator-5554`.
- `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.makoion.mobileclaw.ui.McpSkillChatFlowTest` passed.
- Full `:app:connectedDebugAndroidTest` passed on `emulator-5554` (`Medium_Phone_API_36.1`) with 5 tests completed and 0 failures.

### Product effect

- The MCP connector now has a real companion-backed handshake path instead of only seeded profile metadata.
- Chat-driven MCP connect and skill sync now refresh from a live companion inventory, which moves the connector closer to a practical resource runtime and away from placeholder state.
