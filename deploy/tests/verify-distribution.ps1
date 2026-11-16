param([string]$Archive, [int]$Port = 18080)
$ErrorActionPreference = 'Stop'
if (!$Archive) { $Archive = Join-Path $PSScriptRoot '../../target/certificate-manage-0.1.0-dist.zip' }
$Archive = [IO.Path]::GetFullPath($Archive)
if (!(Test-Path -LiteralPath $Archive)) { throw "Distribution archive missing: $Archive" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
  $names = @($zip.Entries | ForEach-Object FullName)
  foreach ($path in @('lib/certificate-manage.jar','config/application.yml','config/jvm.options','bin/service.bat','bin/service.sh','bin/start.bat','bin/stop.bat','bin/restart.bat','bin/status.bat','bin/start.sh','bin/stop.sh','bin/restart.sh','bin/status.sh','README.md')) {
    if ($names -notcontains "certificateManage/$path") { throw "Missing archive entry: $path" }
  }
  if ($names | Where-Object { $_ -match '\.(mv\.db|key|log)$|(^|/)(node_modules|src)/' }) { throw 'Runtime data or sources found in release' }
} finally { $zip.Dispose() }
$destination = Join-Path (Split-Path $Archive) ('distribution smoke ' + [guid]::NewGuid().ToString('N'))
[IO.Compression.ZipFile]::ExtractToDirectory($Archive, $destination)
$root = Join-Path $destination 'certificateManage'
$oldPort = $env:PORT
$oldHealth = $env:HEALTH_URL
$oldDb = $env:DB_URL
$oldKey = $env:ENCRYPTION_KEY_FILE
try {
  $env:PORT = $null
  $env:HEALTH_URL = "http://127.0.0.1:$Port"
  $configFile = Join-Path $root 'config/application.yml'
  $config = (Get-Content -LiteralPath $configFile -Raw).Replace('port: ${PORT:8080}', "port: $Port")
  [IO.File]::WriteAllText($configFile, $config, (New-Object Text.UTF8Encoding($false)))
  [IO.File]::AppendAllText((Join-Path $root 'config/jvm.options'), "`n-Ddistribution.smoke=中文参数`n", (New-Object Text.UTF8Encoding($false)))
  $env:DB_URL = 'jdbc:h2:file:./data/smoke'
  $env:ENCRYPTION_KEY_FILE = './data/master.key'
  [IO.File]::WriteAllText((Join-Path $root 'run/certificate-manage.pid'), '')
  & (Join-Path $root 'bin/status.bat')
  if ($LASTEXITCODE -ne 3) { throw 'Empty PID file must be treated as stopped' }
  & (Join-Path $root 'bin/start.bat')
  if ($LASTEXITCODE -ne 0) { throw 'start failed' }
  $first = Get-Content (Join-Path $root 'run/certificate-manage.pid')
  $started = Get-CimInstance Win32_Process -Filter "ProcessId = $first"
  if (!$started.CommandLine.Contains('-Ddistribution.smoke=中文参数')) { throw 'UTF-8 JVM options were not preserved' }
  & (Join-Path $root 'bin/start.bat')
  if ($LASTEXITCODE -ne 0 -or (Get-Content (Join-Path $root 'run/certificate-manage.pid')) -ne $first) { throw 'Duplicate start changed PID' }
  & (Join-Path $root 'bin/status.bat')
  if ($LASTEXITCODE -ne 0) { throw 'status failed' }
  & (Join-Path $root 'bin/restart.bat')
  if ($LASTEXITCODE -ne 0 -or (Get-Content (Join-Path $root 'run/certificate-manage.pid')) -eq $first) { throw 'restart failed' }
  foreach ($path in @('data/smoke.mv.db','data/master.key','logs/certificate-manage.log')) {
    if (!(Test-Path -LiteralPath (Join-Path $root $path))) { throw "Expected runtime file missing: $path" }
  }
  & (Join-Path $root 'bin/stop.bat')
  if ($LASTEXITCODE -ne 0) { throw 'stop failed' }
  & (Join-Path $root 'bin/status.bat')
  if ($LASTEXITCODE -ne 3) { throw 'Stopped status must exit 3' }
  Write-Output "Distribution lifecycle verified in: $root"
} finally {
  if (Test-Path -LiteralPath (Join-Path $root 'run/certificate-manage.pid')) { & (Join-Path $root 'bin/stop.bat') }
  $env:PORT = $oldPort
  $env:HEALTH_URL = $oldHealth
  $env:DB_URL = $oldDb
  $env:ENCRYPTION_KEY_FILE = $oldKey
}
exit 0
