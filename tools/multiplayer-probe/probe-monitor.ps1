param(
  [Parameter(Mandatory=$true)][string]$RunDir,
  [Parameter(Mandatory=$true)][string]$GameDir,
  [int]$Port = 47653,
  [int]$IntervalMs = 1000
)
$ErrorActionPreference='SilentlyContinue'
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$networkLog = Join-Path $RunDir 'network.jsonl'
$stateLog = Join-Path $RunDir 'state.jsonl'
$processLog = Join-Path $RunDir 'process.jsonl'
$errorLog = Join-Path $RunDir 'errors.log'
$stopFile = Join-Path $RunDir 'stop.request'
function J($o){ $o | ConvertTo-Json -Compress -Depth 8 }
function Stamp(){ (Get-Date).ToString('o') }
function AppendJson($path,$obj){ Add-Content -LiteralPath $path -Value (J $obj) -Encoding UTF8 }
$seenProcesses=@{}
$lastStateSig=''
while(-not (Test-Path -LiteralPath $stopFile)){
  $now=Stamp
  $procs=Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'corrodinggames\.rts\.java\.Main') -or
    $_.Name -like 'Rusted Warfare*.exe'
  }
  foreach($p in $procs){
    if(-not $seenProcesses.ContainsKey([string]$p.ProcessId)){
      $seenProcesses[[string]$p.ProcessId]=$true
      AppendJson $processLog ([ordered]@{ts=$now;event='PROCESS_SEEN';pid=$p.ProcessId;name=$p.Name;commandLine=$p.CommandLine})
    }
    $tcp=Get-NetTCPConnection -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue
    foreach($c in $tcp){
      AppendJson $networkLog ([ordered]@{ts=$now;proto='TCP';pid=$p.ProcessId;state=[string]$c.State;localAddress=$c.LocalAddress;localPort=$c.LocalPort;remoteAddress=$c.RemoteAddress;remotePort=$c.RemotePort})
    }
    $udp=Get-NetUDPEndpoint -OwningProcess $p.ProcessId -ErrorAction SilentlyContinue
    foreach($u in $udp){
      AppendJson $networkLog ([ordered]@{ts=$now;proto='UDP_LOCAL';pid=$p.ProcessId;localAddress=$u.LocalAddress;localPort=$u.LocalPort})
    }
  }
  try{
    $s=Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/state" -f $Port) -Method Get -TimeoutSec 1
    $summary=[ordered]@{
      ts=$now;status=$s.status;sessionId=$s.sessionId;networked=$s.networked;replay=$s.replay
      frame=$s.frame;gameTimeMs=$s.gameTimeMs;map=$s.map;player=$s.player
      ownUnitCount=@($s.ownUnits).Count;match=$s.match
    }
    $sig=J $summary
    if($sig -ne $lastStateSig){ AppendJson $stateLog $summary; $lastStateSig=$sig }
  }catch{
    $msg=$_.Exception.Message
    if($msg){ Add-Content -LiteralPath $errorLog -Value ("{0}`tSTATE`t{1}" -f $now,$msg) -Encoding UTF8 }
  }
  Start-Sleep -Milliseconds $IntervalMs
}
AppendJson $processLog ([ordered]@{ts=(Stamp);event='PROBE_STOPPED'})
