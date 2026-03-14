param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$Port = 8807,
    [int]$PollIntervalSeconds = 2,
    [int]$RecoveryDelaySeconds = 15,
    [int]$BackgroundPauseSeconds = 4,
    [switch]$LeaveCompanionRunning
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidProjectDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $androidProjectDir))
$bootstrapScript = Join-Path $androidProjectDir "scripts\bootstrap-transport-validation.ps1"
$companionDir = Join-Path $repoRoot "projects\apps\desktop-companion"
$companionScript = Join-Path $companionDir "run-companion.ps1"
$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$packageName = "io.makoion.hub.dev"
$mainActivity = "$packageName/io.makoion.mobileclaw.MainActivity"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$inboxPath = Join-Path $companionDir "inbox-validate-shell-lifecycle-recovery-$sessionTag"
$stdoutLog = Join-Path $companionDir "shell-lifecycle-recovery-$sessionTag.log"
$stderrLog = Join-Path $companionDir "shell-lifecycle-recovery-$sessionTag.err"
$traceLog = Join-Path $companionDir "shell-lifecycle-recovery-$sessionTag.trace.log"
$pythonScript = @'
import json
import sqlite3
import sys

db_path = sys.argv[1]

connection = sqlite3.connect(db_path)
connection.row_factory = sqlite3.Row

def row_to_dict(row):
    if row is None:
        return None
    return {key: row[key] for key in row.keys()}

def parse_file_names(file_names_json):
    if not file_names_json:
        return []
    try:
        return json.loads(file_names_json)
    except Exception:
        return []

latest_device = row_to_dict(
    connection.execute(
        """
        SELECT id, name, role, status, transport_mode, endpoint_url, validation_mode, paired_at
        FROM paired_devices
        ORDER BY paired_at DESC
        LIMIT 1
        """
    ).fetchone()
)

recent_devices = [
    row_to_dict(row)
    for row in connection.execute(
        """
        SELECT id, name, role, status, transport_mode, endpoint_url, validation_mode, paired_at
        FROM paired_devices
        ORDER BY paired_at DESC
        LIMIT 32
        """
    ).fetchall()
]

recent_transfers = []
for row in connection.execute(
    """
    SELECT id, device_id, device_name, status, attempt_count, file_names_json, next_attempt_at,
           last_error, created_at, updated_at
    FROM transfer_outbox
    ORDER BY created_at DESC
    LIMIT 32
    """
).fetchall():
    transfer = row_to_dict(row)
    transfer["file_names"] = parse_file_names(transfer.pop("file_names_json"))
    recent_transfers.append(transfer)

recent_audits = [
    row_to_dict(row)
    for row in connection.execute(
        """
        SELECT action, result, details, created_at
        FROM audit_events
        ORDER BY created_at DESC
        LIMIT 64
        """
    ).fetchall()
]

connection.close()

print(json.dumps({
    "latest_device": latest_device,
    "recent_devices": recent_devices,
    "recent_transfers": recent_transfers,
    "recent_audits": recent_audits,
}))
'@

if (-not (Test-Path $adbPath)) {
    throw "adb.exe was not found at $adbPath"
}
if (-not (Test-Path $bootstrapScript)) {
    throw "bootstrap-transport-validation.ps1 was not found at $bootstrapScript"
}
if (-not (Test-Path $companionScript)) {
    throw "run-companion.ps1 was not found at $companionScript"
}

