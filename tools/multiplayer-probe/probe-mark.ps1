param(
  [Parameter(Mandatory=$true)][string]$Label,
  [Parameter(Mandatory=$true)][string]$RunRoot
)
$current=Join-Path (Join-Path $RunRoot '.control') 'current-run.txt'
if(-not (Test-Path $current)){ throw 'No active probe.' }
$run=(Get-Content $current -Raw).Trim()
if(-not $run){ throw 'No active probe.' }
$row=[ordered]@{ts=(Get-Date).ToString('o');event='USER_MARK';label=$Label} | ConvertTo-Json -Compress
Add-Content -LiteralPath (Join-Path $run 'markers.jsonl') -Value $row -Encoding UTF8
Write-Host "Marked: $Label"
