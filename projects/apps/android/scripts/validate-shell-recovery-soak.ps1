param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$BasePort = 8820,
    [int]$Iterations = 2,
    [int]$PauseBetweenIterationsSeconds = 5,
    [int]$PollIntervalSeconds = 2,
    [int]$RecoveryDelaySeconds = 15,
    [int]$BackgroundPauseSeconds = 4,
    [string]$SummaryPath,
    [switch]$SkipManualRecovery,
    [switch]$SkipLifecycleRecovery,
    [switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$manualRecoveryScript = Join-Path $scriptDir "validate-shell-recovery.ps1"
$lifecycleRecoveryScript = Join-Path $scriptDir "validate-shell-lifecycle-recovery.ps1"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"

if (-not (Test-Path $manualRecoveryScript)) {
    throw "validate-shell-recovery.ps1 was not found at $manualRecoveryScript"
}
if (-not (Test-Path $lifecycleRecoveryScript)) {
    throw "validate-shell-lifecycle-recovery.ps1 was not found at $lifecycleRecoveryScript"
}
if ($SkipManualRecovery -and $SkipLifecycleRecovery) {
    throw "At least one validation path must remain enabled."
}

function Write-SoakStep {
    param([string]$Message)

    Write-Host "[validate-shell-recovery-soak] $Message"
}

function Invoke-RecoveryValidation {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [int]$Port,
        [hashtable]$ExtraParameters = @{}
    )

    $startedAt = Get-Date
    Write-SoakStep "Starting $Name on port $Port"
    try {
        $arguments = @(
            "-NoProfile",
            "-File",
            $ScriptPath,
            "-Serial",
            $script:ResolvedSerial,
            "-EndpointPreset",
            $EndpointPreset,
            "-Port",
            $Port,
            "-PollIntervalSeconds",
            $PollIntervalSeconds
        )
        foreach ($entry in $ExtraParameters.GetEnumerator()) {
            $arguments += @("-$($entry.Key)", [string]$entry.Value)
        }
        $stdoutPath = Join-Path $env:TEMP ("makoion-soak-{0}-{1}.stdout.log" -f $sessionTag, $Port)
        $stderrPath = Join-Path $env:TEMP ("makoion-soak-{0}-{1}.stderr.log" -f $sessionTag, $Port)
        if (Test-Path $stdoutPath) {
            Remove-Item $stdoutPath -Force
        }
        if (Test-Path $stderrPath) {
            Remove-Item $stderrPath -Force
        }
        $process = Start-Process pwsh `
            -ArgumentList $arguments `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -Wait `
            -PassThru `
            -NoNewWindow
        $stdout = if (Test-Path $stdoutPath) { Get-Content -Raw $stdoutPath } else { "" }
        $stderr = if (Test-Path $stderrPath) { Get-Content -Raw $stderrPath } else { "" }
        if ($process.ExitCode -ne 0) {
            $message = ($stderr.Trim(), $stdout.Trim() | Where-Object { $_ }) -join " "
            if ([string]::IsNullOrWhiteSpace($message)) {
                $message = "$Name exited with code $($process.ExitCode)."
            }
            throw $message
        }
        $completedAt = Get-Date
        Write-SoakStep "Completed $Name in $([math]::Round(($completedAt - $startedAt).TotalSeconds, 1))s"
        return [pscustomobject]@{
            name = $Name
            script = $ScriptPath
            port = $Port
            started_at = $startedAt.ToString("o")
            completed_at = $completedAt.ToString("o")
            duration_seconds = [math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
            succeeded = $true
            output = $stdout.Trim()
            error = $null
        }
    } catch {
        $failedAt = Get-Date
        $message = $_.Exception.Message
        Write-SoakStep "FAILED $Name in $([math]::Round(($failedAt - $startedAt).TotalSeconds, 1))s: $message"
        return [pscustomobject]@{
            name = $Name
            script = $ScriptPath
            port = $Port
            started_at = $startedAt.ToString("o")
            completed_at = $failedAt.ToString("o")
            duration_seconds = [math]::Round(($failedAt - $startedAt).TotalSeconds, 3)
            succeeded = $false
            output = $null
            error = $message
        }
    }
}

function Resolve-TargetSerial {
    param([string]$RequestedSerial)

    if ($RequestedSerial) {
        return $RequestedSerial
    }

    $adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adbPath)) {
        throw "adb.exe was not found at $adbPath"
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

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$startedAt = Get-Date
$iterationResults = [System.Collections.Generic.List[object]]::new()
$haltedEarly = $false
$haltReason = $null
$portCursor = $BasePort

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    Write-SoakStep "Iteration $iteration / $Iterations"
    $checks = [System.Collections.Generic.List[object]]::new()

    if (-not $SkipManualRecovery) {
        $manualResult = Invoke-RecoveryValidation `
            -Name "manual_recovery" `
            -ScriptPath $manualRecoveryScript `
            -Port $portCursor
        $checks.Add($manualResult) | Out-Null
        $portCursor += 1
        if (-not $manualResult.succeeded -and -not $ContinueOnFailure) {
            $haltedEarly = $true
            $haltReason = "manual_recovery failed during iteration $iteration"
        }
    }

    if (-not $haltedEarly -and -not $SkipLifecycleRecovery) {
        $lifecycleResult = Invoke-RecoveryValidation `
            -Name "lifecycle_recovery" `
            -ScriptPath $lifecycleRecoveryScript `
            -Port $portCursor `
            -ExtraParameters @{
                RecoveryDelaySeconds = $RecoveryDelaySeconds
                BackgroundPauseSeconds = $BackgroundPauseSeconds
            }
        $checks.Add($lifecycleResult) | Out-Null
        $portCursor += 1
        if (-not $lifecycleResult.succeeded -and -not $ContinueOnFailure) {
            $haltedEarly = $true
            $haltReason = "lifecycle_recovery failed during iteration $iteration"
        }
    }

    $iterationResults.Add([pscustomobject]@{
        iteration = $iteration
        started_at = ($checks | Select-Object -First 1).started_at
        completed_at = ($checks | Select-Object -Last 1).completed_at
        all_succeeded = -not ($checks.succeeded -contains $false)
        checks = $checks
    }) | Out-Null

    if ($haltedEarly) {
        break
    }

    if ($iteration -lt $Iterations -and $PauseBetweenIterationsSeconds -gt 0) {
        Write-SoakStep "Sleeping $PauseBetweenIterationsSeconds second(s) before the next iteration"
        Start-Sleep -Seconds $PauseBetweenIterationsSeconds
    }
}

$completedAt = Get-Date
$summary = [pscustomobject]@{
    serial = $script:ResolvedSerial
    endpoint_preset = $EndpointPreset
    base_port = $BasePort
    iterations_requested = $Iterations
    iterations_completed = $iterationResults.Count
    started_at = $startedAt.ToString("o")
    completed_at = $completedAt.ToString("o")
    duration_seconds = [math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
    all_succeeded = -not ($iterationResults.all_succeeded -contains $false)
    halted_early = $haltedEarly
    halt_reason = $haltReason
    results = $iterationResults
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
if ($SummaryPath) {
    $summaryDir = Split-Path -Parent $SummaryPath
    if ($summaryDir) {
        New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
    }
    Set-Content -Path $SummaryPath -Value $summaryJson
    Write-SoakStep "Wrote soak summary to $SummaryPath"
}

if (-not $summary.all_succeeded -and -not $ContinueOnFailure) {
    if ($haltReason) {
        throw $haltReason
    }
    throw "Shell recovery soak failed."
}

Write-SoakStep "Shell recovery soak completed successfully"
Write-Output $summaryJson
