param([string]$Tag='runtime')
$ErrorActionPreference='Stop'
Set-Location "$PSScriptRoot/.."
try {& "$PSScriptRoot/u3-interact.ps1" -Tag "$Tag-interaction" *> "evidence/ui-port/u3/$Tag-interaction.log"}
catch {$_ | Out-File "evidence/ui-port/u3/$Tag-interaction-failure.log"}
try {& "$PSScriptRoot/u3-shell.ps1" -Tag "$Tag-shell" *> "evidence/ui-port/u3/$Tag-shell.log"}
catch {$_ | Out-File "evidence/ui-port/u3/$Tag-shell-failure.log"}
& "$PSScriptRoot/u3-measure.ps1" -Tag "$Tag-measure" *> "evidence/ui-port/u3/$Tag-measure.log"
