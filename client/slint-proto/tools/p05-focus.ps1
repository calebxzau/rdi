param([int]$ProcessId)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type -TypeDefinition (Get-Content tools/P05Window.cs -Raw)
$shell=New-Object -ComObject WScript.Shell
Start-Sleep -Seconds 2
for($i=0;$i -lt 5;$i++){
 $handle=[P05Window]::Find($ProcessId)
 $native=[P05Window]::Focus($handle)
 $com=$shell.AppActivate($ProcessId)
 "$native $com $([P05Window]::ForegroundPid())" | Add-Content evidence/bg-port/p0.5/focus-helper.log
 Start-Sleep -Seconds 1
}
