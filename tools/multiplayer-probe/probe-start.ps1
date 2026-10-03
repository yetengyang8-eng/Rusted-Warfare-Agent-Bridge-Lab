param(
  [Parameter(Mandatory=$true)][string]$GameDir,
  [string]$RunRoot = '',
  [int]$Port = 47653
)
$ErrorActionPreference='Stop'
$toolDir=Split-Path -Parent $MyInvocation.MyCommand.Path
if(-not $RunRoot){ $RunRoot=Join-Path $GameDir 'multiplayer-probe-runs' }
New-Item -ItemType Directory -Force -Path $RunRoot | Out-Null
$control=Join-Path $RunRoot '.control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$current=Join-Path $control 'current-run.txt'
if(Test-Path $current){
  $old=(Get-Content $current -Raw).Trim()
  if($old -and -not (Test-Path (Join-Path $old 'stop.request'))){ throw "Probe already active: $old" }
}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$run=Join-Path $RunRoot ("run-$stamp")
New-Item -ItemType Directory -Force -Path $run | Out-Null
$meta=[ordered]@{startedAt=(Get-Date).ToString('o');gameDir=$GameDir;port=$Port;computer=$env:COMPUTERNAME;user=$env:USERNAME}
$meta | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $run 'meta.json') -Encoding UTF8
$args=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $toolDir 'probe-monitor.ps1'),'-RunDir',$run,'-GameDir',$GameDir,'-Port',[string]$Port)
$p=Start-Process -FilePath 'powershell.exe' -ArgumentList $args -WindowStyle Minimized -PassThru
Set-Content -LiteralPath (Join-Path $run 'monitor.pid') -Value $p.Id -Encoding ASCII
Set-Content -LiteralPath $current -Value $run -Encoding UTF8
$marker=[ordered]@{ts=(Get-Date).ToString('o');event='PROBE_STARTED';note='Start probe before multiplayer UI'} | ConvertTo-Json -Compress
Set-Content -LiteralPath (Join-Path $run 'markers.jsonl') -Value $marker -Encoding UTF8
Write-Host 'Probe started.'
Write-Host "Run: $run"
Write-Host 'Now launch RW-Agent-Start.bat and use multiplayer normally.'
Write-Host 'Raw IP/port data stays local until reviewed and sanitized.'
