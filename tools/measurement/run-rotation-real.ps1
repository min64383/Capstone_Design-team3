param([string]$Out = ("core/build/rotation-real-" + (Get-Date -Format yyyyMMdd-HHmmss)))
$ErrorActionPreference = "Stop"
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $repo
try {
    & .\gradlew.bat :core:rotationRealReplay "-Pout=$Out"
    if ($LASTEXITCODE -ne 0) { throw "Rotation replay failed" }
    $outputDir = (Resolve-Path $Out).Path
    Compress-Archive -Path (Join-Path $outputDir "*") -DestinationPath "$outputDir.zip"
    explorer.exe /select,"$outputDir.zip"
} finally {
    Pop-Location
}
