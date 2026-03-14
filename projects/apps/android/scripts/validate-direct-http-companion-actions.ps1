param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [ValidateSet("inbox", "latest_transfer", "actions_folder")]
    [string[]]$AppOpenTargetKinds = @(
        "inbox",
        "latest_transfer",
        "actions_folder"
    ),
    [ValidateSet("record_only", "best_effort")]
    [string]$AppOpenMode = "record_only",
    [ValidateSet("open_latest_transfer", "open_actions_folder")]
    [string[]]$WorkflowIds = @(
        "open_latest_transfer",
        "open_actions_folder"
    ),
    [ValidateSet("record_only", "best_effort")]
    [string]$WorkflowRunMode = "record_only",
    [int]$Port = 8795,
    [int]$PollIntervalSeconds = 2,
    [switch]$LeaveCompanionRunning,
    [switch]$AllowRecordedOnlyNotify
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
$actionsDirName = "actions"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$inboxPath = Join-Path $companionDir "inbox-validate-companion-actions-$sessionTag"
$stdoutLog = Join-Path $companionDir "companion-actions-$sessionTag.log"
$stderrLog = Join-Path $companionDir "companion-actions-$sessionTag.err"
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
    $dbPath = Join-Path $env:TEMP ("makoion-companion-actions-{0}.db" -f ([guid]::NewGuid()))
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
    $lastAudit = $lastState.recent_audits | Select-Object -First 1 | ConvertTo-Json -Compress -Depth 4
    throw "Timed out while waiting for $Description. Last audit state: $lastAudit"
}

function Write-ValidationStep {
    param([string]$Message)

    Write-Host "[validate-companion-actions] $Message"
}

function Initialize-AppOpenTarget {
    param([string]$TargetKind)

    if ($TargetKind -eq "latest_transfer") {
        $seedTransferDir = Join-Path $inboxPath "99991231-235959-transfer-seed"
        New-Item -ItemType Directory -Force -Path $seedTransferDir | Out-Null
        Set-Content -Path (Join-Path $seedTransferDir "summary.txt") -Value "Seed transfer for app.open validation."
    } elseif ($TargetKind -eq "actions_folder") {
        New-Item -ItemType Directory -Force -Path (Join-Path $inboxPath $actionsDirName) | Out-Null
    }
}

function Initialize-WorkflowTarget {
    param([string]$WorkflowId)

    if ($WorkflowId -eq "open_latest_transfer") {
        $seedTransferDir = Join-Path $inboxPath "99991231-235959-transfer-seed"
        New-Item -ItemType Directory -Force -Path $seedTransferDir | Out-Null
        Set-Content -Path (Join-Path $seedTransferDir "summary.txt") -Value "Seed transfer for workflow.run validation."
    } elseif ($WorkflowId -eq "open_actions_folder") {
        New-Item -ItemType Directory -Force -Path (Join-Path $inboxPath $actionsDirName) | Out-Null
    }
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

    $audit = $state.recent_audits |
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

    return [pscustomobject]@{
        Audit = $audit
        State = $state
    }
}

function Get-ActionDirectories {
    param([string]$Prefix)

    $actionsRoot = Join-Path $inboxPath $actionsDirName
    if (-not (Test-Path $actionsRoot)) {
        return @()
    }
    return Get-ChildItem -Path $actionsRoot -Directory |
        Where-Object { $_.Name -like "*-$Prefix-*" } |
        Sort-Object Name
}

