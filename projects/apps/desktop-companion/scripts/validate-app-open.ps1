param(
    [int]$Port = 8793,
    [string]$InboxDir = "inbox-validate-app-open",
    [ValidateSet("inbox", "latest_transfer", "actions_folder")]
    [string]$TargetKind = "inbox",
    [ValidateSet("record_only", "best_effort")]
    [string]$OpenMode = "record_only"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$sourceFile = Join-Path $projectDir "src\io\makoion\desktopcompanion\Main.java"
$outDir = Join-Path $projectDir "out"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$stdoutLog = Join-Path $projectDir "companion-app-open-$sessionTag.log"
$stderrLog = Join-Path $projectDir "companion-app-open-$sessionTag.err"
$inboxPath = Join-Path $projectDir "$InboxDir-$sessionTag"
$process = $null

function Get-TargetLabel {
    param([string]$Kind)

    switch ($Kind) {
        "latest_transfer" { return "Latest transfer folder" }
        "actions_folder" { return "Actions folder" }
        default { return "Desktop companion inbox" }
    }
}

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

    if ($TargetKind -eq "latest_transfer") {
        $seedTransferDir = Join-Path $inboxPath "99991231-235959-transfer-seed"
        New-Item -ItemType Directory -Force -Path $seedTransferDir | Out-Null
        Set-Content -Path (Join-Path $seedTransferDir "summary.txt") -Value "Seed transfer for app.open validation."
    }

    $requestId = "app-open-test-$sessionTag"
    $targetLabel = Get-TargetLabel -Kind $TargetKind
    $body = @{
        request_id = $requestId
        source = "codex-validation"
        device_name = "Codex"
        target_kind = $TargetKind
        target_label = $targetLabel
        open_mode = $OpenMode
    } | ConvertTo-Json -Compress

    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/api/v1/app/open" `
        -Method Post `
        -Body $body `
        -ContentType "application/json" `
        -TimeoutSec 5

    $responseJson = $response.Content | ConvertFrom-Json
    $materializedDir = Join-Path $inboxPath "actions\$($responseJson.materialized_dir)"
    if (-not (Test-Path $materializedDir)) {
        throw "app.open did not materialize into $materializedDir"
    }
    if (-not (Test-Path (Join-Path $materializedDir "request.json"))) {
        throw "request.json was not created for app.open"
    }
    if (-not (Test-Path (Join-Path $materializedDir "summary.txt"))) {
        throw "summary.txt was not created for app.open"
    }
    if ($OpenMode -eq "record_only" -and [bool]$responseJson.opened) {
        throw "record_only app.open unexpectedly reported opened=true"
    }
    if ($OpenMode -eq "best_effort" -and -not [bool]$responseJson.opened) {
        throw "best_effort app.open did not report opened=true"
    }

    [pscustomobject]@{
        status_code = [int]$response.StatusCode
        request_id = $responseJson.request_id
        capability = $responseJson.capability
        target_kind = $responseJson.target_kind
        opened = [bool]$responseJson.opened
        opened_path = $responseJson.opened_path
        materialized_dir = $responseJson.materialized_dir
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
