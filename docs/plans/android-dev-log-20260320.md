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
