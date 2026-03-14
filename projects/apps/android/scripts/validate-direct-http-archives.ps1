param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$Port = 0,
    [int]$SmallFileCount = 2,
    [int]$SmallFileSizeKiB = 64,
    [int]$LargeFileCount = 1,
    [int]$LargeFileSizeKiB = 18432,
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
        LIMIT 24
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
    $inboxDir = Join-Path $companionDir "inbox-archive-validate-$timestamp"
    $stdoutPath = Join-Path $env:TEMP "makoion-archive-validation-$timestamp.log"
    $stderrPath = Join-Path $env:TEMP "makoion-archive-validation-$timestamp.err.log"
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
    $dbPath = Join-Path $env:TEMP ("makoion-archive-validation-{0}.db" -f ([guid]::NewGuid()))
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

function Get-TransferDirectorySummary {
    param(
        [string]$InboxDir,
        [string]$MaterializedDir
    )

    $transferDir = Join-Path $InboxDir $MaterializedDir
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

function Invoke-ArchiveValidation {
    param(
        [string]$DeviceId,
        [string]$Prefix,
        [int]$FileCount,
        [int]$FileSizeKiB,
        [string]$ExpectedDeliveryMode,
        [string]$Label,
        [string]$InboxDir
    )

    Invoke-DebugTransportCommand -Command "queue_debug_archive_transfer" -StringExtras @{
        device_id = $DeviceId
        file_prefix = $Prefix
    } -IntExtras @{
        file_count = $FileCount
        file_size_kib = $FileSizeKiB
    }

    $state = Wait-ForState -Description "$Label archive delivery" -TimeoutSeconds 180 -Predicate {
        param($CurrentState)
        $transfer = $CurrentState.latest_transfer
        $transfer `
            -and $transfer.file_names[0] -eq "$Prefix-1.bin" `
            -and $transfer.status -eq "Delivered" `
            -and $transfer.receipt_valid -eq $true `
            -and $transfer.delivery_mode -eq $ExpectedDeliveryMode `
            -and [int]$transfer.file_entry_count -eq $FileCount
    }

    $materializedDir = $state.latest_transfer.materialized_dir
    if (-not $materializedDir) {
        throw "Receipt for $Label archive delivery did not include materialized_dir."
    }
    $transferDirSummary = Get-TransferDirectorySummary -InboxDir $InboxDir -MaterializedDir $materializedDir
    if (-not $transferDirSummary.manifest_present -or -not $transferDirSummary.summary_present -or -not $transferDirSummary.received_present) {
        throw "Companion output for $Label archive delivery is incomplete."
    }
    if ([int]$transferDirSummary.extracted_file_count -ne $FileCount) {
        throw "Companion extracted $($transferDirSummary.extracted_file_count) files for $Label archive delivery; expected $FileCount."
    }

    return [pscustomobject]@{
        label = $Label
        latest_transfer = $state.latest_transfer
        latest_device = $state.latest_device
        transfer_dir = $transferDirSummary
        recent_audits = $state.recent_audits
    }
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$validationPort = Resolve-ValidationPort -RequestedPort $Port
$companion = Start-ValidationCompanion -PortNumber $validationPort

try {
    $bootstrap = & pwsh -NoProfile -File $bootstrapScript -Serial $script:ResolvedSerial -ValidationMode "normal" -EndpointPreset $EndpointPreset -Port $validationPort | ConvertFrom-Json
    Start-Sleep -Seconds 1
    $bootstrappedState = Read-ValidationState
    $deviceId = $bootstrappedState.latest_device.id
    if (-not $deviceId) {
        throw "No paired Direct HTTP device was found after archive bootstrap."
    }

    Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
        device_id = $deviceId
    }
    $probeState = Wait-ForState -Description "archive health probe success" -TimeoutSeconds 20 -Predicate {
        param($State)
        $State.recent_audits | Where-Object {
            $_.action -eq "devices.health_probe" -and $_.result -eq "ok"
        } | Select-Object -First 1
    }

    $smallResult = Invoke-ArchiveValidation `
        -DeviceId $deviceId `
        -Prefix "archive-small" `
        -FileCount $SmallFileCount `
        -FileSizeKiB $SmallFileSizeKiB `
        -ExpectedDeliveryMode "archive_zip" `
        -Label "small" `
        -InboxDir $companion.InboxDir

    $largeResult = Invoke-ArchiveValidation `
        -DeviceId $deviceId `
        -Prefix "archive-large" `
        -FileCount $LargeFileCount `
        -FileSizeKiB $LargeFileSizeKiB `
        -ExpectedDeliveryMode "archive_zip_streaming" `
        -Label "large" `
        -InboxDir $companion.InboxDir

    [pscustomobject]@{
        bootstrap = $bootstrap
        probe_ok = [bool]($probeState.recent_audits | Where-Object {
            $_.action -eq "devices.health_probe" -and $_.result -eq "ok"
        } | Select-Object -First 1)
        companion_health = $companion.Health
        companion_inbox_dir = $companion.InboxDir
        validations = @(
            $smallResult,
            $largeResult
        )
    } | ConvertTo-Json -Depth 8
} finally {
    if ($companion -and -not $LeaveCompanionRunning) {
        Stop-ValidationCompanion -Process $companion.Process
    }
}
