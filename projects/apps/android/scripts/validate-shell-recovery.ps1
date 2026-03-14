param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$Port = 8796,
    [int]$PollIntervalSeconds = 2,
    [int]$RecoveryDelaySeconds = 15,
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
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$inboxPath = Join-Path $companionDir "inbox-validate-shell-recovery-$sessionTag"
$stdoutLog = Join-Path $companionDir "shell-recovery-$sessionTag.log"
$stderrLog = Join-Path $companionDir "shell-recovery-$sessionTag.err"
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

latest_transfer_row = connection.execute(
    """
    SELECT id, device_id, device_name, status, attempt_count, file_names_json, next_attempt_at,
           last_error, created_at, updated_at
    FROM transfer_outbox
    ORDER BY created_at DESC
    LIMIT 1
    """
).fetchone()
latest_transfer = row_to_dict(latest_transfer_row)
if latest_transfer is not None:
    latest_transfer["file_names"] = parse_file_names(latest_transfer.pop("file_names_json"))

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
    "latest_transfer": latest_transfer,
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
        $dbPath = Join-Path $env:TEMP ("makoion-shell-recovery-{0}.db" -f ([guid]::NewGuid()))
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
    $lastTransfer = $lastState.latest_transfer | ConvertTo-Json -Compress -Depth 6
    $recentTransfers = $lastState.recent_transfers | ConvertTo-Json -Compress -Depth 6
    throw "Timed out while waiting for $Description. Last transfer state: $lastTransfer Recent transfers: $recentTransfers"
}

function Write-ValidationStep {
    param([string]$Message)

    Write-Host "[validate-shell-recovery] $Message"
}

function Find-TransferByFileName {
    param(
        [object]$State,
        [string]$FileName
    )

    $recentMatch = $State.recent_transfers |
        Where-Object { $_.file_names -contains $FileName } |
        Sort-Object { [long]$_.created_at } -Descending |
        Select-Object -First 1
    if ($recentMatch) {
        return $recentMatch
    }
    $latestTransfer = $State.latest_transfer
    if ($latestTransfer -and $latestTransfer.file_names -contains $FileName) {
        return $latestTransfer
    }
    return $null
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

function Wait-UntilEpochMillis {
    param([long]$EpochMillis)

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $delayMs = [math]::Max(0, $EpochMillis - $now + 1000)
    Start-Sleep -Milliseconds $delayMs
}

function Validate-StaleSendingRecovery {
    param([string]$DeviceId)

    $filePrefix = "recovery-stale-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    Write-ValidationStep "Queueing stale sending draft for $targetFileName"
    Invoke-DebugTransportCommand -Command "queue_stale_sending_draft" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $filePrefix
    } -IntExtras @{
        file_count = 1
    } -BoolExtras @{
        open_devices_after_command = $false
    }

    $queuedState = Wait-ForState -Description "stale sending draft queued" -TimeoutSeconds 20 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Sending"
    }
    $beforeRecoveredAudit = Get-LatestAuditTimestamp -State $queuedState -Action "files.send_to_device"
    $beforeRecoveryAudit = Get-LatestAuditTimestamp -State $queuedState -Action "shell.recovery"

    Write-ValidationStep "Requesting manual shell recovery for stale sending draft"
    Invoke-DebugTransportCommand -Command "request_shell_recovery" -BoolExtras @{
        open_devices_after_command = $false
    }

    $recoveredAudit = Wait-ForAuditEvent -Action "files.send_to_device" -ExpectedResults @("recovered") -AfterCreatedAt $beforeRecoveredAudit -DetailsNeedle "" -TimeoutSeconds 20
    $recoveryAudit = Wait-ForAuditEvent -Action "shell.recovery" -ExpectedResults @("passed") -AfterCreatedAt $beforeRecoveryAudit -DetailsNeedle "Transfer recovery repaired" -TimeoutSeconds 20
    $deliveredState = Wait-ForState -Description "stale draft delivered after recovery" -TimeoutSeconds 30 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and
            $transfer.status -eq "Delivered" -and
            [int]$transfer.attempt_count -ge 2
    }
    $deliveredTransfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
    Write-ValidationStep "Completed stale sending recovery for $targetFileName"

    return [pscustomobject]@{
        scenario = "stale_sending"
        recovery_audit = $recoveryAudit
        recovered_audit = $recoveredAudit
        latest_transfer = $deliveredTransfer
    }
}

function Validate-DueRetryRecovery {
    param([string]$DeviceId)

    $filePrefix = "recovery-due-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    Write-ValidationStep "Queueing due retry draft for $targetFileName"
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
    $beforeRecoveryAudit = Get-LatestAuditTimestamp -State $queuedState -Action "shell.recovery"

    Write-ValidationStep "Requesting manual shell recovery for due retry draft"
    Invoke-DebugTransportCommand -Command "request_shell_recovery" -BoolExtras @{
        open_devices_after_command = $false
    }

    $recoveryAudit = Wait-ForAuditEvent -Action "shell.recovery" -ExpectedResults @("passed") -AfterCreatedAt $beforeRecoveryAudit -DetailsNeedle "immediate drain was requested" -TimeoutSeconds 20
    $deliveredState = Wait-ForState -Description "due retry delivered after recovery" -TimeoutSeconds 30 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and
            $transfer.status -eq "Delivered" -and
            [int]$transfer.attempt_count -ge 2
    }
    $deliveredTransfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
    Write-ValidationStep "Completed due retry recovery for $targetFileName"

    return [pscustomobject]@{
        scenario = "due_retry"
        recovery_audit = $recoveryAudit
        latest_transfer = $deliveredTransfer
    }
}