function Resolve-TargetSerial {
    param([string]$RequestedSerial)

    if ($RequestedSerial) {
        return $RequestedSerial
    }

    $devices = @(& $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
    if ($devices.Count -eq 0) {
        throw "No connected Android emulator/device was found. Pass -Serial explicitly."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Pass -Serial explicitly."
    }
    return ($devices[0] -split "\s+")[0]
}

function Wait-ForResolvedDevice {
    param([int]$TimeoutSeconds = 30)

    $serialPattern = "^{0}`tdevice$" -f [regex]::Escape($script:ResolvedSerial)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $devices = @(& $adbPath devices | Select-Object -Skip 1)
        if ($devices | Where-Object { $_ -match $serialPattern }) {
            return
        }
        & $adbPath reconnect | Out-Null
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    $deviceSnapshot = (& $adbPath devices) -join [Environment]::NewLine
    throw "Android device $($script:ResolvedSerial) is unavailable. adb devices output: $deviceSnapshot"
}

function Write-ValidationStep {
    param([string]$Message)

    $line = "[validate-shell-lifecycle-recovery] $Message"
    Write-Host $line
    Add-Content -Path $traceLog -Value $line
}

function Wait-CompanionHealth {
    param([int]$PortNumber)

    $lastError = $null
    for ($index = 0; $index -lt 25; $index++) {
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$PortNumber/health" -TimeoutSec 2
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Milliseconds 400
        }
    }
    throw "Companion health check on 127.0.0.1:$PortNumber did not become ready. Last error: $lastError"
}

function Start-ValidationCompanion {
    param([int]$PortNumber, [string]$InboxDirectory)

    New-Item -ItemType Directory -Force -Path $InboxDirectory | Out-Null
    $process = Start-Process pwsh -ArgumentList @(
        "-NoProfile",
        "-File",
        $companionScript,
        "--host",
        "127.0.0.1",
        "--port",
        $PortNumber,
        "--inbox-dir",
        $InboxDirectory
    ) -WorkingDirectory $companionDir -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
    $health = Wait-CompanionHealth -PortNumber $PortNumber
    return [pscustomobject]@{
        Process = $process
        Health = $health
    }
}

function Stop-ValidationCompanion {
    param([System.Diagnostics.Process]$Process)

    if (-not $Process) {
        return
    }

    $null = & taskkill.exe /PID $Process.Id /T /F 2>$null
    if ($LASTEXITCODE -ne 0) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 300
}

function Invoke-DebugTransportCommand {
    param(
        [string]$Command,
        [hashtable]$StringExtras = @{},
        [hashtable]$IntExtras = @{},
        [hashtable]$BoolExtras = @{}
    )

    $arguments = @(
        "-s",
        $script:ResolvedSerial,
        "shell",
        "am",
        "broadcast",
        "-a",
        "$packageName.DEBUG_TRANSPORT",
        "-n",
        "$packageName/io.makoion.mobileclaw.debug.DebugTransportReceiver",
        "--es",
        "command",
        $Command
    )

    foreach ($entry in $StringExtras.GetEnumerator()) {
        $arguments += @("--es", $entry.Key, [string]$entry.Value)
    }
    foreach ($entry in $IntExtras.GetEnumerator()) {
        $arguments += @("--ei", $entry.Key, [string]$entry.Value)
    }
    foreach ($entry in $BoolExtras.GetEnumerator()) {
        $arguments += @("--ez", $entry.Key, [string]$entry.Value)
    }

    & $adbPath @arguments | Out-Null
}

function Force-StopMobileClaw {
    & $adbPath -s $script:ResolvedSerial shell am force-stop $packageName | Out-Null
}

function Launch-MobileClawDevices {
    & $adbPath -s $script:ResolvedSerial shell am start -n $mainActivity --es open_section devices | Out-Null
}

function Background-MobileClaw {
    & $adbPath -s $script:ResolvedSerial shell input keyevent 3 | Out-Null
}

