param(
  [Parameter(Mandatory=$true)][string]$JarPath,
  [string]$Needle = '0.5.0',
  [string]$OldNeedle = '0.4.0'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Adler32([byte[]]$b, [int]$start) {
  [uint32]$a = 1; [uint32]$s = 0
  for ($i = $start; $i -lt $b.Length; $i++) {
    $a = ($a + $b[$i]) % 65521
    $s = ($s + $a) % 65521
  }
  return [uint32](($s * 65536) + $a)
}
function Read-U32LE([byte[]]$b, [int]$o) {
  return [uint32]$b[$o] -bor ([uint32]$b[$o+1] -shl 8) -bor ([uint32]$b[$o+2] -shl 16) -bor ([uint32]$b[$o+3] -shl 24)
}

$jar = (Resolve-Path $JarPath).Path
$jarBytes = [System.IO.File]::ReadAllBytes($jar)
$md5 = [System.Security.Cryptography.MD5]::Create().ComputeHash($jarBytes)
$md5hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
Write-Output "JAR      : $jar"
Write-Output "SIZE     : $($jarBytes.Length) bytes"
Write-Output "MD5      : $md5hex"

$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
  $names = @($zip.Entries | ForEach-Object { $_.FullName })
  Write-Output "ENTRIES  : $($names.Count) -> $($names -join ', ')"
  if ($names.Count -ne 1 -or $names[0] -ne 'classes.dex') {
    Write-Output "ENTRYCHECK: FAIL (expected exactly one entry 'classes.dex')"
  } else {
    Write-Output "ENTRYCHECK: OK (single classes.dex, no extra files)"
  }
  $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' } | Select-Object -First 1
  if (-not $entry) { throw 'classes.dex not found in jar' }
  $ms = New-Object System.IO.MemoryStream
  $es = $entry.Open()
  $es.CopyTo($ms)
  $es.Close()
  $dex = $ms.ToArray()
  $ms.Close()
} finally { $zip.Dispose() }

Write-Output "DEX SIZE : $($dex.Length) bytes"
Write-Output "DEX MAGIC: $([System.Text.Encoding]::ASCII.GetString($dex,0,8).Replace("`0",'\0'))"

# --- dex header self-consistency ---
$storedAdler = Read-U32LE $dex 8
$calcAdler   = Get-Adler32 $dex 12
$storedSig = ($dex[12..31] | ForEach-Object { $_.ToString('x2') }) -join ''
$sha1 = [System.Security.Cryptography.SHA1]::Create()
$calcSig = ($sha1.ComputeHash($dex[32..($dex.Length-1)]) | ForEach-Object { $_.ToString('x2') }) -join ''
$fileSizeField = Read-U32LE $dex 32
$headerSize    = Read-U32LE $dex 36
$endianTag     = Read-U32LE $dex 40

Write-Output ("ADLER32  : stored=0x{0:x8} computed=0x{1:x8} -> {2}" -f $storedAdler, $calcAdler, $(if ($storedAdler -eq $calcAdler) { 'MATCH' } else { 'MISMATCH' }))
Write-Output "SHA1     : stored  =$storedSig"
Write-Output "SHA1     : computed=$calcSig -> $(if ($storedSig -eq $calcSig) { 'MATCH' } else { 'MISMATCH' })"
Write-Output "HDR      : file_size=$fileSizeField (actual $($dex.Length)) header_size=$headerSize endian=0x$('{0:x}' -f $endianTag)"

# --- structural parse: string table ---
$stringIdsSize = Read-U32LE $dex 56
$stringIdsOff  = Read-U32LE $dex 60
$typeIdsSize   = Read-U32LE $dex 64
$typeIdsOff    = Read-U32LE $dex 68
$protoIdsSize  = Read-U32LE $dex 72
$protoIdsOff   = Read-U32LE $dex 76
$methodIdsSize = Read-U32LE $dex 88
$methodIdsOff  = Read-U32LE $dex 92
$mapOff        = Read-U32LE $dex 52
Write-Output "STRUCT   : string_ids=$stringIdsSize@$stringIdsOff type_ids=$typeIdsSize@$typeIdsOff proto_ids=$protoIdsSize@$protoIdsOff method_ids=$methodIdsSize@$methodIdsOff map_off=$mapOff"
$ok = $true
if ($stringIdsOff + $stringIdsSize*4 -gt $dex.Length) { $ok = $false; Write-Output 'STRUCT   : string_ids OUT OF RANGE' }
if ($typeIdsOff + $typeIdsSize*4 -gt $dex.Length)     { $ok = $false; Write-Output 'STRUCT   : type_ids OUT OF RANGE' }
if ($methodIdsOff + $methodIdsSize*8 -gt $dex.Length) { $ok = $false; Write-Output 'STRUCT   : method_ids OUT OF RANGE' }
if ($mapOff -lt 0x70 -or $mapOff -ge $dex.Length)     { $ok = $false; Write-Output 'STRUCT   : map_off INVALID' }
Write-Output "STRUCT   : ranges $(if ($ok) { 'OK' } else { 'FAIL' })"

$newHits = New-Object System.Collections.ArrayList
$oldHits = New-Object System.Collections.ArrayList
$semvers = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $stringIdsSize; $i++) {
  $dataOff = Read-U32LE $dex ($stringIdsOff + $i*4)
  $p = [int]$dataOff
  $len = 0; $shift = 0
  do { $b = $dex[$p]; $len = $len -bor (($b -band 0x7f) -shl $shift); $shift += 7; $p++ } while ($b -band 0x80)
  $end = $p
  while ($dex[$end] -ne 0) { $end++ }
  $s = [System.Text.Encoding]::UTF8.GetString($dex, $p, $end - $p)
  if ($s.Contains($Needle))    { [void]$newHits.Add($s) }
  if ($s.Contains($OldNeedle)) { [void]$oldHits.Add($s) }
  if ($s -match '^\d+\.\d+\.\d+$') { [void]$semvers.Add($s) }
}
Write-Output "STRINGS  : containing '$Needle' = $($newHits.Count) $(if ($newHits.Count) { '-> ' + ($newHits -join ' | ') })"
Write-Output "STRINGS  : containing '$OldNeedle' = $($oldHits.Count) $(if ($oldHits.Count) { '-> ' + ($oldHits -join ' | ') })"
Write-Output "STRINGS  : semver-like = $(if ($semvers.Count) { $semvers -join ', ' } else { '(none)' })"

$verdict = ($names.Count -eq 1 -and $names[0] -eq 'classes.dex' -and $storedAdler -eq $calcAdler -and $storedSig -eq $calcSig -and $fileSizeField -eq $dex.Length -and $ok -and $newHits.Count -ge 1 -and $oldHits.Count -eq 0)
Write-Output "VERDICT  : $(if ($verdict) { 'PASS' } else { 'FAIL' })"
