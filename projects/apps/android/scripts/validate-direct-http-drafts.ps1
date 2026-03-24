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
    [int]$CompanionHealthTimeoutSeconds = 60,
    [int]$PollIntervalSeconds = 2,
    [switch]$LeaveCompanionRunning,
    [switch]$SkipCompanionRestartOnRetryModes
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
$powerShellPath = (Get-Process -Id $PID).Path
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$traceLog = Join-Path $companionDir "direct-http-drafts-$sessionTag.trace.log"
$validationStateRemotePath = "files/debug-validation-state.json"
$script:CompanionStartCounter = 0

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

    $line = "[validate-direct-http-drafts] $Message"
    Write-Host $line
    Add-Content -Path $traceLog -Value $line
}

function Get-LogExcerpt {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return ""
    }

    return ((Get-Content -Path $Path -Tail 40) -join [Environment]::NewLine).Trim()
}

function Wait-CompanionHealth {
    param(
        [int]$PortNumber,
        [System.Diagnostics.Process]$Process,
        [string]$StdoutPath,
        [string]$StderrPath,
        [int]$TimeoutSeconds
    )

    $lastError = $null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            $stderr = Get-LogExcerpt -Path $StderrPath
            $stdout = Get-LogExcerpt -Path $StdoutPath
            throw "Companion process exited before /health became ready (exit code $($Process.ExitCode)). stderr: $stderr stdout: $stdout".Trim()
        }
        try {
            return Invoke-RestMethod -Uri "http://127.0.0.1:$PortNumber/health" -TimeoutSec 2
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Milliseconds 500
        }
    }
    $stderr = Get-LogExcerpt -Path $StderrPath
    $stdout = Get-LogExcerpt -Path $StdoutPath
    throw "Companion health check on 127.0.0.1:$PortNumber did not become ready within $TimeoutSeconds seconds. Last error: $lastError stderr: $stderr stdout: $stdout".Trim()
}

