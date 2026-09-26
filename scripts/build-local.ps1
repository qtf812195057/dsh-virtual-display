param(
  [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path,
  [string]$Jdk  = ''
)
# Replicates build.sh (javac -> jar -> D8 min-api 30 -> zip) on Windows.
# build.sh itself needs bash+python3+zip; this uses only the JDK's javac/jar/java.
$ErrorActionPreference = 'Continue'

if (-not $Jdk) {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin') }
  $candidates += 'D:\work\pocket-tavern\tools\jdk-17.0.20.1+1\bin'
  foreach ($c in $candidates) { if (Test-Path (Join-Path $c 'javac.exe')) { $Jdk = $c; break } }
}
if (-not $Jdk) { throw 'No JDK found. Pass -Jdk <path-to-jdk\bin> (JDK 17 or newer).' }

$java  = Join-Path $Jdk 'java.exe'
$javac = Join-Path $Jdk 'javac.exe'
$jar   = Join-Path $Jdk 'jar.exe'
foreach ($t in @($java, $javac, $jar)) { if (-not (Test-Path $t)) { throw "missing tool: $t" } }
Write-Output "JDK      : $Jdk"

Push-Location $Root
try {
  if (-not (Test-Path 'build\classes')) { New-Item -ItemType Directory -Path 'build\classes' -Force | Out-Null }
  if (-not (Test-Path 'build\dex')) { New-Item -ItemType Directory -Path 'build\dex' -Force | Out-Null }
  Write-Output '=== [1/4] javac -encoding utf-8 -source 11 -target 11 -cp build/android.jar -d build/classes src/*.java ==='
  $sources = @(Get-ChildItem -Path 'src\*.java' | ForEach-Object { $_.FullName })
  & $javac -encoding utf-8 -source 11 -target 11 -cp 'build/android.jar' -d 'build/classes' $sources 2>&1 | ForEach-Object { Write-Output "  $_" }
  if ($LASTEXITCODE -ne 0) { throw "javac failed with exit $LASTEXITCODE" }

  Write-Output '=== [2/4] jar cf build/helper-classes.jar -C build/classes . ==='
  & $jar cf 'build/helper-classes.jar' -C 'build/classes' . 2>&1 | ForEach-Object { Write-Output "  $_" }
  if ($LASTEXITCODE -ne 0) { throw "jar (classes) failed with exit $LASTEXITCODE" }

  Write-Output '=== [3/4] java -cp vendor/r8.jar com.android.tools.r8.D8 --lib build/android.jar --min-api 30 --output build/dex build/helper-classes.jar ==='
  & $java -cp 'vendor/r8.jar' com.android.tools.r8.D8 --lib 'build/android.jar' --min-api 30 --output 'build/dex' 'build/helper-classes.jar' 2>&1 | ForEach-Object { Write-Output "  $_" }
  if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit $LASTEXITCODE" }

  Write-Output '=== [4/4] jar cfM build/dsh-vdisplay.jar -C build/dex classes.dex ==='
  if (Test-Path 'build\dsh-vdisplay.jar') { Remove-Item 'build\dsh-vdisplay.jar' -Force }
  & $jar cfM 'build/dsh-vdisplay.jar' -C 'build/dex' 'classes.dex' 2>&1 | ForEach-Object { Write-Output "  $_" }
  if ($LASTEXITCODE -ne 0) { throw "jar (package) failed with exit $LASTEXITCODE" }

  Write-Output '=== BUILD OK ==='
} finally { Pop-Location }

& (Join-Path $PSScriptRoot 'verify-helper-jar.ps1') -JarPath (Join-Path $Root 'build\dsh-vdisplay.jar')
