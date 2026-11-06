param([string]$Tag='gu3')
$ErrorActionPreference='Stop'
Set-Location "$PSScriptRoot/.."
$out='evidence/ui-port/u3'
& cargo clippy --workspace --all-targets -- -D warnings *> "$out/clippy.log"
if($LASTEXITCODE){throw 'Workspace Clippy failed'}
Get-FileHash target/release/rdi-slint-proto.exe|Select-Object Algorithm,Hash|ConvertTo-Json|Set-Content "$out/final-binary.json"
& "$PSScriptRoot/u3-gallery.ps1" -Scale 1 -Tag $Tag *> "$out/$Tag-gallery-1.log"
& "$PSScriptRoot/u3-gallery.ps1" -Scale 2 -Tag $Tag *> "$out/$Tag-gallery-2.log"
& "$PSScriptRoot/u3-interact.ps1" -Tag "$Tag-interaction" *> "$out/$Tag-interaction.log"
& "$PSScriptRoot/u3-measure.ps1" -Tag "$Tag-measure" *> "$out/$Tag-measure.log"
