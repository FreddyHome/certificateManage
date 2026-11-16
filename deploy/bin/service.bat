@echo off
setlocal
set "CM_SCRIPT=%~f0"
set "CM_ACTION=%~1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& { $lines = Get-Content -LiteralPath $env:CM_SCRIPT -Encoding UTF8; $index = [Array]::IndexOf($lines, '# POWERSHELL_BEGIN'); if ($index -lt 0) { exit 1 }; $body = $lines[($index + 1)..($lines.Length - 1)] -join [Environment]::NewLine; & ([scriptblock]::Create($body)) -Action $env:CM_ACTION }"
exit /b %ERRORLEVEL%
# POWERSHELL_BEGIN
param([ValidateSet('start','stop','restart','status')][string]$Action)
$ErrorActionPreference = 'Stop'
$appRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $env:CM_SCRIPT) '..'))
$jarPath = Join-Path $appRoot 'lib\certificate-manage.jar'
$pidFile = Join-Path $appRoot 'run\certificate-manage.pid'
$port = if ($env:PORT) { $env:PORT } else { '8080' }
$healthBase = if ($env:HEALTH_URL) { $env:HEALTH_URL.TrimEnd('/') } else { "http://127.0.0.1:$port" }
$timeout = if ($env:START_TIMEOUT) { [int]$env:START_TIMEOUT } else { 60 }

# Check command identity as well as the stored PID; never stop an unrelated process.
function Test-ServiceIdentity($candidate) {
  $pattern = '(?:^|\s)"?-jar"?\s+(?:"' + [regex]::Escape($jarPath) + '"|' + [regex]::Escape($jarPath) + '(?=\s|$))'
  return $candidate -and $candidate.Name -match '^java(w)?\.exe$' -and $candidate.CommandLine -and [regex]::IsMatch($candidate.CommandLine, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)
}
function Find-Service {
  if (Test-Path -LiteralPath $pidFile) {
    $saved = [IO.File]::ReadAllText($pidFile).Trim()
    if ($saved -match '^\d+$') {
      $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $saved"
      if (Test-ServiceIdentity $candidate) { return $candidate }
    }
  }
  $matchingProcesses = @(Get-CimInstance Win32_Process -Filter "name = 'java.exe' or name = 'javaw.exe'" | Where-Object { Test-ServiceIdentity $_ })
  if ($matchingProcesses.Count -gt 1) { throw 'Multiple matching application processes; inspect manually.' }
  if ($matchingProcesses.Count -eq 1) { return $matchingProcesses[0] }
  return $null
}

function Test-Health {
  try {
    $web = Invoke-WebRequest "$healthBase/" -UseBasicParsing -TimeoutSec 2
    $csrf = Invoke-WebRequest "$healthBase/api/v1/auth/csrf" -UseBasicParsing -TimeoutSec 2
    return $web.StatusCode -eq 200 -and $csrf.StatusCode -eq 200 -and [bool](($csrf.Content | ConvertFrom-Json).token)
  } catch { return $false }
}

# Windows command-line quoting, including trailing slashes and embedded quotes.
function Quote-Argument([string]$value) {
  return '"' + ([regex]::Replace([regex]::Replace($value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1')) + '"'
}

function Start-ServiceProcess {
  $current = Find-Service
  if ($current) {
    if (!(Test-Health)) { throw "RUNNING but unhealthy (PID $($current.ProcessId)); see logs." }
    Write-Output "Already RUNNING, PID $($current.ProcessId)"
    return
  }
  if (!(Test-Path -LiteralPath $jarPath)) { throw "Application JAR missing: $jarPath" }
  $occupied = $false
  try { Invoke-WebRequest "$healthBase/" -UseBasicParsing -TimeoutSec 1 | Out-Null; $occupied = $true } catch { $occupied = $null -ne $_.Exception.Response }
  if ($occupied) { throw "HTTP address already responds: $healthBase; refusing to start another instance." }
  foreach ($name in @('logs','run','data')) { [IO.Directory]::CreateDirectory((Join-Path $appRoot $name)) | Out-Null }
  $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
  $optionsFile = Join-Path $appRoot 'config\jvm.options'
  $javaArgs = @()
  if (Test-Path -LiteralPath $optionsFile) { $javaArgs = @(Get-Content -LiteralPath $optionsFile -Encoding UTF8 | ForEach-Object { $_.Trim() } | Where-Object { $_ -and !($_.StartsWith('#')) }) }
  $configUri = ([Uri](Join-Path $appRoot 'config\')).AbsoluteUri
  $javaArgs += @('-jar', $jarPath, "--spring.config.additional-location=$configUri")
  $argumentLine = ($javaArgs | ForEach-Object { Quote-Argument $_ }) -join ' '
  $child = Start-Process -FilePath $java -ArgumentList $argumentLine -WorkingDirectory $appRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $appRoot 'logs\console.log') -RedirectStandardError (Join-Path $appRoot 'logs\console-error.log') -PassThru
  $child.Id | Set-Content -LiteralPath $pidFile -Encoding ASCII
  $deadline = [DateTime]::UtcNow.AddSeconds($timeout)
  while ([DateTime]::UtcNow -lt $deadline) {
    if ($child.HasExited) { Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue; throw 'Java exited during startup; see logs/console-error.log and logs/console.log.' }
    if (Test-Health) { Write-Output "RUNNING, PID $($child.Id); homepage and API healthy; logs: $appRoot\logs"; return }
    Start-Sleep -Milliseconds 500
  }
  throw "Startup timed out; PID $($child.Id) retained. Check status/logs or run stop.bat."
}

function Stop-ServiceProcess {
  $current = Find-Service
  if (!$current) { Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue; Write-Output 'STOPPED'; return }
  Stop-Process -Id $current.ProcessId -ErrorAction Stop
  Wait-Process -Id $current.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
  if (Find-Service) { throw 'Application process did not stop; PID retained.' }
  Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
  Write-Output "STOPPED, PID $($current.ProcessId)"
}

$hash = [Security.Cryptography.SHA256]::Create()
$lockName = 'Local\CertificateManage_' + ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($appRoot.ToLowerInvariant())))).Replace('-','')
$hash.Dispose()
$mutex = New-Object Threading.Mutex($false, $lockName)
$locked = $false
try {
  $locked = $mutex.WaitOne(0)
  if (!$locked) { throw 'Another maintenance command is running.' }
  switch ($Action) {
    'start' { Start-ServiceProcess }
    'stop' { Stop-ServiceProcess }
    'restart' { Stop-ServiceProcess; Start-ServiceProcess }
    'status' {
      $current = Find-Service
      if (!$current) { Write-Output 'STOPPED'; exit 3 }
      if (!(Test-Health)) { Write-Output "RUNNING but unhealthy, PID $($current.ProcessId)"; exit 1 }
      Write-Output "RUNNING, PID $($current.ProcessId); homepage and API healthy"
    }
  }
  exit 0
} catch { [Console]::Error.WriteLine('ERROR: ' + $_.Exception.Message); exit 1 }
finally { if ($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose() }
