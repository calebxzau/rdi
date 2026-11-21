param([string]$Name='list_windows',[string]$Arguments='{}',[string]$Out='')
$ErrorActionPreference='Stop'
$body=@{jsonrpc='2.0';id=2;method='tools/call';params=@{name=$Name;arguments=($Arguments|ConvertFrom-Json)}}|ConvertTo-Json -Depth 40 -Compress
$response=Invoke-WebRequest 'http://127.0.0.1:55566/mcp' -Method Post -ContentType 'application/json' -Headers @{Accept='application/json, text/event-stream'} -Body $body -TimeoutSec 30
if($Out){[IO.File]::WriteAllText($Out,$response.Content)}
if(-not $Out){$response.Content}
