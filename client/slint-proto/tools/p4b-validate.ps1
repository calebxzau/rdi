$ErrorActionPreference='Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out='evidence/bg-port/p4b'
function Step($name,[string[]]$cmd){
 & cargo @cmd *> "$out/$name.log"
 $code=$LASTEXITCODE
 Set-Content "$out/$name.exit" $code
 Write-Output "$name exit=$code"
 if($code -ne 0){exit $code}
}
& rustfmt --edition 2024 src/scene/crowd.rs src/scene/crowd_sim.rs *> "$out/fmt.log"
Step 'test' @('test','--all-targets')
Step 'clippy' @('clippy','--all-targets','--','-D','warnings')
Step 'clippy-probe' @('clippy','--all-targets','--all-features','--','-D','warnings')
Step 'release' @('build','--release','--bins','--examples')
