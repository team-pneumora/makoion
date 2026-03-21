param(
    [string]$Serial,
    [string]$ValidationMode = "normal",
    [int]$Port = 8787,
    [ValidateSet("adb_reverse", "emulator_host")]
    [string]$EndpointPreset = "adb_reverse"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidProjectDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $androidProjectDir))
$companionScript = Join-Path $repoRoot "projects\apps\desktop-companion\scripts\prepare-adb-reverse.ps1"
$adbPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$packageName = "io.makoion.hub.dev"
$debugAction = "$packageName.DEBUG_TRANSPORT"
$debugReceiver = "$packageName/io.makoion.mobileclaw.debug.DebugTransportReceiver"
$mainActivity = "$packageName/io.makoion.mobileclaw.MainActivity"

function Invoke-AdbProcess {
    param([string[]]$Arguments)

    $stdoutPath = Join-Path $env:TEMP ("makoion-bootstrap-adb-{0}.stdout" -f ([guid]::NewGuid()))
    $stderrPath = Join-Path $env:TEMP ("makoion-bootstrap-adb-{0}.stderr" -f ([guid]::NewGuid()))
    try {
        $process = Start-Process $adbPath -ArgumentList $Arguments -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -Wait -PassThru -NoNewWindow
        $output = @()
        if (Test-Path $stdoutPath) {
            $output += Get-Content -Raw $stdoutPath
        }
        if (Test-Path $stderrPath) {
            $output += Get-Content -Raw $stderrPath
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = ($output -join [Environment]::NewLine).Trim()
        }
    } finally {
        Remove-Item $stdoutPath,$stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Install-DebugApk {
    param([string]$TargetSerial, [string]$ResolvedApkPath)

    $installAttempt = Invoke-AdbProcess -Arguments @("-s", $TargetSerial, "install", "-r", $ResolvedApkPath)
    if ($installAttempt.ExitCode -eq 0) {
        return
    }

    $installText = $installAttempt.Output
    if ($installText -like "*INSTALL_FAILED_UPDATE_INCOMPATIBLE*") {
        & $adbPath -s $TargetSerial uninstall $packageName | Out-Null
        $reinstallAttempt = Invoke-AdbProcess -Arguments @("-s", $TargetSerial, "install", $ResolvedApkPath)
        if ($reinstallAttempt.ExitCode -eq 0) {
            return
        }
        $reinstallText = $reinstallAttempt.Output
        throw "Debug APK reinstall failed after uninstalling conflicting package. $reinstallText".Trim()
    }

    throw "Debug APK install failed. $installText".Trim()
}

function Resolve-DebugApkPath {
    $candidatePaths = [System.Collections.Generic.List[string]]::new()
    $settingsGradlePath = Join-Path $androidProjectDir "settings.gradle.kts"
    $projectName = "MobileClawAndroid"
    if (Test-Path $settingsGradlePath) {
        $settingsGradle = Get-Content -Raw $settingsGradlePath
        $match = [regex]::Match($settingsGradle, 'rootProject\.name\s*=\s*"([^"]+)"')
        if ($match.Success) {
            $projectName = $match.Groups[1].Value
        }
    }

    $customBuildRoot = $env:MAKOION_ANDROID_BUILD_ROOT
    if ([string]::IsNullOrWhiteSpace($customBuildRoot) -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $defaultBuildRoot = Join-Path $env:LOCALAPPDATA "Makoion\android-gradle-build"
        if (Test-Path $defaultBuildRoot) {
            $customBuildRoot = $defaultBuildRoot
        }
    }
    if ([string]::IsNullOrWhiteSpace($customBuildRoot) -and $androidProjectDir -like "*OneDrive*" -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $customBuildRoot = Join-Path $env:LOCALAPPDATA "Makoion\android-gradle-build"
    }
    if (-not [string]::IsNullOrWhiteSpace($customBuildRoot)) {
        $candidatePaths.Add(
            (Join-Path $customBuildRoot "$projectName\app\outputs\apk\debug\app-debug.apk")
        ) | Out-Null
    }
    $candidatePaths.Add(
        (Join-Path $androidProjectDir "app\build\outputs\apk\debug\app-debug.apk")
    ) | Out-Null

    $existingCandidates = @(
        $candidatePaths |
            Where-Object { Test-Path $_ } |
            Sort-Object { (Get-Item $_).LastWriteTimeUtc } -Descending
    )
    if ($existingCandidates.Count -gt 0) {
        return $existingCandidates[0]
    }
    return $candidatePaths[0]
}

$apkPath = Resolve-DebugApkPath

if (-not (Test-Path $adbPath)) {
    throw "adb.exe was not found at $adbPath"
}
if (-not (Test-Path $apkPath)) {
    throw "Debug APK was not found at $apkPath. Run :app:assembleDebug first."
}
if ($EndpointPreset -eq "adb_reverse" -and -not (Test-Path $companionScript)) {
    throw "prepare-adb-reverse.ps1 was not found at $companionScript"
}

if ($EndpointPreset -eq "adb_reverse") {
    $prepareArgs = @("-NoProfile", "-File", $companionScript, "-Port", $Port)
    if ($Serial) {
        $prepareArgs += @("-Serial", $Serial)
    }
    $prepareResult = & pwsh @prepareArgs | ConvertFrom-Json
    $targetSerial = if ($Serial) { $Serial } else { $prepareResult.serial }
    $endpoint = $prepareResult.endpoint
} else {
    if (-not $Serial) {
        $devices = & $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
        if ($devices.Count -eq 0) {
            throw "No connected emulator/device was found. Pass -Serial explicitly or start an emulator first."
        }
        if ($devices.Count -gt 1) {
            throw "Multiple devices are connected. Pass -Serial explicitly."
        }
        $Serial = ($devices[0] -split "\s+")[0]
    }
    $targetSerial = $Serial
    $endpoint = "http://10.0.2.2:$Port/api/v1/transfers"
}

Install-DebugApk -TargetSerial $targetSerial -ResolvedApkPath $apkPath
& $adbPath -s $targetSerial shell am broadcast `
    -a $debugAction `
    --include-stopped-packages `
    -n $debugReceiver `
    --es command bootstrap_direct_http_device `
    --es endpoint_preset $EndpointPreset `
    --es endpoint_url $endpoint `
    --es validation_mode $ValidationMode | Out-Null
& $adbPath -s $targetSerial shell am start `
    -n $mainActivity `
    --es open_section devices | Out-Null

[pscustomobject]@{
    serial = $targetSerial
    endpoint = $endpoint
    endpoint_preset = $EndpointPreset
    validation_mode = $ValidationMode
    package = $packageName
} | ConvertTo-Json -Compress
