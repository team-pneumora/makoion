$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcRoot = Join-Path $scriptDir "src"
$buildDir = Join-Path $scriptDir "build\\classes"
$mainClass = "io.makoion.desktopcompanion.Main"

function Get-JavaCandidateRoots {
    $roots = [System.Collections.Generic.List[string]]::new()

    foreach ($envVar in @("JAVA_HOME", "JDK_HOME")) {
        $value = [Environment]::GetEnvironmentVariable($envVar)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $roots.Add($value)
        }
    }

    $androidStudioJbr = Join-Path ${env:ProgramFiles} "Android\Android Studio\jbr"
    if (Test-Path $androidStudioJbr) {
        $roots.Add($androidStudioJbr)
    }

    foreach ($javaHome in @(
        (Join-Path ${env:ProgramFiles} "Java"),
        (Join-Path ${env:ProgramFiles(x86)} "Java")
    )) {
        if (-not [string]::IsNullOrWhiteSpace($javaHome) -and (Test-Path $javaHome)) {
            Get-ChildItem -Path $javaHome -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $roots.Add($_.FullName) }
        }
    }

    return $roots | Select-Object -Unique
}

function Resolve-JavaToolPath {
    param([string]$ToolName)

    $toolCommand = Get-Command $ToolName -ErrorAction SilentlyContinue
    if ($toolCommand) {
        return $toolCommand.Source
    }

    foreach ($root in Get-JavaCandidateRoots) {
        foreach ($candidate in @(
            (Join-Path $root "bin\$ToolName.exe"),
            (Join-Path $root "$ToolName.exe")
        )) {
            if (Test-Path $candidate) {
                return $candidate
            }
        }
    }

    throw "$ToolName.exe could not be found. Set JAVA_HOME or install a local JDK/Android Studio JBR."
}

$javacPath = Resolve-JavaToolPath -ToolName "javac"
$javaPath = Resolve-JavaToolPath -ToolName "java"

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sourceFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sourceFiles) {
    throw "No Java source files were found under $srcRoot"
}

& $javacPath -d $buildDir $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed."
}

$javaArgs = @("-cp", $buildDir, $mainClass) + $args
& $javaPath @javaArgs
