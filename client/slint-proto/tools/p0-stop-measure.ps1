# Stop only our measurement helpers and the prototype they recorded.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$helpers = Get-CimInstance Win32_Process -Filter "Name = 'pwsh.exe'" | Where-Object { $_.CommandLine -match '[\\/]p0-(evidence|measure-pair|measure)\.ps1(?:[\s\"]|$)' }
foreach ($helper in $helpers) { Stop-Process -Id $helper.ProcessId -ErrorAction SilentlyContinue }
$records = Get-ChildItem evidence/bg-port/p0-*-memory.jsonl | ForEach-Object { Get-Content $_ | ForEach-Object { $_ | ConvertFrom-Json } }
foreach ($processId in ($records.ProcessId | Sort-Object -Unique)) {
 $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
 if ($process -and $process.ProcessName -eq 'rdi-slint-proto') { $process.CloseMainWindow() | Out-Null; $process.WaitForExit(5000) | Out-Null }
}
