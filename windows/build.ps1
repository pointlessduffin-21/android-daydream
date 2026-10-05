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
