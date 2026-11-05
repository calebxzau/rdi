param([string]$Phase = 'build')
Set-Location (Split-Path $PSScriptRoot -Parent)
& cargo $Phase *> "evidence/$Phase.log"
$code=$LASTEXITCODE
Set-Content "evidence/$Phase.exit" $code
exit $code