function Pull-MobileClawDatabase {
    param([string]$DestinationPath)

    if (Test-Path $DestinationPath) {
        Remove-Item $DestinationPath -Force
    }
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Wait-ForResolvedDevice
        $process = Start-Process $adbPath -ArgumentList @(
            "-s",
            $script:ResolvedSerial,
            "exec-out",
            "run-as",
            $packageName,
            "cat",
            "databases/mobileclaw_shell.db"
        ) -RedirectStandardOutput $DestinationPath -RedirectStandardError "$DestinationPath.err" -Wait -PassThru -NoNewWindow

        if ($process.ExitCode -eq 0) {
            if (Test-Path "$DestinationPath.err") {
                Remove-Item "$DestinationPath.err" -Force
            }
            return
        }

        $stderr = if (Test-Path "$DestinationPath.err") {
            Get-Content -Raw "$DestinationPath.err"
        } else {
            ""
        }
        if (Test-Path "$DestinationPath.err") {
            Remove-Item "$DestinationPath.err" -Force
        }
        if ($attempt -lt 3 -and $stderr -match "device '.*' not found|no devices/emulators found") {
            Start-Sleep -Seconds 2
            continue
        }
        throw "Failed to pull mobileclaw_shell.db from the device. $stderr".Trim()
    }
}

function Read-ValidationState {
    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $dbPath = Join-Path $env:TEMP ("makoion-shell-lifecycle-{0}.db" -f ([guid]::NewGuid()))
        $stderrPath = "$dbPath.stderr"
        try {
            Pull-MobileClawDatabase -DestinationPath $dbPath
            $stateJson = $pythonScript | python - $dbPath 2> $stderrPath
            if ($LASTEXITCODE -ne 0) {
                $stderr = if (Test-Path $stderrPath) { Get-Content -Raw $stderrPath } else { "" }
                throw "Failed to inspect validation database snapshot. $stderr".Trim()
            }
            if ([string]::IsNullOrWhiteSpace($stateJson)) {
                throw "Validation database snapshot was empty."
            }
            return $stateJson | ConvertFrom-Json -Depth 8
        } catch {
            $lastError = $_.Exception.Message
            if ($attempt -lt 5) {
                Start-Sleep -Milliseconds 300
            }
        } finally {
            if (Test-Path $dbPath) {
                Remove-Item $dbPath -Force
            }
            if (Test-Path $stderrPath) {
                Remove-Item $stderrPath -Force
            }
        }
    }
    throw "Failed to read validation state after 5 attempts. Last error: $lastError"
}

