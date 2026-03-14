param(
    [int]$Port = 8794,
    [string]$InboxDir = "inbox-validate-workflow-run",
    [ValidateSet("open_latest_transfer", "open_actions_folder", "open_latest_action")]
    [string]$WorkflowId = "open_latest_transfer",
    [ValidateSet("record_only", "best_effort")]
    [string]$RunMode = "record_only"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$sourceFile = Join-Path $projectDir "src\io\makoion\desktopcompanion\Main.java"
$outDir = Join-Path $projectDir "out"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$stdoutLog = Join-Path $projectDir "companion-workflow-run-$sessionTag.log"
$stderrLog = Join-Path $projectDir "companion-workflow-run-$sessionTag.err"
$inboxPath = Join-Path $projectDir "$InboxDir-$sessionTag"
$process = $null

function Wait-ForHealth {
    param([int]$Port)

    for ($index = 0; $index -lt 20; $index++) {
        Start-Sleep -Milliseconds 300
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
        }
    }

    throw "Companion health check did not succeed."
}

function Initialize-WorkflowTarget {
    param([string]$SelectedWorkflowId)

    switch ($SelectedWorkflowId) {
        "open_latest_transfer" {
            $seedTransferDir = Join-Path $inboxPath "99991231-235959-transfer-seed"
            New-Item -ItemType Directory -Force -Path $seedTransferDir | Out-Null
            Set-Content -Path (Join-Path $seedTransferDir "summary.txt") -Value "Seed transfer for workflow.run validation."
        }
        "open_actions_folder" {
            New-Item -ItemType Directory -Force -Path (Join-Path $inboxPath "actions") | Out-Null
        }
        "open_latest_action" {
            $seedActionDir = Join-Path $inboxPath "actions\99991231-235959-notify-seed"
            New-Item -ItemType Directory -Force -Path $seedActionDir | Out-Null
            Set-Content -Path (Join-Path $seedActionDir "request.json") -Value '{"request_id":"seed-workflow-run"}'
            Set-Content -Path (Join-Path $seedActionDir "summary.txt") -Value "Seed action for workflow.run validation."
        }
    }
}

try {
    & javac -d $outDir $sourceFile

    $process = Start-Process `
        -FilePath "java" `
        -ArgumentList @(
            "-cp",
            $outDir,
            "io.makoion.desktopcompanion.Main",
            "--host",
            "127.0.0.1",
            "--port",
            $Port,
            "--inbox-dir",
            $inboxPath
        ) `
        -PassThru `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog

    Wait-ForHealth -Port $Port
    Initialize-WorkflowTarget -SelectedWorkflowId $WorkflowId

    $requestId = "workflow-run-test-$sessionTag"
    $workflowLabel = switch ($WorkflowId) {
        "open_actions_folder" { "Open actions folder" }
        "open_latest_action" { "Open latest action" }
        default { "Open latest transfer" }
    }
    $body = @{
        request_id = $requestId
        source = "codex-validation"
        device_name = "Codex"
        workflow_id = $WorkflowId
        workflow_label = $workflowLabel
        run_mode = $RunMode
    } | ConvertTo-Json -Compress

    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/api/v1/workflow/run" `
        -Method Post `
        -Body $body `
        -ContentType "application/json" `
        -TimeoutSec 5

    $responseJson = $response.Content | ConvertFrom-Json
    $materializedDir = Join-Path $inboxPath "actions\$($responseJson.materialized_dir)"
    if (-not (Test-Path $materializedDir)) {
        throw "workflow.run did not materialize into $materializedDir"
    }
    if (-not (Test-Path (Join-Path $materializedDir "request.json"))) {
        throw "request.json was not created for workflow.run"
    }
    if (-not (Test-Path (Join-Path $materializedDir "summary.txt"))) {
        throw "summary.txt was not created for workflow.run"
    }
    $summaryText = Get-Content -Raw (Join-Path $materializedDir "summary.txt")
    if ($RunMode -eq "record_only" -and [bool]$responseJson.executed) {
        throw "record_only workflow.run unexpectedly reported executed=true"
    }
    if ($RunMode -eq "best_effort" -and -not [bool]$responseJson.executed) {
        throw "best_effort workflow.run did not report executed=true"
    }
    if ($RunMode -eq "record_only" -and $summaryText -notlike "*Executed: False*" -and $summaryText -notlike "*Executed: false*") {
        throw "workflow.run summary did not confirm Executed: false for record_only"
    }
    if ($RunMode -eq "best_effort" -and $summaryText -notlike "*Executed: True*" -and $summaryText -notlike "*Executed: true*") {
        throw "workflow.run summary did not confirm Executed: true for best_effort"
    }

    [pscustomobject]@{
        status_code = [int]$response.StatusCode
        request_id = $responseJson.request_id
        capability = $responseJson.capability
        workflow_id = $responseJson.workflow_id
        run_mode = $RunMode
        executed = [bool]$responseJson.executed
        materialized_dir = $responseJson.materialized_dir
        status_detail = $responseJson.status_detail
    } | ConvertTo-Json -Compress
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Start-Sleep -Milliseconds 300
    }
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