function Wait-ForMaterializedAction {
    param(
        [string]$Prefix,
        [int]$PreviousCount,
        [string]$SummaryNeedle,
        [string]$RequestNeedle,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $directories = @(Get-ActionDirectories -Prefix $Prefix)
        if ($directories.Count -gt $PreviousCount) {
            foreach ($candidate in ($directories | Select-Object -Skip $PreviousCount)) {
                $requestPath = Join-Path $candidate.FullName "request.json"
                $summaryPath = Join-Path $candidate.FullName "summary.txt"
                if ((Test-Path $requestPath) -and (Test-Path $summaryPath)) {
                    $summary = Get-Content -Raw $summaryPath
                    $request = Get-Content -Raw $requestPath
                    if (
                        ([string]::IsNullOrWhiteSpace($SummaryNeedle) -or $summary -like "*$SummaryNeedle*") -and
                        ([string]::IsNullOrWhiteSpace($RequestNeedle) -or $request -like "*$RequestNeedle*")
                    ) {
                        return [pscustomobject]@{
                            Directory = $candidate
                            Summary = $summary.Trim()
                            Request = $request.Trim()
                        }
                    }
                }
            }
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
    throw "Timed out while waiting for a materialized $Prefix action."
}

function Invoke-SessionNotifyValidation {
    param([string]$DeviceId)

    $initialState = Read-ValidationState
    $beforeAudit = Get-LatestAuditTimestamp -State $initialState -Action "devices.session_notify"
    $beforeCount = @(Get-ActionDirectories -Prefix "notify").Count
    $bodyMarker = "session-notify-validation-$sessionTag"
    Write-ValidationStep "Sending session.notify probe with marker $bodyMarker"

    Invoke-DebugTransportCommand -Command "send_session_notification" -StringExtras @{
        device_id = $DeviceId
        body = $bodyMarker
    }

    $auditEvent = Wait-ForAuditEvent -Action "devices.session_notify" -ExpectedResults @("delivered") -AfterCreatedAt $beforeAudit -DetailsNeedle "" -TimeoutSeconds 20
    $materialized = Wait-ForMaterializedAction -Prefix "notify" -PreviousCount $beforeCount -SummaryNeedle $bodyMarker -RequestNeedle $bodyMarker -TimeoutSeconds 20
    if (-not $AllowRecordedOnlyNotify -and $materialized.Summary -notlike "*Displayed: True*" -and $materialized.Summary -notlike "*Displayed: true*") {
        throw "session.notify summary did not confirm Displayed: true"
    }
    Write-ValidationStep "Session.notify materialized in $($materialized.Directory.Name)"

    return [pscustomobject]@{
        action = "session.notify"
        audit = $auditEvent.Audit
        materialized_dir = $materialized.Directory.Name
        summary = $materialized.Summary
        notification_displayed = -not $AllowRecordedOnlyNotify
    }
}

function Invoke-AppOpenValidation {
    param(
        [string]$DeviceId,
        [string]$TargetKind
    )

    $initialState = Read-ValidationState
    $beforeAudit = Get-LatestAuditTimestamp -State $initialState -Action "devices.app_open"
    $beforeCount = @(Get-ActionDirectories -Prefix "app-open").Count
    Initialize-AppOpenTarget -TargetKind $TargetKind
    Write-ValidationStep "Sending app.open probe for $TargetKind in $AppOpenMode mode"

    Invoke-DebugTransportCommand -Command "send_app_open" -StringExtras ([ordered]@{
        device_id = $DeviceId
        target_kind = $TargetKind
        open_mode = $AppOpenMode
    })

    $expectedAuditResult = if ($AppOpenMode -eq "record_only") { "recorded" } else { "opened" }
    $auditEvent = Wait-ForAuditEvent -Action "devices.app_open" -ExpectedResults @($expectedAuditResult) -AfterCreatedAt $beforeAudit -DetailsNeedle $TargetKind -TimeoutSeconds 20
    $materialized = Wait-ForMaterializedAction -Prefix "app-open" -PreviousCount $beforeCount -SummaryNeedle "Target kind: $TargetKind" -RequestNeedle ('"target_kind":"{0}"' -f $TargetKind) -TimeoutSeconds 20
    Write-ValidationStep "App.open materialized in $($materialized.Directory.Name)"

    return [pscustomobject]@{
        action = "app.open"
        target_kind = $TargetKind
        open_mode = $AppOpenMode
        audit = $auditEvent.Audit
        materialized_dir = $materialized.Directory.Name
        summary = $materialized.Summary
    }
}

function Invoke-WorkflowValidation {
    param(
        [string]$DeviceId,
        [string]$WorkflowId
    )

    $workflowLabel = if ($WorkflowId -eq "open_actions_folder") {
        "Open actions folder"
    } else {
        "Open latest transfer"
    }

    $initialState = Read-ValidationState
    $beforeAudit = Get-LatestAuditTimestamp -State $initialState -Action "devices.workflow_run"
    $beforeCount = @(Get-ActionDirectories -Prefix "workflow-run").Count
    Initialize-WorkflowTarget -WorkflowId $WorkflowId
    Write-ValidationStep "Sending workflow.run probe for $WorkflowId in $WorkflowRunMode mode"

    Invoke-DebugTransportCommand -Command "run_workflow" -StringExtras ([ordered]@{
        device_id = $DeviceId
        workflow_id = $WorkflowId
        run_mode = $WorkflowRunMode
    })

    $expectedAuditResult = if ($WorkflowRunMode -eq "record_only") { "recorded" } else { "completed" }
    $auditEvent = Wait-ForAuditEvent -Action "devices.workflow_run" -ExpectedResults @($expectedAuditResult) -AfterCreatedAt $beforeAudit -DetailsNeedle $WorkflowId -TimeoutSeconds 20
    $materialized = Wait-ForMaterializedAction -Prefix "workflow-run" -PreviousCount $beforeCount -SummaryNeedle "Workflow id: $WorkflowId" -RequestNeedle ('"run_mode":"{0}"' -f $WorkflowRunMode) -TimeoutSeconds 20
    if ($WorkflowRunMode -eq "record_only" -and $materialized.Summary -notlike "*Executed: False*" -and $materialized.Summary -notlike "*Executed: false*") {
        throw "workflow.run summary did not confirm Executed: false for record_only"
    }
    if ($WorkflowRunMode -eq "best_effort" -and $materialized.Summary -notlike "*Executed: True*" -and $materialized.Summary -notlike "*Executed: true*") {
        throw "workflow.run summary did not confirm Executed: true for best_effort"
    }
    Write-ValidationStep "Workflow.run materialized in $($materialized.Directory.Name)"

    return [pscustomobject]@{
        action = "workflow.run"
        workflow_id = $WorkflowId
        run_mode = $WorkflowRunMode
        audit = $auditEvent.Audit
        materialized_dir = $materialized.Directory.Name
        summary = $materialized.Summary
    }
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$companion = $null
$results = [System.Collections.Generic.List[object]]::new()

try {
    Write-ValidationStep "Starting desktop companion on port $Port"
    $companion = Start-ValidationCompanion -PortNumber $Port -InboxDirectory $inboxPath
    Write-ValidationStep "Bootstrapping Direct HTTP device against $($companion.Health.endpoint)"
    $bootstrap = & pwsh -NoProfile -File $bootstrapScript -Serial $script:ResolvedSerial -ValidationMode "normal" -EndpointPreset $EndpointPreset -Port $Port | ConvertFrom-Json
    Start-Sleep -Seconds 1

    $bootstrappedState = Wait-ForState -Description "bootstrapped Direct HTTP device" -TimeoutSeconds 20 -Predicate {
        param($State)
        $State.latest_device -and
            $State.latest_device.transport_mode -eq "DirectHttp" -and
            $State.latest_device.endpoint_url -eq $bootstrap.endpoint
    }
    $deviceId = $bootstrappedState.latest_device.id
    if (-not $deviceId) {
        throw "No paired Direct HTTP device was found after bootstrap."
    }
    Write-ValidationStep "Bootstrapped device $deviceId at endpoint $($bootstrap.endpoint)"

    $beforeProbeAudit = Get-LatestAuditTimestamp -State $bootstrappedState -Action "devices.health_probe"
    Write-ValidationStep "Probing companion health from the device"
    Invoke-DebugTransportCommand -Command "probe_health" -StringExtras @{
        device_id = $deviceId
    }
    $healthProbe = Wait-ForAuditEvent -Action "devices.health_probe" -ExpectedResults @("ok") -AfterCreatedAt $beforeProbeAudit -DetailsNeedle "" -TimeoutSeconds 20
    Write-ValidationStep "Companion health probe succeeded; starting action validations"

    $results.Add((Invoke-SessionNotifyValidation -DeviceId $deviceId)) | Out-Null
    foreach ($targetKind in $AppOpenTargetKinds) {
        $results.Add((Invoke-AppOpenValidation -DeviceId $deviceId -TargetKind $targetKind)) | Out-Null
    }
    foreach ($workflowId in $WorkflowIds) {
        $results.Add((Invoke-WorkflowValidation -DeviceId $deviceId -WorkflowId $workflowId)) | Out-Null
    }

    $result = [pscustomobject]@{
        bootstrap = $bootstrap
        companion_health = $companion.Health
        health_probe_audit = $healthProbe.Audit
        validations = $results
    } | ConvertTo-Json -Depth 8
    Write-ValidationStep "Companion action validation completed successfully"
    Write-Output $result
} finally {
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