function Wait-ForState {
    param(
        [string]$Description,
        [int]$TimeoutSeconds,
        [scriptblock]$Predicate
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = $null
    while ((Get-Date) -lt $deadline) {
        $lastState = Read-ValidationState
        if (& $Predicate $lastState) {
            return $lastState
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
    $recentTransfers = $lastState.recent_transfers | ConvertTo-Json -Compress -Depth 6
    throw "Timed out while waiting for $Description. Recent transfers: $recentTransfers"
}

function Get-LatestAuditTimestamp {
    param(
        [object]$State,
        [string]$Action
    )

    $audit = $State.recent_audits |
        Where-Object { $_.action -eq $Action } |
        Sort-Object { [long]$_.created_at } -Descending |
        Select-Object -First 1
    if ($audit) {
        return [long]$audit.created_at
    }
    return 0L
}

function Wait-ForAuditEvent {
    param(
        [string]$Action,
        [string[]]$ExpectedResults,
        [long]$AfterCreatedAt,
        [string]$DetailsNeedle,
        [int]$TimeoutSeconds
    )

    $state = Wait-ForState -Description "$Action audit" -TimeoutSeconds $TimeoutSeconds -Predicate {
        param($CandidateState)
        $match = $CandidateState.recent_audits |
            Where-Object {
                $_.action -eq $Action -and
                [long]$_.created_at -gt $AfterCreatedAt -and
                $ExpectedResults -contains $_.result -and
                (
                    [string]::IsNullOrWhiteSpace($DetailsNeedle) -or
                    $_.details -like "*$DetailsNeedle*"
                )
            } |
            Sort-Object { [long]$_.created_at } -Descending |
            Select-Object -First 1
        return $null -ne $match
    }

    return $state.recent_audits |
        Where-Object {
            $_.action -eq $Action -and
            [long]$_.created_at -gt $AfterCreatedAt -and
            $ExpectedResults -contains $_.result -and
            (
                [string]::IsNullOrWhiteSpace($DetailsNeedle) -or
                $_.details -like "*$DetailsNeedle*"
            )
        } |
        Sort-Object { [long]$_.created_at } -Descending |
        Select-Object -First 1
}

function Find-DeviceByEndpoint {
    param(
        [object]$State,
        [string]$EndpointUrl
    )

    $recentMatch = $State.recent_devices |
        Where-Object { $_.transport_mode -eq "DirectHttp" -and $_.endpoint_url -eq $EndpointUrl } |
        Sort-Object { [long]$_.paired_at } -Descending |
        Select-Object -First 1
    if ($recentMatch) {
        return $recentMatch
    }
    $latestDevice = $State.latest_device
    if ($latestDevice -and $latestDevice.transport_mode -eq "DirectHttp" -and $latestDevice.endpoint_url -eq $EndpointUrl) {
        return $latestDevice
    }
    return $null
}

function Wait-UntilEpochMillis {
    param([long]$EpochMillis)

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $delayMs = [math]::Max(0, $EpochMillis - $now + 1000)
    Start-Sleep -Milliseconds $delayMs
}

function Find-TransferByFileName {
    param(
        [object]$State,
        [string]$FileName
    )

    return $State.recent_transfers |
        Where-Object { $_.file_names -contains $FileName } |
        Sort-Object { [long]$_.created_at } -Descending |
        Select-Object -First 1
}

function Get-TransferDirectories {
    if (-not (Test-Path $inboxPath)) {
        return @()
    }
    return Get-ChildItem -Path $inboxPath -Directory |
        Where-Object { $_.Name -like "*-transfer-*" } |
        Sort-Object Name
}

function Wait-ForMaterializedTransfer {
    param(
        [int]$PreviousCount,
        [string]$SummaryNeedle,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $directories = @(Get-TransferDirectories)
        if ($directories.Count -gt $PreviousCount) {
            foreach ($candidate in ($directories | Select-Object -Skip $PreviousCount)) {
                $summaryPath = Join-Path $candidate.FullName "summary.txt"
                if (Test-Path $summaryPath) {
                    $summary = Get-Content -Raw $summaryPath
                    if ([string]::IsNullOrWhiteSpace($SummaryNeedle) -or $summary -like "*$SummaryNeedle*") {
                        return [pscustomobject]@{
                            Directory = $candidate
                            Summary = $summary.Trim()
                        }
                    }
                }
            }
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
    throw "Timed out while waiting for a materialized transfer containing $SummaryNeedle."
}

function Validate-ProcessDeathStaleSending {
    param([string]$DeviceId)

    $filePrefix = "lifecycle-stale-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    $beforeMaterializedCount = @(Get-TransferDirectories).Count
    Write-ValidationStep "Queueing stale sending draft for process-death recovery: $targetFileName"
    Invoke-DebugTransportCommand -Command "queue_stale_sending_draft" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $filePrefix
    } -IntExtras @{
        file_count = 1
    }

    $queuedState = Wait-ForState -Description "stale sending draft queued" -TimeoutSeconds 20 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Sending"
    }
    $beforeRecoveredAudit = Get-LatestAuditTimestamp -State $queuedState -Action "files.send_to_device"

    Write-ValidationStep "Force-stopping the app and relaunching for stale sending recovery"
    Force-StopMobileClaw
    Start-Sleep -Seconds 1
    Launch-MobileClawDevices

    $recoveredAudit = Wait-ForAuditEvent -Action "files.send_to_device" -ExpectedResults @("recovered") -AfterCreatedAt $beforeRecoveredAudit -DetailsNeedle "" -TimeoutSeconds 25
    $deliveredState = Wait-ForState -Description "stale sending draft delivered after app restart" -TimeoutSeconds 40 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
    }
    $materialized = Wait-ForMaterializedTransfer -PreviousCount $beforeMaterializedCount -SummaryNeedle $targetFileName -TimeoutSeconds 40

    return [pscustomobject]@{
        scenario = "process_death_stale_sending"
        recovered_audit = $recoveredAudit
        latest_transfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
        materialized_dir = $materialized.Directory.Name
    }
}

function Validate-ForegroundResumeDueRetry {
    param([string]$DeviceId)

    $filePrefix = "lifecycle-foreground-due-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    $beforeMaterializedCount = @(Get-TransferDirectories).Count
    Write-ValidationStep "Backgrounding the app before queueing due retry recovery"
    Background-MobileClaw
    Start-Sleep -Seconds 1
    Write-ValidationStep "Queueing due retry draft for foreground-resume recovery: $targetFileName"
    Invoke-DebugTransportCommand -Command "queue_due_retry_draft" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $filePrefix
    } -IntExtras @{
        file_count = 1
    } -BoolExtras @{
        open_devices_after_command = $false
    }

    $queuedState = Wait-ForState -Description "due retry draft queued" -TimeoutSeconds 20 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Queued"
    }

    Write-ValidationStep "Keeping the app backgrounded before foreground-resume recovery"
    Start-Sleep -Seconds $BackgroundPauseSeconds

    $backgroundState = Wait-ForState -Description "due retry observed while app is backgrounded" -TimeoutSeconds 10 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and @("Queued", "Delivered") -contains $transfer.status
    }

    Write-ValidationStep "Bringing the app back to the foreground for due retry recovery"
    Launch-MobileClawDevices

    $deliveredState = Wait-ForState -Description "due retry delivered after foreground resume" -TimeoutSeconds 40 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
    }
    $materialized = Wait-ForMaterializedTransfer -PreviousCount $beforeMaterializedCount -SummaryNeedle $targetFileName -TimeoutSeconds 40

    return [pscustomobject]@{
        scenario = "background_due_retry_resilience"
        background_state = Find-TransferByFileName -State $backgroundState -FileName $targetFileName
        latest_transfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
        materialized_dir = $materialized.Directory.Name
    }
}

