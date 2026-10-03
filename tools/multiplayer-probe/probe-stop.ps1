param(
  [Parameter(Mandatory=$true)][string]$GameDir,
  [string]$RunRoot = ''
)
$ErrorActionPreference='SilentlyContinue'
if(-not $RunRoot){ $RunRoot=Join-Path $GameDir 'multiplayer-probe-runs' }
$control=Join-Path $RunRoot '.control'
$current=Join-Path $control 'current-run.txt'
if(-not (Test-Path $current)){ throw 'No active probe.' }
$run=(Get-Content $current -Raw).Trim()
if(-not $run -or -not (Test-Path $run)){ throw 'Active probe directory is missing.' }
$marker=[ordered]@{ts=(Get-Date).ToString('o');event='PROBE_STOP_REQUESTED'} | ConvertTo-Json -Compress
Add-Content -LiteralPath (Join-Path $run 'markers.jsonl') -Value $marker -Encoding UTF8
New-Item -ItemType File -Force -Path (Join-Path $run 'stop.request') | Out-Null
$pidFile=Join-Path $run 'monitor.pid'
if(Test-Path $pidFile){
  $monitorPid=[int](Get-Content $pidFile -Raw)
  for($i=0;$i -lt 20;$i++){ if(-not (Get-Process -Id $monitorPid -ErrorAction SilentlyContinue)){break}; Start-Sleep -Milliseconds 250 }
}
foreach($name in @('rw-agent-game.log','rw-agent-bootstrap.log')){
  $src=Join-Path $GameDir $name
  if(Test-Path $src){ Copy-Item -LiteralPath $src -Destination (Join-Path $run $name) -Force }
}
$states=@(); $stateFile=Join-Path $run 'state.jsonl'
if(Test-Path $stateFile){ $states=Get-Content $stateFile | ForEach-Object { try{ $_ | ConvertFrom-Json }catch{} } }
$netFile=Join-Path $run 'network.jsonl'
$netCount=if(Test-Path $netFile){(Get-Content $netFile).Count}else{0}
$summary=[ordered]@{
  stoppedAt=(Get-Date).ToString('o');run=$run;stateSamples=$states.Count;networkRows=$netCount
  sawNetworked=[bool]($states | Where-Object {$_.networked -eq $true} | Select-Object -First 1)
  sessions=@($states | ForEach-Object {$_.sessionId} | Where-Object {$_} | Select-Object -Unique)
  teams=@($states | ForEach-Object {if($_.player){$_.player.teamId}} | Where-Object {$_ -ne $null} | Select-Object -Unique)
  outcomes=@($states | ForEach-Object {if($_.match){$_.match.outcome}} | Where-Object {$_} | Select-Object -Unique)
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'quick-summary.json') -Encoding UTF8
Remove-Item -LiteralPath $current -Force
Write-Host 'Probe stopped.'; Write-Host "Run: $run"; Write-Host ($summary | ConvertTo-Json -Compress -Depth 5)
