param(
    [int]$Port = 8792,
    [string]$InboxDir = "inbox-validate-session-notify",
    [switch]$AllowRecordedOnly
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$sourceFile = Join-Path $projectDir "src\io\makoion\desktopcompanion\Main.java"
$outDir = Join-Path $projectDir "out"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$stdoutLog = Join-Path $projectDir "companion-session-notify-$sessionTag.log"
$stderrLog = Join-Path $projectDir "companion-session-notify-$sessionTag.err"
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

    $requestId = "notify-test-$sessionTag"
    $body = @{
        request_id = $requestId
        source = "codex-validation"
        device_name = "Codex"
        title = "Makoion session ping"
        body = "session.notify validation probe"
    } | ConvertTo-Json -Compress

    $response = Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/api/v1/session/notify" `
        -Method Post `
        -Body $body `
        -ContentType "application/json" `
        -TimeoutSec 5

    $responseJson = $response.Content | ConvertFrom-Json
    $materializedDir = Join-Path $inboxPath "actions\$($responseJson.materialized_dir)"
    if (-not (Test-Path $materializedDir)) {
        throw "session.notify did not materialize into $materializedDir"
    }
    if (-not (Test-Path (Join-Path $materializedDir "request.json"))) {
        throw "request.json was not created for session.notify"
    }
    if (-not (Test-Path (Join-Path $materializedDir "summary.txt"))) {
        throw "summary.txt was not created for session.notify"
    }
    $summaryText = Get-Content -Raw (Join-Path $materializedDir "summary.txt")
    if (-not $AllowRecordedOnly -and -not [bool]$responseJson.notification_displayed) {
        throw "session.notify did not report notification_displayed=true"
    }
    if (-not $AllowRecordedOnly -and $summaryText -notlike "*Displayed: True*" -and $summaryText -notlike "*Displayed: true*") {
        throw "session.notify summary did not confirm Displayed: true"
    }

    [pscustomobject]@{
        status_code = [int]$response.StatusCode
        request_id = $responseJson.request_id
        capability = $responseJson.capability
        notification_displayed = [bool]$responseJson.notification_displayed
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
