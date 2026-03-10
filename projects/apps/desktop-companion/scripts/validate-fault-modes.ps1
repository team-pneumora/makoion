param(
    [int]$Port = 8791,
    [string]$InboxDir = "inbox-validate-faults"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$sourceFile = Join-Path $projectDir "src\io\makoion\desktopcompanion\Main.java"
$outDir = Join-Path $projectDir "out"
$sessionTag = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$stdoutLog = Join-Path $projectDir "companion-validate-$sessionTag.log"
$stderrLog = Join-Path $projectDir "companion-validate-$sessionTag.err"
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

function Invoke-Transfer {
    param(
        [int]$Port,
        [string]$TransferId,
        [string]$Mode,
        [int]$TimeoutSec
    )

    $body = "{`"transfer_id`":`"$TransferId`",`"device_name`":`"Codex`",`"file_names`":[`"alpha.txt`"]}"
    $headers = @{
        "X-MobileClaw-Transfer-Id" = $TransferId
        "X-MobileClaw-Device-Name" = "Codex"
        "X-MobileClaw-Delivery-Mode" = "manifest_only"
        "X-MobileClaw-Debug-Receipt-Mode" = $Mode
        "X-MobileClaw-Response-Timeout-Ms" = "1000"
    }

    Invoke-WebRequest `
        -Uri "http://127.0.0.1:$Port/api/v1/transfers" `
        -Method Post `
        -Headers $headers `
        -Body $body `
        -ContentType "application/json" `
        -TimeoutSec $TimeoutSec
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

    $timeoutFirst = $null
    try {
        $response = Invoke-Transfer -Port $Port -TransferId "transfer-timeout-001" -Mode "timeout_once" -TimeoutSec 2
        $timeoutFirst = "unexpected-success:$($response.StatusCode)"
    } catch {
        $timeoutFirst = $_.Exception.Message
    }
    $timeoutSecond = Invoke-Transfer -Port $Port -TransferId "transfer-timeout-001" -Mode "timeout_once" -TimeoutSec 10

    $disconnectFirst = $null
    try {
        $response = Invoke-Transfer -Port $Port -TransferId "transfer-disconnect-001" -Mode "disconnect_once" -TimeoutSec 4
        $disconnectFirst = "unexpected-success:$($response.StatusCode)"
    } catch {
        $disconnectFirst = $_.Exception.Message
    }
    $disconnectSecond = Invoke-Transfer -Port $Port -TransferId "transfer-disconnect-001" -Mode "disconnect_once" -TimeoutSec 10

    [pscustomobject]@{
        timeout_first = $timeoutFirst
        timeout_second = [int]$timeoutSecond.StatusCode
        disconnect_first = $disconnectFirst
        disconnect_second = [int]$disconnectSecond.StatusCode
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