function Validate-ProcessDeathDelayedRetry {
    param(
        [string]$DeviceId,
        [int]$DelaySeconds
    )

    $filePrefix = "lifecycle-delayed-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    $beforeMaterializedCount = @(Get-TransferDirectories).Count
    Write-ValidationStep "Queueing delayed retry draft for process-death recovery: $targetFileName"
    Invoke-DebugTransportCommand -Command "queue_delayed_retry_draft" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $filePrefix
    } -IntExtras @{
        file_count = 1
        retry_delay_seconds = $DelaySeconds
    }

    $queuedState = Wait-ForState -Description "delayed retry draft queued" -TimeoutSeconds 20 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and
            $transfer.status -eq "Queued" -and
            [long]$transfer.next_attempt_at -gt [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    $scheduledRetryAt = [long](Find-TransferByFileName -State $queuedState -FileName $targetFileName).next_attempt_at

    Write-ValidationStep "Force-stopping the app before delayed retry and relaunching it"
    Force-StopMobileClaw
    Start-Sleep -Seconds 1
    Launch-MobileClawDevices

    Wait-UntilEpochMillis -EpochMillis $scheduledRetryAt
    $deliveredState = Wait-ForState -Description "delayed retry delivered after scheduled retry on relaunch" -TimeoutSeconds ($DelaySeconds + 40) -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
    }
    $materialized = Wait-ForMaterializedTransfer -PreviousCount $beforeMaterializedCount -SummaryNeedle $targetFileName -TimeoutSeconds ($DelaySeconds + 40)

    return [pscustomobject]@{
        scenario = "process_death_delayed_retry"
        queued_before_relaunch = Find-TransferByFileName -State $queuedState -FileName $targetFileName
        latest_transfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
        materialized_dir = $materialized.Directory.Name
    }
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$companion = $null
$cleanupDeviceId = $null

