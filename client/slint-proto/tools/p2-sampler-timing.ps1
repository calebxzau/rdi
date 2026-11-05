param([string]$Batch='clean')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$root="evidence/bg-port/p2/sampler-ab/$Batch"
New-Item -ItemType Directory -Force $root | Out-Null
try {
    foreach($content in @('p1','p2')) {
        $index=0
        # Counterbalance order; each condition gets four independent process runs.
        foreach($sampler in @('legacy','nearest','nearest','legacy','nearest','legacy','legacy','nearest')) {
            $case="$content-$index-$sampler"
            Set-Content "$root/current.txt" $case
            $flags=@()
            if($content -eq 'p1'){$flags+='--scene-p1-only'}
            if($sampler -eq 'legacy'){$flags+='--scene-legacy-sampler'}
            & ./target/release/examples/scene_preview.exe "$root/$case" 0 @flags *> "$root/$case.log"
            Set-Content "$root/$case.exit" $LASTEXITCODE
            if($LASTEXITCODE -ne 0){throw "Sampler timing $case failed"}
            $index++
        }
    }
    Set-Content "$root/complete.exit" 0
}catch{
    $_ | Out-String | Set-Content "$root/error.log"
    Set-Content "$root/complete.exit" 1
    throw
}
