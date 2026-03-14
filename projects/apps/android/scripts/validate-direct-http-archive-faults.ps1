param(
    [string]$Serial,
    [ValidateSet("archive_zip", "archive_zip_streaming")]
    [string]$DeliveryMode = "archive_zip",
    [ValidateSet(
        "normal",
        "partial_receipt",
        "malformed_receipt",
        "retry_once",
        "timeout_once",
        "disconnect_once",
        "delayed_ack"
    )]
    [string[]]$ValidationModes = @(),
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$Port = 0,
    [int]$PollIntervalSeconds = 2,
    [switch]$IncludeSlowTimeout,
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

def parse_receipt(receipt_json):
    if not receipt_json:
        return {
            "receipt_valid": None,
            "receipt_issue": None,
            "status_detail": None,
            "receipt_delivery_mode": None,
            "materialized_dir": None,
            "file_entry_count": None,
            "extracted_entries": None,
        }
    try:
        receipt = json.loads(receipt_json)
    except Exception:
        return {
            "receipt_valid": False,
            "receipt_issue": "receipt_json was not parseable",
            "status_detail": None,
            "receipt_delivery_mode": None,
            "materialized_dir": None,
            "file_entry_count": None,
            "extracted_entries": None,
        }
    return {
        "receipt_valid": receipt.get("receipt_valid"),
        "receipt_issue": receipt.get("receipt_issue"),
        "status_detail": receipt.get("status_detail"),
        "receipt_delivery_mode": receipt.get("delivery_mode"),
        "materialized_dir": receipt.get("materialized_dir"),
        "file_entry_count": receipt.get("file_entry_count"),
        "extracted_entries": receipt.get("extracted_entries"),
    }

def parse_file_names(file_names_json):
    if not file_names_json:
        return []
    try:
        return json.loads(file_names_json)
    except Exception:
        return []

def normalize_transfer(row):
    transfer = row_to_dict(row)
    if transfer is None:
        return None
    transfer["file_names"] = parse_file_names(transfer.pop("file_names_json"))
    transfer.update(parse_receipt(transfer.pop("receipt_json")))
    return transfer

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

latest_device = recent_devices[0] if recent_devices else None

recent_transfers = [
    normalize_transfer(row)
    for row in connection.execute(
        """
        SELECT id, device_id, device_name, status, attempt_count, file_names_json, delivery_mode,
               receipt_json, next_attempt_at, last_error, created_at, updated_at
        FROM transfer_outbox
        ORDER BY created_at DESC
        LIMIT 16
        """
    ).fetchall()
]

latest_transfer = recent_transfers[0] if recent_transfers else None

latest_audit = row_to_dict(
    connection.execute(
        """
        SELECT action, result, details, created_at
        FROM audit_events
        ORDER BY created_at DESC
        LIMIT 1
        """
    ).fetchone()
)

recent_audits = [
    row_to_dict(row)
    for row in connection.execute(
        """
        SELECT action, result, details, created_at
        FROM audit_events
        ORDER BY created_at DESC
        LIMIT 24
        """
    ).fetchall()
]

connection.close()

print(json.dumps({
    "latest_device": latest_device,
    "recent_devices": recent_devices,
    "latest_transfer": latest_transfer,
    "recent_transfers": recent_transfers,
    "latest_audit": latest_audit,
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

function Wait-CompanionHealth {
    param([int]$PortNumber)

    $lastError = $null
    for ($index = 0; $index -lt 20; $index++) {
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$PortNumber/health" -TimeoutSec 2
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Seconds 1
        }
    }
    throw "Companion health check on 127.0.0.1:$PortNumber did not become ready. Last error: $lastError"
}

function Resolve-ValidationPort {
    param([int]$RequestedPort)

    if ($RequestedPort -gt 0) {
        return $RequestedPort
    }

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Start-ValidationCompanion {
    param([int]$PortNumber)

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $inboxDir = Join-Path $companionDir ("inbox-archive-faults-{0}-{1}" -f $DeliveryMode, $timestamp)
    $stdoutPath = Join-Path $env:TEMP "makoion-archive-faults-$timestamp.log"
    $stderrPath = Join-Path $env:TEMP "makoion-archive-faults-$timestamp.err.log"
    New-Item -ItemType Directory -Force -Path $inboxDir | Out-Null
    $process = Start-Process pwsh -ArgumentList @(
        "-NoProfile",
        "-File",
        $companionScript,
        "--host",
        "127.0.0.1",
        "--port",
        $PortNumber,
        "--inbox-dir",
        $inboxDir
    ) -WorkingDirectory $companionDir -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $health = Wait-CompanionHealth -PortNumber $PortNumber
    if ($health.inbox_dir -ne $inboxDir) {
        throw "Port $PortNumber is already serving a different companion inbox ($($health.inbox_dir))."
    }
    return [pscustomobject]@{
        Process = $process
        InboxDir = $inboxDir
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
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
        [hashtable]$IntExtras = @{}
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

    & $adbPath @arguments | Out-Null
}

function Pull-MobileClawDatabase {
    param([string]$DestinationPath)

    if (Test-Path $DestinationPath) {
        Remove-Item $DestinationPath -Force
    }
    $process = Start-Process $adbPath -ArgumentList @(
        "-s",
        $script:ResolvedSerial,
        "exec-out",
        "run-as",
        $packageName,
        "cat",
        "databases/mobileclaw_shell.db"
    ) -RedirectStandardOutput $DestinationPath -RedirectStandardError "$DestinationPath.err" -Wait -PassThru -NoNewWindow

    if ($process.ExitCode -ne 0) {
        $stderr = if (Test-Path "$DestinationPath.err") {
            Get-Content -Raw "$DestinationPath.err"
        } else {
            ""
        }
        throw "Failed to pull mobileclaw_shell.db from the device. $stderr".Trim()
    }

    if (Test-Path "$DestinationPath.err") {
        Remove-Item "$DestinationPath.err" -Force
    }
}

function Read-ValidationState {
    $dbPath = Join-Path $env:TEMP ("makoion-archive-faults-{0}.db" -f ([guid]::NewGuid()))
    Pull-MobileClawDatabase -DestinationPath $dbPath
    try {
        $stateJson = $pythonScript | python - $dbPath
        return $stateJson | ConvertFrom-Json -Depth 8
    } finally {
        if (Test-Path $dbPath) {
            Remove-Item $dbPath -Force
        }
    }
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
    throw "Timed out while waiting for $Description. Last transfer state: $lastTransfer"
}

function Wait-UntilRetryIsDue {
    param([object]$Transfer)

    $nextAttemptAt = $Transfer.next_attempt_at
    if (-not $nextAttemptAt) {
        Start-Sleep -Seconds 16
        return
    }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $delayMs = [math]::Max(0, [long]$nextAttemptAt - $now + 1000)
    Start-Sleep -Milliseconds $delayMs
}

function Find-TransferByPrefix {
    param(
        [object]$State,
        [string]$Prefix
    )

    foreach ($transfer in @($State.recent_transfers)) {
        if (
            $transfer.file_names `
                -and $transfer.file_names.Count -gt 0 `
                -and $transfer.file_names[0] -eq "$Prefix-1.bin"
        ) {
            return $transfer
        }
    }
    return $null
}

function Normalize-ValidationModeToken {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }

    return $Value.Trim().Replace("_", "").Replace("-", "").Replace(" ", "").ToUpperInvariant()
}

function Find-BootstrappedDevice {
    param(
        [object]$State,
        [string]$Endpoint,
        [string]$ValidationMode
    )

    $normalizedMode = Normalize-ValidationModeToken -Value $ValidationMode
    foreach ($device in @($State.recent_devices)) {
        if (
            $device.endpoint_url -eq $Endpoint `
                -and (Normalize-ValidationModeToken -Value $device.validation_mode) -eq $normalizedMode
        ) {
            return $device
        }
    }

    return $null
}

function Get-TransferDirectorySummary {
    param(
        [string]$InboxDir,
        [string]$MaterializedDir,
        [string]$TransferId
    )

    $resolvedDirName = $MaterializedDir
    if ([string]::IsNullOrWhiteSpace($resolvedDirName)) {
        if ([string]::IsNullOrWhiteSpace($TransferId)) {
            throw "Materialized transfer directory could not be resolved because both materialized_dir and transfer_id were empty."
        }
        $matchingDirs = @(
            Get-ChildItem -Path $InboxDir -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like "*-$TransferId" } |
                Sort-Object LastWriteTime -Descending
        )
        if ($matchingDirs.Count -eq 0) {
            throw "Expected a materialized transfer directory matching *-$TransferId under $InboxDir, but none was found."
        }
        $resolvedDirName = $matchingDirs[0].Name
    }

    $transferDir = Join-Path $InboxDir $resolvedDirName
    if (-not (Test-Path $transferDir)) {
        throw "Expected materialized transfer directory $transferDir was not found."
    }
    $filesDir = Join-Path $transferDir "files"
    $fileEntries = if (Test-Path $filesDir) {
        Get-ChildItem -Path $filesDir -File | Sort-Object Name
    } else {
        @()
    }
    return [pscustomobject]@{
        transfer_dir = $transferDir
        manifest_present = Test-Path (Join-Path $transferDir "manifest.json")
        summary_present = Test-Path (Join-Path $transferDir "summary.txt")
        received_present = Test-Path (Join-Path $transferDir "received.txt")
        extracted_file_count = $fileEntries.Count
        extracted_file_sizes = @($fileEntries | ForEach-Object { $_.Length })
        extracted_file_names = @($fileEntries | ForEach-Object { $_.Name })
    }
}

function Get-ScenarioDefaults {
    param([string]$SelectedDeliveryMode)

    if ($SelectedDeliveryMode -eq "archive_zip_streaming") {
        return [pscustomobject]@{
            FileCount = 1
            FileSizeKiB = 18432
        }
    }
    return [pscustomobject]@{
        FileCount = 2
        FileSizeKiB = 64
    }
}

function Get-DefaultValidationModes {
    param([string]$SelectedDeliveryMode)

    if ($SelectedDeliveryMode -eq "archive_zip_streaming") {
        return @(
            "normal",
            "partial_receipt",
            "malformed_receipt",
            "retry_once",
            "disconnect_once",
            "delayed_ack"
        )
    }
    return @(
        "normal",
        "partial_receipt",
        "malformed_receipt",
        "retry_once",
        "timeout_once",
        "disconnect_once",
        "delayed_ack"
    )
}

function Get-EffectiveValidationModes {
    param(
        [string]$SelectedDeliveryMode,
        [string[]]$RequestedModes,
        [switch]$AllowSlowTimeout
    )

    $modes = if ($RequestedModes.Count -gt 0) {
        $RequestedModes
    } else {
        Get-DefaultValidationModes -SelectedDeliveryMode $SelectedDeliveryMode
    }
    if ($SelectedDeliveryMode -eq "archive_zip_streaming" -and -not $AllowSlowTimeout) {
        return @($modes | Where-Object { $_ -ne "timeout_once" })
    }
    return @($modes)
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$scenarioDefaults = Get-ScenarioDefaults -SelectedDeliveryMode $DeliveryMode
$effectiveValidationModes = Get-EffectiveValidationModes `
    -SelectedDeliveryMode $DeliveryMode `
    -RequestedModes $ValidationModes `
    -AllowSlowTimeout:$IncludeSlowTimeout

$validationPort = Resolve-ValidationPort -RequestedPort $Port
$companion = Start-ValidationCompanion -PortNumber $validationPort
$results = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($mode in $effectiveValidationModes) {
        $bootstrap = & pwsh -NoProfile -File $bootstrapScript `
            -Serial $script:ResolvedSerial `
            -ValidationMode $mode `
            -EndpointPreset $EndpointPreset `
            -Port $validationPort | ConvertFrom-Json

        $bootstrappedState = Wait-ForState -Description "bootstrapped Direct HTTP device for $mode" -TimeoutSeconds 20 -Predicate {
            param($State)
            Find-BootstrappedDevice -State $State -Endpoint $bootstrap.endpoint -ValidationMode $mode
        }
        $bootstrappedDevice = Find-BootstrappedDevice `
            -State $bootstrappedState `
            -Endpoint $bootstrap.endpoint `
            -ValidationMode $mode
        $deviceId = $bootstrappedDevice.id
        $deviceName = $bootstrappedDevice.name
        if (-not $deviceId) {
            throw "No paired Direct HTTP device was found after archive bootstrap for mode $mode."
        }

        Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
            device_id = $deviceId
        }
        $probeState = Wait-ForState -Description "archive health probe success for $mode" -TimeoutSeconds 20 -Predicate {
            param($State)
            $State.recent_audits | Where-Object {
                $_.action -eq "devices.health_probe" `
                    -and $_.result -eq "ok" `
                    -and $_.details -like "*$deviceName*"
            } | Select-Object -First 1
        }

        $filePrefix = "{0}-{1}" -f $DeliveryMode.Replace("_", "-"), $mode.Replace("_", "-")
        Invoke-DebugTransportCommand -Command "queue_debug_archive_transfer" -StringExtras @{
            device_id = $deviceId
            file_prefix = $filePrefix
        } -IntExtras @{
            file_count = $scenarioDefaults.FileCount
            file_size_kib = $scenarioDefaults.FileSizeKiB
        }

        $finalState = switch ($mode) {
            "normal" {
                Wait-ForState -Description "$DeliveryMode normal delivery" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.receipt_valid -eq $true `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and [int]$transfer.file_entry_count -eq $scenarioDefaults.FileCount `
                        -and [int]$transfer.attempt_count -eq 1
                }
            }
            "partial_receipt" {
                Wait-ForState -Description "$DeliveryMode partial receipt review" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $false `
                        -and [int]$transfer.attempt_count -eq 1
                }
            }
            "malformed_receipt" {
                Wait-ForState -Description "$DeliveryMode malformed receipt review" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $false `
                        -and [int]$transfer.attempt_count -eq 1
                }
            }
            "retry_once" {
                $retryState = Wait-ForState -Description "$DeliveryMode retry_once retry scheduling" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Queued" `
                        -and [long]$transfer.next_attempt_at -gt 0 `
                        -and [int]$transfer.attempt_count -eq 1
                }
                Wait-UntilRetryIsDue -Transfer (Find-TransferByPrefix -State $retryState -Prefix $filePrefix)
                Invoke-DebugTransportCommand -Command "drain_outbox"
                Wait-ForState -Description "$DeliveryMode retry_once recovery" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $true `
                        -and [int]$transfer.file_entry_count -eq $scenarioDefaults.FileCount `
                        -and [int]$transfer.attempt_count -ge 2
                }
            }
            "timeout_once" {
                $retryState = Wait-ForState -Description "$DeliveryMode timeout_once retry scheduling" -TimeoutSeconds 240 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Queued" `
                        -and [long]$transfer.next_attempt_at -gt 0 `
                        -and [int]$transfer.attempt_count -eq 1
                }
                Wait-UntilRetryIsDue -Transfer (Find-TransferByPrefix -State $retryState -Prefix $filePrefix)
                Invoke-DebugTransportCommand -Command "drain_outbox"
                Wait-ForState -Description "$DeliveryMode timeout_once recovery" -TimeoutSeconds 240 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $true `
                        -and [int]$transfer.file_entry_count -eq $scenarioDefaults.FileCount `
                        -and [int]$transfer.attempt_count -ge 2
                }
            }
            "disconnect_once" {
                $retryState = Wait-ForState -Description "$DeliveryMode disconnect_once retry scheduling" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Queued" `
                        -and [long]$transfer.next_attempt_at -gt 0 `
                        -and [int]$transfer.attempt_count -eq 1
                }
                Wait-UntilRetryIsDue -Transfer (Find-TransferByPrefix -State $retryState -Prefix $filePrefix)
                Invoke-DebugTransportCommand -Command "drain_outbox"
                Wait-ForState -Description "$DeliveryMode disconnect_once recovery" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $true `
                        -and [int]$transfer.file_entry_count -eq $scenarioDefaults.FileCount `
                        -and [int]$transfer.attempt_count -ge 2
                }
            }
            "delayed_ack" {
                Wait-ForState -Description "$DeliveryMode delayed_ack delivery" -TimeoutSeconds 180 -Predicate {
                    param($State)
                    $transfer = Find-TransferByPrefix -State $State -Prefix $filePrefix
                    $transfer `
                        -and $transfer.status -eq "Delivered" `
                        -and $transfer.delivery_mode -eq $DeliveryMode `
                        -and $transfer.receipt_valid -eq $true `
                        -and [int]$transfer.file_entry_count -eq $scenarioDefaults.FileCount `
                        -and [int]$transfer.attempt_count -eq 1
                }
            }
            default {
                throw "Unsupported validation mode $mode"
            }
        }

        $matchedTransfer = Find-TransferByPrefix -State $finalState -Prefix $filePrefix
        if (-not $matchedTransfer) {
            throw "Failed to locate the completed transfer row for prefix $filePrefix."
        }
        $transferDirSummary = Get-TransferDirectorySummary `
            -InboxDir $companion.InboxDir `
            -MaterializedDir $matchedTransfer.materialized_dir `
            -TransferId $matchedTransfer.id
        if (-not $transferDirSummary.manifest_present -or -not $transferDirSummary.summary_present -or -not $transferDirSummary.received_present) {
            throw "Companion output for $DeliveryMode $mode is incomplete."
        }
        if ([int]$transferDirSummary.extracted_file_count -ne $scenarioDefaults.FileCount) {
            throw "Companion extracted $($transferDirSummary.extracted_file_count) files for $DeliveryMode $mode; expected $($scenarioDefaults.FileCount)."
        }

        $results.Add([pscustomobject]@{
            mode = $mode
            delivery_mode_profile = $DeliveryMode
            bootstrap = $bootstrap
            probe_ok = [bool]($probeState.recent_audits | Where-Object {
                $_.action -eq "devices.health_probe" `
                    -and $_.result -eq "ok" `
                    -and $_.details -like "*$deviceName*"
            } | Select-Object -First 1)
            latest_device = $finalState.latest_device
            latest_transfer = $matchedTransfer
            transfer_dir = $transferDirSummary
            recent_audits = @($finalState.recent_audits | Select-Object -First 10)
        }) | Out-Null
    }

    [pscustomobject]@{
        delivery_mode_profile = $DeliveryMode
        companion_inbox_dir = $companion.InboxDir
        validation_modes = $effectiveValidationModes
        validations = $results
    } | ConvertTo-Json -Depth 8
} finally {
    if ($companion -and -not $LeaveCompanionRunning) {
        Stop-ValidationCompanion -Process $companion.Process
    }
}
