param(
    [string]$Serial,
    [int]$Port = 8787
)

$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        return $sdkAdb
    }

    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "adb.exe was not found in LOCALAPPDATA Android SDK or PATH."
}

function Get-ConnectedDevices {
    param([string]$AdbPath)

    $lines = & $AdbPath devices -l | Select-Object -Skip 1
    return $lines |
        Where-Object { $_ -and $_.Trim() -and ($_ -notmatch "offline") } |
        ForEach-Object {
            $parts = $_ -split "\s+"
            [pscustomobject]@{
                Serial = $parts[0]
                State = $parts[1]
                Raw = $_.Trim()
            }
        } |
        Where-Object { $_.State -eq "device" }
}

$adbPath = Resolve-AdbPath
$devices = Get-ConnectedDevices -AdbPath $adbPath

if (-not $Serial) {
    if ($devices.Count -eq 0) {
        throw "No connected Android devices were found."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Pass -Serial explicitly."
    }
    $Serial = $devices[0].Serial
}

& $adbPath -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
$reverseList = & $adbPath -s $Serial reverse --list

[pscustomobject]@{
    serial = $Serial
    port = $Port
    endpoint = "http://127.0.0.1:$Port/api/v1/transfers"
    reverse = ($reverseList | Where-Object { $_ -match "tcp:$Port" })
} | ConvertTo-Json -Compress