function Start-ValidationCompanion {
    param([int]$PortNumber)

    $script:CompanionStartCounter += 1
    $companionTag = "{0}-{1:00}" -f $sessionTag, $script:CompanionStartCounter
    $stdoutPath = Join-Path $companionDir "direct-http-drafts-$companionTag.log"
    $stderrPath = Join-Path $companionDir "direct-http-drafts-$companionTag.err"
    $process = Start-Process $powerShellPath -ArgumentList @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $companionScript,
        "--host",
        "127.0.0.1",
        "--port",
        $PortNumber
    ) -WorkingDirectory $companionDir -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $health = Wait-CompanionHealth `
        -PortNumber $PortNumber `
        -Process $process `
        -StdoutPath $stdoutPath `
        -StderrPath $stderrPath `
        -TimeoutSeconds $CompanionHealthTimeoutSeconds
    return [pscustomobject]@{
        Started = $true
        Process = $process
        Health = $health
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
    }
}

function Ensure-Companion {
    param([int]$PortNumber)

    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$PortNumber/health" -TimeoutSec 2
        return [pscustomobject]@{
            Started = $false
            Process = $null
            Health = $health
            StdoutPath = $null
            StderrPath = $null
        }
    } catch {
        return Start-ValidationCompanion -PortNumber $PortNumber
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

function Restart-ValidationCompanion {
    param(
        [object]$Companion,
        [int]$PortNumber
    )

    if (-not $Companion.Process) {
        throw "Companion restart validation requires a script-owned companion process on port $PortNumber."
    }
    Write-ValidationStep "Restarting companion on port $PortNumber to validate retry drain after recovery"
    Stop-ValidationCompanion -Process $Companion.Process
    Start-Sleep -Seconds 1
    return Start-ValidationCompanion -PortNumber $PortNumber
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
        "--include-stopped-packages",
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

function Pull-AppFileFromDevice {
    param(
        [string]$RemotePath,
        [string]$DestinationPath
    )

    if (Test-Path $DestinationPath) {
        Remove-Item $DestinationPath -Force
    }

    for ($attempt = 1; $attempt -le 4; $attempt++) {
        $process = Start-Process $adbPath -ArgumentList @(
            "-s",
            $script:ResolvedSerial,
            "exec-out",
            "run-as",
            $packageName,
            "cat",
            $RemotePath
        ) -RedirectStandardOutput $DestinationPath -RedirectStandardError "$DestinationPath.err" -Wait -PassThru -NoNewWindow

        if ($process.ExitCode -eq 0 -and (Test-Path $DestinationPath) -and (Get-Item $DestinationPath).Length -gt 0) {
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
        if ($attempt -lt 4 -and $stderr -match "device '.*' not found|no devices/emulators found|No such file or directory") {
            Start-Sleep -Seconds 2
            continue
        }
        throw "Failed to pull $RemotePath from the device. $stderr".Trim()
    }
}

function Read-ValidationState {
    $lastError = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $jsonPath = Join-Path $env:TEMP ("makoion-transport-validation-{0}.json" -f ([guid]::NewGuid()))
        try {
            Invoke-DebugTransportCommand -Command "dump_validation_state" -BoolExtras @{ open_devices_after_command = $false }
            Start-Sleep -Milliseconds 300
            Pull-AppFileFromDevice -RemotePath $validationStateRemotePath -DestinationPath $jsonPath
            $stateJson = Get-Content -Raw $jsonPath
            if ([string]::IsNullOrWhiteSpace($stateJson)) {
                throw "Validation state snapshot was empty."
            }
            return $stateJson | ConvertFrom-Json
        } catch {
            $lastError = $_.Exception.Message
            if ($attempt -lt 5) {
                Start-Sleep -Milliseconds 300
            }
        } finally {
            if (Test-Path $jsonPath) {
                Remove-Item $jsonPath -Force
            }
        }
    }
    throw "Failed to read validation state from the device. $lastError".Trim()
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
    $lastTransfer = if ($lastState.latest_transfer) { $lastState.latest_transfer | ConvertTo-Json -Compress -Depth 6 } else { "{}" }
    $lastAudit = if ($lastState.latest_audit) { $lastState.latest_audit | ConvertTo-Json -Compress -Depth 4 } else { "{}" }
    throw "Timed out while waiting for $Description. Last transfer state: $lastTransfer Latest audit: $lastAudit"
}

function Wait-UntilRetryIsDue {
    param([object]$State)

    $transfer = $State.latest_transfer
    if (-not $transfer) {
        $transfer = @($State.recent_transfers | Select-Object -First 1)[0]
    }
    $nextAttemptAt = $transfer.next_attempt_at
    if (-not $nextAttemptAt) {
        Start-Sleep -Seconds 16
        return
    }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $delayMs = [math]::Max(0, [long]$nextAttemptAt - $now + 1000)
    Start-Sleep -Milliseconds $delayMs
}

function Wait-ForDeliveredRetryRecovery {
    param(
        [string]$Description,
        [string]$FileName,
        [int]$TimeoutSeconds,
        [int]$MinimumAttemptCount = 2
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastState = $null
    while ((Get-Date) -lt $deadline) {
        $lastState = Read-ValidationState
        $transfer = Find-TransferByFileName -State $lastState -FileName $FileName
        if (
            $transfer -and
            $transfer.status -eq "Delivered" -and
            [int]$transfer.attempt_count -ge $MinimumAttemptCount -and
            $lastState.latest_device.status -eq "Bridge active"
        ) {
            return $lastState
        }

        if ($transfer -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0) {
            Wait-UntilRetryIsDue -State ([pscustomobject]@{
                latest_transfer = $transfer
                recent_transfers = @($transfer)
            })
            Invoke-DebugTransportCommand -Command "drain_outbox"
            Start-Sleep -Seconds 1
            continue
        }

        Start-Sleep -Seconds $PollIntervalSeconds
    }

    $lastTransfer = if ($lastState) { Find-TransferByFileName -State $lastState -FileName $FileName } else { $null }
    $lastTransferJson = if ($lastTransfer) { $lastTransfer | ConvertTo-Json -Compress -Depth 6 } else { "{}" }
    throw "Timed out while waiting for $Description. Last matched transfer state: $lastTransferJson"
}

function Invoke-BootstrapValidation {
    param([string]$Mode)

    return (& $powerShellPath -NoProfile -ExecutionPolicy Bypass -File $bootstrapScript -Serial $script:ResolvedSerial -ValidationMode $Mode -EndpointPreset $EndpointPreset -Port $Port) | ConvertFrom-Json
}

function Find-TransferByFileName {
    param(
        [object]$State,
        [string]$FileName
    )

    if ($State.latest_transfer -and $State.latest_transfer.file_names -contains $FileName) {
        return $State.latest_transfer
    }

    $recentTransfers = @()
    if ($State.recent_transfers) {
        $recentTransfers = @($State.recent_transfers)
    }
    return $recentTransfers | Where-Object { $_.file_names -contains $FileName } | Select-Object -First 1
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
Wait-ForResolvedDevice
$companion = Ensure-Companion -PortNumber $Port
$results = [System.Collections.Generic.List[object]]::new()

try {
    Write-ValidationStep "Using Android target $($script:ResolvedSerial) on port $Port"
    foreach ($mode in $ValidationModes) {
        Write-ValidationStep "Bootstrapping Direct HTTP validation mode $mode"
        $bootstrap = Invoke-BootstrapValidation -Mode $mode
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
        Start-Sleep -Seconds 3
        $probeState = Read-ValidationState

        Invoke-DebugTransportCommand -Command "queue_debug_transfer" -StringExtras @{
            device_id = $deviceId
            file_prefix = $filePrefix
        } -IntExtras @{
            file_count = 1
        }

        $companionRestarted = $false
        $finalState = switch ($mode) {
            "normal" {
                $state = Wait-ForState -Description "delivered transfer for normal" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Delivered" -and $State.latest_device.status -eq "Bridge active"
                }
                $state
            }
            "partial_receipt" {
                Wait-ForState -Description "partial receipt review state" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Delivered" -and $State.latest_device.status -eq "Receipt review"
                }
            }
            "malformed_receipt" {
                Wait-ForState -Description "malformed receipt review state" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Delivered" -and $State.latest_device.status -eq "Receipt review"
                }
            }
            "retry_once" {
                $retryState = Wait-ForState -Description "retry scheduling for retry_once" -TimeoutSeconds 20 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                if (-not $SkipCompanionRestartOnRetryModes) {
                    $companion = Restart-ValidationCompanion -Companion $companion -PortNumber $Port
                    $companionRestarted = $true
                }
                Wait-ForDeliveredRetryRecovery -Description "retry_once recovery delivery" -FileName "$filePrefix-1.txt" -TimeoutSeconds 90
            }
            "timeout_once" {
                $retryState = Wait-ForState -Description "retry scheduling for timeout_once" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                if (-not $SkipCompanionRestartOnRetryModes) {
                    $companion = Restart-ValidationCompanion -Companion $companion -PortNumber $Port
                    $companionRestarted = $true
                }
                Wait-ForDeliveredRetryRecovery -Description "timeout_once recovery delivery" -FileName "$filePrefix-1.txt" -TimeoutSeconds 120
            }
            "disconnect_once" {
                $retryState = Wait-ForState -Description "retry scheduling for disconnect_once" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                if (-not $SkipCompanionRestartOnRetryModes) {
                    $companion = Restart-ValidationCompanion -Companion $companion -PortNumber $Port
                    $companionRestarted = $true
                }
                Wait-ForDeliveredRetryRecovery -Description "disconnect_once recovery delivery" -FileName "$filePrefix-1.txt" -TimeoutSeconds 120
            }
            "delayed_ack" {
                $retryState = Wait-ForState -Description "retry scheduling for delayed_ack" -TimeoutSeconds 25 -Predicate {
                    param($State)
                    $transfer = Find-TransferByFileName -State $State -FileName "$filePrefix-1.txt"
                    $transfer -and $transfer.status -eq "Queued" -and [long]$transfer.next_attempt_at -gt 0
                }
                Invoke-DebugTransportCommand -Command "set_validation_mode" -StringExtras @{
                    device_id = $deviceId
                    validation_mode = "normal"
                }
                if (-not $SkipCompanionRestartOnRetryModes) {
                    $companion = Restart-ValidationCompanion -Companion $companion -PortNumber $Port
                    $companionRestarted = $true
                }
                Wait-ForDeliveredRetryRecovery -Description "delayed_ack recovery delivery" -FileName "$filePrefix-1.txt" -TimeoutSeconds 120
            }
            default {
                throw "Unsupported validation mode $mode"
            }
        }

        $results.Add([pscustomobject]@{
            mode = $mode
            bootstrap = $bootstrap
            latest_device = $finalState.latest_device
            latest_transfer = Find-TransferByFileName -State $finalState -FileName "$filePrefix-1.txt"
            latest_audit = $finalState.latest_audit
            recent_audits = $finalState.recent_audits
            probe_ok = [bool](
                @($probeState.recent_audits | Where-Object {
                    $_.action -eq "devices.health_probe" -and $_.result -eq "ok"
                }).Count -gt 0
            )
            companion_restart_verified = [bool]$companionRestarted
        }) | Out-Null
    }

    $results | ConvertTo-Json -Depth 8
} finally {
    if ($companion.Started -and -not $LeaveCompanionRunning) {
        Stop-ValidationCompanion -Process $companion.Process
    }
    if (Test-Path $traceLog) {
        Remove-Item $traceLog -Force -ErrorAction SilentlyContinue
    }
}
