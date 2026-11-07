# Build, copy JAR, and commit to git
# Use this instead of gradlew.bat build to auto-copy the JAR and create a git commit

$ErrorActionPreference = "Stop"
$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path $ScriptDir -Parent
$ReleasesDir = Join-Path $ProjectRoot "releases"

# Read version from build.gradle
$BuildGradle = Get-Content (Join-Path $ScriptDir "build.gradle") -Raw
if ($BuildGradle -match "version\s*=\s*'([^']+)'") {
    $Version = $matches[1]
} else {
    Write-Host "Could not read version from build.gradle!" -ForegroundColor Red
    exit 1
}
Write-Host "Building version: $Version" -ForegroundColor Yellow

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

# Git commit
Write-Host "`n=== Git Commit ===" -ForegroundColor Cyan
$CommitMessage = Read-Host "Enter commit message for v$Version"

if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
    $CommitMessage = "PackUpdater v$Version"
}

# Stage all changes
git add -A 2>&1 | Out-Null

# Check if there are changes to commit
$GitStatus = git status --porcelain 2>&1
if ($GitStatus) {
    # Commit with detailed message
    $FullMessage = "PackUpdater v$Version - $CommitMessage`n`nVersion: $Version`nDate: $(Get-Date -Format 'yyyy-MM-dd')`nJAR: $($JarFile.Name)"
    git commit -m $FullMessage --trailer "Co-authored-by: Junie <junie@jetbrains.com>" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Committed changes for v$Version" -ForegroundColor Green
    } else {
        Write-Host "Git commit failed or nothing to commit" -ForegroundColor Yellow
    }
} else {
    Write-Host "No changes to commit" -ForegroundColor Yellow
}

Write-Host "`nBuild complete! JAR saved to:" -ForegroundColor Cyan
Write-Host "  Project root: $(Join-Path $ProjectRoot $JarFile.Name)"
Write-Host "  Releases:     $(Join-Path $ReleasesDir $JarFile.Name)"
