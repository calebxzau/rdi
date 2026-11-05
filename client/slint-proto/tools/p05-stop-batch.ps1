$ErrorActionPreference='Stop'
$helpers=Get-CimInstance Win32_Process -Filter "Name = 'pwsh.exe'" | Where-Object { $_.CommandLine -match '[\\/]p05-(continue|measure-all|run-case)\.ps1(?:[\s\"]|$)' }
$children=Get-CimInstance Win32_Process -Filter "Name = 'rdi-slint-proto.exe' OR Name = 'p0-reference.exe'" | Where-Object {$_.ParentProcessId -in $helpers.ProcessId}
foreach($helper in $helpers){Stop-Process -Id $helper.ProcessId -ErrorAction SilentlyContinue}
foreach($child in $children){Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue}