function Validate-DelayedRetryRecovery {
    param(
        [string]$DeviceId,
        [int]$DelaySeconds
    )

    $filePrefix = "recovery-delayed-$sessionTag"
    $targetFileName = "$filePrefix-1.txt"
    Write-ValidationStep "Queueing delayed retry draft for $targetFileName with ${DelaySeconds}s delay"
    Invoke-DebugTransportCommand -Command "queue_delayed_retry_draft" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $filePrefix
    } -IntExtras @{
        file_count = 1
        retry_delay_seconds = $DelaySeconds
    } -BoolExtras @{
        open_devices_after_command = $false
    }

    $queuedState = Wait-ForState -Description "delayed retry draft queued" -TimeoutSeconds 20 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and
            $transfer.status -eq "Queued" -and
            [long]$transfer.next_attempt_at -gt [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    }
    $beforeRecoveryAudit = Get-LatestAuditTimestamp -State $queuedState -Action "shell.recovery"
    $queuedTransfer = Find-TransferByFileName -State $queuedState -FileName $targetFileName
    $scheduledRetryAt = [long]$queuedTransfer.next_attempt_at

    Write-ValidationStep "Requesting manual shell recovery for delayed retry draft"
    Invoke-DebugTransportCommand -Command "request_shell_recovery" -BoolExtras @{
        open_devices_after_command = $false
    }

    $recoveryAudit = Wait-ForAuditEvent -Action "shell.recovery" -ExpectedResults @("passed") -AfterCreatedAt $beforeRecoveryAudit -DetailsNeedle "delayed queued draft" -TimeoutSeconds 20
    $stillQueuedState = Wait-ForState -Description "delayed retry remains queued immediately after recovery" -TimeoutSeconds 10 -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and $transfer.status -eq "Queued"
    }

    Wait-UntilEpochMillis -EpochMillis $scheduledRetryAt
    $deliveredState = Wait-ForState -Description "delayed retry delivered after scheduled recovery" -TimeoutSeconds ($DelaySeconds + 30) -Predicate {
        param($State)
        $transfer = Find-TransferByFileName -State $State -FileName $targetFileName
        $transfer -and
            $transfer.status -eq "Delivered" -and
            [int]$transfer.attempt_count -ge 1
    }
    $deliveredTransfer = Find-TransferByFileName -State $deliveredState -FileName $targetFileName
    $stillQueuedTransfer = Find-TransferByFileName -State $stillQueuedState -FileName $targetFileName
    Write-ValidationStep "Completed delayed retry recovery for $targetFileName"

    return [pscustomobject]@{
        scenario = "delayed_retry"
        recovery_audit = $recoveryAudit
        queued_after_recovery = $stillQueuedTransfer
        latest_transfer = $deliveredTransfer
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
    Invoke-DebugTransportCommand -Command "request_shell_recovery" -BoolExtras @{
        open_devices_after_command = $false
    }
    $bootstrapRecoveryAudit = Wait-ForAuditEvent -Action "shell.recovery" -ExpectedResults @("passed") -AfterCreatedAt $beforeBootstrapRecoveryAudit -DetailsNeedle "" -TimeoutSeconds 30

    $beforeProbeAudit = Get-LatestAuditTimestamp -State $bootstrappedState -Action "devices.health_probe"
    Write-ValidationStep "Probing companion health from the device"
    Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
        device_id = $deviceId
    } -BoolExtras @{
        open_devices_after_command = $false
    }
    $healthProbeAudit = Wait-ForAuditEvent -Action "devices.health_probe" -ExpectedResults @("ok") -AfterCreatedAt $beforeProbeAudit -DetailsNeedle "" -TimeoutSeconds 20
    Write-ValidationStep "Companion health probe succeeded; starting recovery scenarios"

    $result = [pscustomobject]@{
        bootstrap = $bootstrap
        companion_health = $companion.Health
        bootstrap_recovery_audit = $bootstrapRecoveryAudit
        health_probe_audit = $healthProbeAudit
        validations = @(
            (Validate-StaleSendingRecovery -DeviceId $deviceId),
            (Validate-DueRetryRecovery -DeviceId $deviceId),
            (Validate-DelayedRetryRecovery -DeviceId $deviceId -DelaySeconds $RecoveryDelaySeconds)
        )
    } | ConvertTo-Json -Depth 8
    Write-ValidationStep "Shell recovery validation completed successfully"
    Write-Output $result
} finally {
    if ($cleanupDeviceId) {
        try {
            Invoke-DebugTransportCommand -Command "cleanup_validation_device" -StringExtras @{
                device_id = $cleanupDeviceId
            } -BoolExtras @{
                open_devices_after_command = $false
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
    }
}
