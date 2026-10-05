param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path '.venv\Scripts\python.exe')) {
    & $Python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Creating the Python environment failed.' }
}
$BuildPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
& $BuildPython -m pip install -r requirements-build.txt
if ($LASTEXITCODE -ne 0) { throw 'Installing build dependencies failed.' }
& $BuildPython -m unittest test_core test_settings
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
& $BuildPython -m PyInstaller --noconfirm Daydream.spec
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
Write-Host "Executable: $PSScriptRoot\dist\Daydream.exe"
$ReleaseDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'releases'
New-Item -ItemType Directory -Force -Path $ReleaseDirectory | Out-Null
$ReleaseExecutable = Join-Path $ReleaseDirectory 'Daydream.exe'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dist\Daydream.exe') -Destination $ReleaseExecutable -Force
$Hash = (Get-FileHash -LiteralPath $ReleaseExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $ReleaseDirectory 'Daydream.exe.sha256') -Value "$Hash  Daydream.exe" -Encoding ascii
Write-Host "Release: $ReleaseExecutable"
