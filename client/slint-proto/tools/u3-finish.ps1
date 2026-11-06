param([string]$Tag='finished')
$ErrorActionPreference='Stop'
Set-Location "$PSScriptRoot/.."
& "$PSScriptRoot/u3-validate.ps1"
Get-FileHash target/release/rdi-slint-proto.exe|Select-Object Algorithm,Hash|ConvertTo-Json|Set-Content evidence/ui-port/u3/final-binary.json
& "$PSScriptRoot/u3-gallery.ps1" -Scale 1 -Tag $Tag -Passive *> "evidence/ui-port/u3/$Tag-gallery-1.log"
& "$PSScriptRoot/u3-gallery.ps1" -Scale 2 -Tag $Tag -Passive *> "evidence/ui-port/u3/$Tag-gallery-2.log"
& "$PSScriptRoot/u3-interact.ps1" -Tag "$Tag-scroll" -ScrollOnly *> "evidence/ui-port/u3/$Tag-scroll.log"
try {& "$PSScriptRoot/u3-measure.ps1" -Tag "$Tag-measure" -Runs 1 -NoWarmup *> "evidence/ui-port/u3/$Tag-measure.log"}
catch {$_ | Out-File "evidence/ui-port/u3/$Tag-measure-failure.log"}