try {
    Write-ValidationStep "Starting desktop companion on port $Port"
    $companion = Start-ValidationCompanion -PortNumber $Port -InboxDirectory $inboxPath
    Write-ValidationStep "Bootstrapping Direct HTTP device against $($companion.Health.endpoint)"
    $bootstrap = & pwsh -NoProfile -File $bootstrapScript -Serial $script:ResolvedSerial -ValidationMode "normal" -EndpointPreset $EndpointPreset -Port $Port | ConvertFrom-Json
    Start-Sleep -Seconds 1

    $bootstrappedState = Wait-ForState -Description "bootstrapped Direct HTTP device" -TimeoutSeconds 20 -Predicate {
        param($State)
        $null -ne (Find-DeviceByEndpoint -State $State -EndpointUrl $bootstrap.endpoint)
    }
    $bootstrappedDevice = Find-DeviceByEndpoint -State $bootstrappedState -EndpointUrl $bootstrap.endpoint
    $deviceId = $bootstrappedDevice.id
    if (-not $deviceId) {
        throw "No paired Direct HTTP device was found after bootstrap."
    }
    $cleanupDeviceId = $deviceId
    Write-ValidationStep "Bootstrapped device $deviceId at endpoint $($bootstrap.endpoint)"

    $beforeBootstrapRecoveryAudit = Get-LatestAuditTimestamp -State $bootstrappedState -Action "shell.recovery"
    Write-ValidationStep "Requesting shell recovery to refresh runtime state before companion probe"
    Invoke-DebugTransportCommand -Command "request_shell_recovery"
    $bootstrapRecoveryAudit = Wait-ForAuditEvent -Action "shell.recovery" -ExpectedResults @("passed") -AfterCreatedAt $beforeBootstrapRecoveryAudit -DetailsNeedle "" -TimeoutSeconds 30

    $beforeProbeAudit = Get-LatestAuditTimestamp -State $bootstrappedState -Action "devices.health_probe"
    Write-ValidationStep "Probing companion health from the device"
    Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
        device_id = $deviceId
    }
    $healthProbeAudit = Wait-ForAuditEvent -Action "devices.health_probe" -ExpectedResults @("ok") -AfterCreatedAt $beforeProbeAudit -DetailsNeedle "" -TimeoutSeconds 20
    Write-ValidationStep "Companion health probe succeeded; starting lifecycle recovery scenarios"

    $result = [pscustomobject]@{
        bootstrap = $bootstrap
        companion_health = $companion.Health
        bootstrap_recovery_audit = $bootstrapRecoveryAudit
        health_probe_audit = $healthProbeAudit
        validations = @(
            (Validate-ProcessDeathStaleSending -DeviceId $deviceId),
            (Validate-ForegroundResumeDueRetry -DeviceId $deviceId),
            (Validate-ProcessDeathDelayedRetry -DeviceId $deviceId -DelaySeconds $RecoveryDelaySeconds)
        )
    } | ConvertTo-Json -Depth 8
    Write-ValidationStep "Shell lifecycle recovery validation completed successfully"
    Write-Output $result
} catch {
    Write-ValidationStep "ERROR: $($_.Exception.Message)"
    throw
} finally {
    if ($cleanupDeviceId) {
        try {
            Invoke-DebugTransportCommand -Command "cleanup_validation_device" -StringExtras @{
                device_id = $cleanupDeviceId
            }
        } catch {
            Write-ValidationStep "Cleanup skipped for ${cleanupDeviceId}: $($_.Exception.Message)"
        }
    }
    if ($companion -and $companion.Process -and -not $LeaveCompanionRunning) {
        Stop-ValidationCompanion -Process $companion.Process
    }
    if (-not $LeaveCompanionRunning) {
        if (Test-Path $inboxPath) {
            Remove-Item $inboxPath -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path $stdoutLog) {
            Remove-Item $stdoutLog -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path $stderrLog) {
            Remove-Item $stderrLog -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path $traceLog) {
            Remove-Item $traceLog -Force -ErrorAction SilentlyContinue
        }
    }
}
