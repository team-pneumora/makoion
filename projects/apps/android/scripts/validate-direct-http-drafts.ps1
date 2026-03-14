param(
    [string]$Serial,
    [ValidateSet(
        "normal",
        "partial_receipt",
        "malformed_receipt",
        "retry_once",
        "timeout_once",
        "disconnect_once",
        "delayed_ack"
    )]
    [string[]]$ValidationModes = @(
        "normal",
        "partial_receipt",
        "malformed_receipt",
        "retry_once",
        "timeout_once",
        "disconnect_once",
        "delayed_ack"
    ),
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$Port = 8787,
    [int]$PollIntervalSeconds = 2,
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
        }
    try:
        receipt = json.loads(receipt_json)
    except Exception:
        return {
            "receipt_valid": False,
            "receipt_issue": "receipt_json was not parseable",
            "status_detail": None,
            "receipt_delivery_mode": None,
        }
    return {
        "receipt_valid": receipt.get("receipt_valid"),
        "receipt_issue": receipt.get("receipt_issue"),
        "status_detail": receipt.get("status_detail"),
        "receipt_delivery_mode": receipt.get("delivery_mode"),
        }

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

latest_transfer_row = connection.execute(
    """
    SELECT id, device_id, device_name, status, attempt_count, file_names_json, delivery_mode, receipt_json,
           next_attempt_at, last_error, created_at, updated_at
    FROM transfer_outbox
    ORDER BY created_at DESC
    LIMIT 1
    """
).fetchone()
latest_transfer = row_to_dict(latest_transfer_row)
if latest_transfer is not None:
    latest_transfer["file_names"] = parse_file_names(latest_transfer.pop("file_names_json"))
    latest_transfer.update(parse_receipt(latest_transfer.pop("receipt_json")))

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
        LIMIT 32
        """
    ).fetchall()
]

connection.close()

print(json.dumps({
    "latest_device": latest_device,
    "latest_transfer": latest_transfer,
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

function Ensure-Companion {
    param([int]$PortNumber)

    try {
        $health = Wait-CompanionHealth -PortNumber $PortNumber
        return [pscustomobject]@{
            Started = $false
            Process = $null
            Health = $health
        }
    } catch {
        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $stdoutPath = Join-Path $env:TEMP "makoion-companion-$timestamp.log"
        $stderrPath = Join-Path $env:TEMP "makoion-companion-$timestamp.err.log"
        $process = Start-Process pwsh -ArgumentList @(
            "-NoProfile",
            "-File",
            $companionScript,
            "--host",
            "127.0.0.1",
            "--port",
            $PortNumber
        ) -WorkingDirectory $companionDir -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
        $health = Wait-CompanionHealth -PortNumber $PortNumber
        return [pscustomobject]@{
            Started = $true
            Process = $process
            Health = $health
            StdoutPath = $stdoutPath
            StderrPath = $stderrPath
        }
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
    param()

    $dbPath = Join-Path $env:TEMP ("makoion-transport-validation-{0}.db" -f ([guid]::NewGuid()))
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
    param([object]$State)

    $nextAttemptAt = $State.latest_transfer.next_attempt_at
    if (-not $nextAttemptAt) {
        Start-Sleep -Seconds 16
        return
    }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $delayMs = [math]::Max(0, [long]$nextAttemptAt - $now + 1000)
    Start-Sleep -Milliseconds $delayMs
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$companion = Ensure-Companion -PortNumber $Port
$results = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($mode in $ValidationModes) {
        $bootstrap = & pwsh -NoProfile -File $bootstrapScript -Serial $script:ResolvedSerial -ValidationMode $mode -EndpointPreset $EndpointPreset -Port $Port | ConvertFrom-Json
        $filePrefix = "validate-$mode"
        Start-Sleep -Seconds 1
        $bootstrappedState = Read-ValidationState
        $deviceId = $bootstrappedState.latest_device.id
        if (-not $deviceId) {
            throw "No paired Direct HTTP device was found after bootstrap for mode $mode."
        }

        Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
            device_id = $deviceId
        }
        $probeState = Wait-ForState -Description "health probe success for $mode" -TimeoutSeconds 20 -Predicate {
            param($State)
            $State.latest_audit -and $State.latest_audit.action -eq "devices.health_probe" -and $State.latest_audit.result -eq "ok"
        }

        Invoke-DebugTransportCommand -Command "queue_debug_transfer" -StringExtras @{
            device_id = $deviceId
            file_prefix = $filePrefix
        } -IntExtras @{
            file_count = 1
        }

        $finalState = switch ($mode) {
            "normal" {
                $state = Wait-ForState -Description "delivered transfer for normal" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered"
                }
                if ($state.latest_transfer.receipt_valid -ne $true) {
                    throw "Expected a valid receipt for normal mode."
                }
                $state
            }
            "partial_receipt" {
                $state = Wait-ForState -Description "partial receipt review state" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and $transfer.receipt_valid -eq $false
                }
                $state
            }
            "malformed_receipt" {
                $state = Wait-ForState -Description "malformed receipt review state" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and $transfer.receipt_valid -eq $false
                }
                $state
            }
            "retry_once" {
                $retryState = Wait-ForState -Description "retry scheduling for retry_once" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                Wait-UntilRetryIsDue -State $retryState
                Invoke-DebugTransportCommand -Command "drain_outbox"
                $state = Wait-ForState -Description "retry_once recovery delivery" -TimeoutSeconds 40 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
                }
                $state
            }
            "timeout_once" {
                $retryState = Wait-ForState -Description "retry scheduling for timeout_once" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                Wait-UntilRetryIsDue -State $retryState
                Invoke-DebugTransportCommand -Command "drain_outbox"
                $state = Wait-ForState -Description "timeout_once recovery delivery" -TimeoutSeconds 40 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
                }
                $state
            }
            "disconnect_once" {
                $retryState = Wait-ForState -Description "retry scheduling for disconnect_once" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                Wait-UntilRetryIsDue -State $retryState
                Invoke-DebugTransportCommand -Command "drain_outbox"
                $state = Wait-ForState -Description "disconnect_once recovery delivery" -TimeoutSeconds 40 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
                }
                $state
            }
            "delayed_ack" {
                $retryState = Wait-ForState -Description "retry scheduling for delayed_ack" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                Invoke-DebugTransportCommand -Command "set_validation_mode" -StringExtras @{
                    device_id = $deviceId
                    validation_mode = "normal"
                }
                Wait-UntilRetryIsDue -State $retryState
                Invoke-DebugTransportCommand -Command "drain_outbox"
                $state = Wait-ForState -Description "delayed_ack recovery delivery" -TimeoutSeconds 45 -Predicate {
                    param($State)
                    $transfer = $State.latest_transfer
                    $transfer -and $transfer.file_names[0] -eq "$filePrefix-1.txt" -and $transfer.status -eq "Delivered" -and [int]$transfer.attempt_count -ge 2
                }
                $state
            }
            default {
                throw "Unsupported validation mode $mode"
            }
        }

        $results.Add([pscustomobject]@{
            mode = $mode
            bootstrap = $bootstrap
            latest_device = $finalState.latest_device
            latest_transfer = $finalState.latest_transfer
            recent_audits = $finalState.recent_audits
            probe_ok = [bool]($probeState.latest_audit -and $probeState.latest_audit.action -eq "devices.health_probe" -and $probeState.latest_audit.result -eq "ok")
        }) | Out-Null
    }

    $results | ConvertTo-Json -Depth 8
} finally {
    if ($companion.Started -and -not $LeaveCompanionRunning) {
        Stop-ValidationCompanion -Process $companion.Process
    }
}
