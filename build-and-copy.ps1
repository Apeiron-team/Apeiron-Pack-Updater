# Build and copy JAR to project root and releases/ folder
# Use this instead of gradlew.bat build to auto-copy the JAR for documentation

$ErrorActionPreference = "Stop"
$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path $ScriptDir -Parent
$ReleasesDir = Join-Path $ProjectRoot "releases"

# Run Gradle build
Write-Host "=== Building PackUpdater ===" -ForegroundColor Cyan
& "$ScriptDir\gradlew.bat" build --console=plain

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

# Find the built JAR
$JarFile = Get-ChildItem "$ScriptDir\build\libs\*.jar" | Where-Object { -not $_.Name.Contains("sources") -and -not $_.Name.Contains("dev") } | Select-Object -First 1

if (-not $JarFile) {
    Write-Host "No JAR file found in build/libs/" -ForegroundColor Red
    exit 1
}

# Copy to project root
Copy-Item $JarFile.FullName (Join-Path $ProjectRoot $JarFile.Name) -Force
Write-Host "Copied $($JarFile.Name) to project root" -ForegroundColor Green

# Copy to releases folder
if (-not (Test-Path $ReleasesDir)) {
    New-Item -ItemType Directory -Path $ReleasesDir -Force | Out-Null
}
Copy-Item $JarFile.FullName (Join-Path $ReleasesDir $JarFile.Name) -Force
Write-Host "Copied $($JarFile.Name) to releases/" -ForegroundColor Green

Write-Host "`nBuild complete! JAR saved to:" -ForegroundColor Cyan
Write-Host "  Project root: $(Join-Path $ProjectRoot $JarFile.Name)"
Write-Host "  Releases:     $(Join-Path $ReleasesDir $JarFile.Name)"
