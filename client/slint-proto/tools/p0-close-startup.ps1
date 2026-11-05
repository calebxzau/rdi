# The startup helper that created these processes is identified by its recorded parent PID.
param([Parameter(Mandatory=$true)][int]$ParentProcessId)
$ErrorActionPreference = 'Stop'
$processes = Get-CimInstance Win32_Process -Filter "Name = 'rdi-slint-proto.exe'" | Where-Object { $_.ParentProcessId -eq $ParentProcessId -and $_.ExecutablePath -eq 'C:\Users\calebxzhou\Documents\coding\rdi5\client\slint-proto\target\release\rdi-slint-proto.exe' }
foreach ($process in $processes) { Stop-Process -Id $process.ProcessId }
