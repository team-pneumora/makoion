param(
    [string]$Serial,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse",
    [int]$BasePort = 8850,
    [int]$Iterations = 1,
    [int]$DurationMinutes = 0,
    [int]$PauseBetweenIterationsSeconds = 5,
    [int]$PollIntervalSeconds = 2,
    [int]$RecoveryDelaySeconds = 15,
    [int]$BackgroundPauseSeconds = 4,
    [int]$StepTimeoutMinutes = 20,
    [string]$ArtifactDirectory,
    [string]$SummaryPath,
    [switch]$SkipManualRecovery,
    [switch]$SkipLifecycleRecovery,
    [switch]$SkipDirectHttpRecovery,
    [switch]$ContinueOnFailure
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$manualRecoveryScript = Join-Path $scriptDir "validate-shell-recovery.ps1"
$lifecycleRecoveryScript = Join-Path $scriptDir "validate-shell-lifecycle-recovery.ps1"
$directHttpRecoveryScript = Join-Path $scriptDir "validate-direct-http-drafts.ps1"
$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"

if (-not (Test-Path $manualRecoveryScript)) {
    throw "validate-shell-recovery.ps1 was not found at $manualRecoveryScript"
}
if (-not (Test-Path $lifecycleRecoveryScript)) {
    throw "validate-shell-lifecycle-recovery.ps1 was not found at $lifecycleRecoveryScript"
}
if (-not (Test-Path $directHttpRecoveryScript)) {
    throw "validate-direct-http-drafts.ps1 was not found at $directHttpRecoveryScript"
}
if (-not (Test-Path $adbPath)) {
    throw "adb.exe was not found at $adbPath"
}
if ($SkipManualRecovery -and $SkipLifecycleRecovery -and $SkipDirectHttpRecovery) {
    throw "At least one validation path must remain enabled."
}
if ($Iterations -lt 0) {
    throw "Iterations must be zero or greater."
}
if ($DurationMinutes -lt 0) {
    throw "DurationMinutes must be zero or greater."
}
if ($StepTimeoutMinutes -lt 1) {
    throw "StepTimeoutMinutes must be at least 1."
}
if ($Iterations -eq 0 -and $DurationMinutes -eq 0) {
    throw "Specify Iterations greater than zero or set DurationMinutes for a duration-based soak run."
}

function Write-SoakStep {
    param([string]$Message)

    Write-Host "[validate-agent-runtime-soak] $Message"
}

function Stop-ValidationProcess {
    param([System.Diagnostics.Process]$Process)

    if (-not $Process) {
        return
    }
    if ($Process.HasExited) {
        return
    }

    $null = & taskkill.exe /PID $Process.Id /T /F 2>$null
    if ($LASTEXITCODE -ne 0) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Milliseconds 300
}

function Get-OutputPreview {
    param(
        [string]$Text,
        [int]$MaxLength = 240
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }

    $singleLine = ($Text -replace "\s+", " ").Trim()
    if ($singleLine.Length -le $MaxLength) {
        return $singleLine
    }
    return $singleLine.Substring(0, $MaxLength) + "..."
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

function Get-JsonCandidateFromOutput {
    param([string]$Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $null
    }

    $rawLines = $Raw -split "\r?\n"
    $jsonStart = $null
    for ($index = 0; $index -lt $rawLines.Count; $index++) {
        $trimmed = $rawLines[$index].TrimStart()
        if (
            $trimmed.StartsWith("{") -or
            $trimmed -match '^\[\s*$' -or
            $trimmed -match '^\[\s*[\{\"]'
        ) {
            $jsonStart = $index
            break
        }
    }

    if ($null -eq $jsonStart) {
        return $Raw.Trim()
    }

    return ($rawLines[$jsonStart..($rawLines.Count - 1)] -join [Environment]::NewLine).Trim()
}

function Get-ValidationOutputSummary {
    param([string]$OutputPath)

    if (-not (Test-Path $OutputPath)) {
        return $null
    }

    $raw = Get-Content -Raw $OutputPath
    $candidateJson = Get-JsonCandidateFromOutput -Raw $raw
    if ([string]::IsNullOrWhiteSpace($candidateJson)) {
        return $null
    }

    try {
        $payload = $candidateJson | ConvertFrom-Json

        if ($payload -is [System.Array]) {
            $modes = @(
                $payload |
                    ForEach-Object { $_.mode } |
                    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
            )
            return [pscustomobject]@{
                summary_kind = "direct_http_validation"
                validation_count = $modes.Count
                scenarios = $modes
                companion_restart_verified = @($payload | Where-Object { $_.companion_restart_verified }).Count
            }
        }

        if ($payload.PSObject.Properties.Name -contains "mode") {
            $mode = @($payload.mode | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            return [pscustomobject]@{
                summary_kind = "direct_http_validation"
                validation_count = $mode.Count
                scenarios = $mode
                companion_restart_verified = if ($payload.companion_restart_verified) { 1 } else { 0 }
            }
        }

        if ($payload.PSObject.Properties.Name -contains "validations") {
            $scenarios = @(
                $payload.validations |
                    ForEach-Object { $_.scenario } |
                    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
            )
            return [pscustomobject]@{
                summary_kind = "shell_validation"
                validation_count = $scenarios.Count
                scenarios = $scenarios
                bootstrap_endpoint = if ($payload.bootstrap) { $payload.bootstrap.endpoint } else { $null }
            }
        }

        if ($payload.PSObject.Properties.Name -contains "results") {
            $checkNames = @(
                $payload.results |
                    ForEach-Object { $_.checks } |
                    ForEach-Object { $_ } |
                    ForEach-Object { $_.name } |
                    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
            )
            return [pscustomobject]@{
                summary_kind = "soak_summary"
                validation_count = $checkNames.Count
                scenarios = $checkNames
                iterations_completed = $payload.iterations_completed
            }
        }

        return [pscustomobject]@{
            summary_kind = "unknown_json"
            validation_count = 0
            scenarios = @()
        }
    } catch {
        return [pscustomobject]@{
            parse_error = $_.Exception.Message
            output_preview = Get-OutputPreview -Text $raw
        }
    }
}

function Test-DurationExpired {
    if (-not $script:DeadlineAt) {
        return $false
    }
    return (Get-Date) -ge $script:DeadlineAt
}

function Invoke-SoakValidation {
    param(
        [int]$Iteration,
        [string]$Name,
        [string]$ScriptPath,
        [int]$Port,
        [hashtable]$ExtraParameters = @{}
    )

    $startedAt = Get-Date
    Write-SoakStep "Starting $Name on port $Port"
    $artifactBase = "{0:d3}-{1}-{2}" -f $Iteration, $Name, $Port
    $stdoutPath = Join-Path $script:ResolvedArtifactDirectory "$artifactBase.stdout.log"
    $stderrPath = Join-Path $script:ResolvedArtifactDirectory "$artifactBase.stderr.log"

    try {
        $arguments = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
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

        if (Test-Path $stdoutPath) {
            Remove-Item $stdoutPath -Force
        }
        if (Test-Path $stderrPath) {
            Remove-Item $stderrPath -Force
        }

        $powerShellPath = (Get-Process -Id $PID).Path
        $process = Start-Process $powerShellPath `
            -ArgumentList $arguments `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru
        $completed = $process.WaitForExit($StepTimeoutMinutes * 60 * 1000)
        if (-not $completed) {
            Stop-ValidationProcess -Process $process
            throw "$Name timed out after $StepTimeoutMinutes minute(s)."
        }
        $process.WaitForExit()

        $stdout = if (Test-Path $stdoutPath) { [string](Get-Content -Raw $stdoutPath) } else { "" }
        $stderr = if (Test-Path $stderrPath) { [string](Get-Content -Raw $stderrPath) } else { "" }
        $outputSummary = Get-ValidationOutputSummary -OutputPath $stdoutPath
        $hasValidPayload = (
            $null -ne $outputSummary -and
            $outputSummary.PSObject.Properties.Name -notcontains "parse_error" -and
            [int]$outputSummary.validation_count -gt 0
        )

        if ($process.ExitCode -ne 0 -and -not $hasValidPayload) {
            $message = (@($stderr, $stdout) | ForEach-Object {
                if ($null -eq $_) { "" } else { $_.ToString().Trim() }
            } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " "
            if ([string]::IsNullOrWhiteSpace($message)) {
                $message = "$Name exited with code $($process.ExitCode)."
            }
            throw $message
        }

        if ($process.ExitCode -ne 0 -and $hasValidPayload) {
            Write-SoakStep "$Name reported exit code $($process.ExitCode) but produced a valid success payload; treating it as passed"
        }

        $completedAt = Get-Date
        Write-SoakStep "Completed $Name in $([math]::Round(($completedAt - $startedAt).TotalSeconds, 1))s"
        return [pscustomobject]@{
            iteration = $Iteration
            name = $Name
            script = $ScriptPath
            port = $Port
            started_at = $startedAt.ToString("o")
            completed_at = $completedAt.ToString("o")
            duration_seconds = [math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
            succeeded = $true
            reported_exit_code = $process.ExitCode
            output_path = $stdoutPath
            error_path = if (Test-Path $stderrPath) { $stderrPath } else { $null }
            output_preview = Get-OutputPreview -Text $stdout
            result_summary = $outputSummary
            error = $null
        }
    } catch {
        $failedAt = Get-Date
        $message = $_.Exception.Message
        $stdout = if (Test-Path $stdoutPath) { [string](Get-Content -Raw $stdoutPath) } else { "" }
        $stderr = if (Test-Path $stderrPath) { [string](Get-Content -Raw $stderrPath) } else { "" }
        $outputSummary = Get-ValidationOutputSummary -OutputPath $stdoutPath
        Write-SoakStep "FAILED $Name in $([math]::Round(($failedAt - $startedAt).TotalSeconds, 1))s: $message"
        return [pscustomobject]@{
            iteration = $Iteration
            name = $Name
            script = $ScriptPath
            port = $Port
            started_at = $startedAt.ToString("o")
            completed_at = $failedAt.ToString("o")
            duration_seconds = [math]::Round(($failedAt - $startedAt).TotalSeconds, 3)
            succeeded = $false
            output_path = if (Test-Path $stdoutPath) { $stdoutPath } else { $null }
            error_path = if (Test-Path $stderrPath) { $stderrPath } else { $null }
            output_preview = Get-OutputPreview -Text $stdout
            error_preview = Get-OutputPreview -Text $stderr
            result_summary = $outputSummary
            error = $message
        }
    }
}

$script:ResolvedSerial = Resolve-TargetSerial -RequestedSerial $Serial
$script:ResolvedArtifactDirectory = if ($ArtifactDirectory) {
    $ArtifactDirectory
} else {
    Join-Path $env:TEMP "makoion-agent-runtime-soak-$sessionTag"
}
New-Item -ItemType Directory -Force -Path $script:ResolvedArtifactDirectory | Out-Null
$resolvedSummaryPath = if ($SummaryPath) {
    $SummaryPath
} else {
    Join-Path $script:ResolvedArtifactDirectory "summary.json"
}

$effectiveIterationLimit = $Iterations
if ($DurationMinutes -gt 0 -and ((-not $PSBoundParameters.ContainsKey("Iterations")) -or $Iterations -eq 0)) {
    $effectiveIterationLimit = [int]::MaxValue
}
$script:DeadlineAt = if ($DurationMinutes -gt 0) {
    (Get-Date).AddMinutes($DurationMinutes)
} else {
    $null
}

$startedAt = Get-Date
$iterationResults = [System.Collections.Generic.List[object]]::new()
$haltedEarly = $false
$haltReason = $null
$completionReason = $null
$portCursor = $BasePort

for ($iteration = 1; $iteration -le $effectiveIterationLimit; $iteration++) {
    if (Test-DurationExpired) {
        $completionReason = "duration_elapsed"
        break
    }

    $iterationLabel = if ($effectiveIterationLimit -eq [int]::MaxValue) {
        "Iteration $iteration"
    } else {
        "Iteration $iteration / $effectiveIterationLimit"
    }
    if ($script:DeadlineAt) {
        $remainingSeconds = [math]::Max([math]::Round(($script:DeadlineAt - (Get-Date)).TotalSeconds, 1), 0)
        Write-SoakStep "$iterationLabel (remaining budget ${remainingSeconds}s)"
    } else {
        Write-SoakStep $iterationLabel
    }

    $checks = [System.Collections.Generic.List[object]]::new()
    $stoppedForDuration = $false

    if (-not $SkipManualRecovery) {
        if (Test-DurationExpired) {
            $stoppedForDuration = $true
        } else {
            $manualResult = Invoke-SoakValidation `
                -Iteration $iteration `
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
    }

    if (-not $haltedEarly -and -not $stoppedForDuration -and -not $SkipLifecycleRecovery) {
        if (Test-DurationExpired) {
            $stoppedForDuration = $true
        } else {
            $lifecycleResult = Invoke-SoakValidation `
                -Iteration $iteration `
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
    }

    if (-not $haltedEarly -and -not $stoppedForDuration -and -not $SkipDirectHttpRecovery) {
        if (Test-DurationExpired) {
            $stoppedForDuration = $true
        } else {
            $directHttpResult = Invoke-SoakValidation `
                -Iteration $iteration `
                -Name "direct_http_recovery" `
                -ScriptPath $directHttpRecoveryScript `
                -Port $portCursor
            $checks.Add($directHttpResult) | Out-Null
            $portCursor += 1
            if (-not $directHttpResult.succeeded -and -not $ContinueOnFailure) {
                $haltedEarly = $true
                $haltReason = "direct_http_recovery failed during iteration $iteration"
            }
        }
    }

    if ($checks.Count -eq 0 -and $stoppedForDuration) {
        $completionReason = "duration_elapsed"
        break
    }

    $iterationResults.Add([pscustomobject]@{
        iteration = $iteration
        started_at = ($checks | Select-Object -First 1).started_at
        completed_at = ($checks | Select-Object -Last 1).completed_at
        check_count = $checks.Count
        successful_checks = @($checks | Where-Object { $_.succeeded }).Count
        failed_checks = @($checks | Where-Object { -not $_.succeeded }).Count
        all_succeeded = -not ($checks.succeeded -contains $false)
        stopped_for_duration = $stoppedForDuration
        checks = $checks
    }) | Out-Null

    if ($haltedEarly) {
        $completionReason = "failure"
        break
    }
    if ($stoppedForDuration -or (Test-DurationExpired)) {
        $completionReason = "duration_elapsed"
        break
    }

    if ($iteration -lt $effectiveIterationLimit -and $PauseBetweenIterationsSeconds -gt 0) {
        Write-SoakStep "Sleeping $PauseBetweenIterationsSeconds second(s) before the next iteration"
        Start-Sleep -Seconds $PauseBetweenIterationsSeconds
    }
}

$completedAt = Get-Date
$allChecks = @($iterationResults | ForEach-Object { $_.checks })
if (-not $completionReason) {
    if ($haltedEarly) {
        $completionReason = "failure"
    } elseif ($script:DeadlineAt -and (Test-DurationExpired)) {
        $completionReason = "duration_elapsed"
    } else {
        $completionReason = "iteration_limit_reached"
    }
}

$summary = [pscustomobject]@{
    serial = $script:ResolvedSerial
    endpoint_preset = $EndpointPreset
    base_port = $BasePort
    iterations_requested = $Iterations
    iteration_limit = if ($effectiveIterationLimit -eq [int]::MaxValue) { $null } else { $effectiveIterationLimit }
    duration_minutes = $DurationMinutes
    iterations_completed = $iterationResults.Count
    started_at = $startedAt.ToString("o")
    completed_at = $completedAt.ToString("o")
    duration_seconds = [math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
    deadline_at = if ($script:DeadlineAt) { $script:DeadlineAt.ToString("o") } else { $null }
    step_timeout_minutes = $StepTimeoutMinutes
    artifact_directory = $script:ResolvedArtifactDirectory
    summary_path = $resolvedSummaryPath
    total_checks = $allChecks.Count
    succeeded_checks = @($allChecks | Where-Object { $_.succeeded }).Count
    failed_checks = @($allChecks | Where-Object { -not $_.succeeded }).Count
    average_check_duration_seconds = if ($allChecks.Count -gt 0) {
        [math]::Round((($allChecks | Measure-Object -Property duration_seconds -Average).Average), 3)
    } else {
        0
    }
    all_succeeded = -not ($iterationResults.all_succeeded -contains $false)
    halted_early = $haltedEarly
    halt_reason = $haltReason
    completion_reason = $completionReason
    results = $iterationResults
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
if ($resolvedSummaryPath) {
    $summaryDir = Split-Path -Parent $resolvedSummaryPath
    if ($summaryDir) {
        New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
    }
    Set-Content -Path $resolvedSummaryPath -Value $summaryJson
    Write-SoakStep "Wrote soak summary to $resolvedSummaryPath"
}

if (-not $summary.all_succeeded -and -not $ContinueOnFailure) {
    if ($haltReason) {
        throw $haltReason
    }
    throw "Agent runtime soak failed."
}

if ($summary.all_succeeded) {
    Write-SoakStep "Agent runtime soak completed successfully"
} else {
    Write-SoakStep "Agent runtime soak completed with one or more failed checks"
}
Write-Output $summaryJson
