$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcRoot = Join-Path $scriptDir "src"
$buildDir = Join-Path $scriptDir "build\\classes"
$mainClass = "io.makoion.desktopcompanion.Main"

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sourceFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sourceFiles) {
    throw "No Java source files were found under $srcRoot"
}

javac -d $buildDir $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "javac compilation failed."
}

$javaArgs = @("-cp", $buildDir, $mainClass) + $args
& java @javaArgs
