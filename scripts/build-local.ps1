param()
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'build.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Helper build failed' }
& (Join-Path $PSScriptRoot 'verify-helper-jar.ps1') -JarPath (Join-Path $PSScriptRoot '..\build\dsh-vdisplay.jar')
