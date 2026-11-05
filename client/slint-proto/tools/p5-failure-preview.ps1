$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($case in @('validation','slow','loader')) {
 $flags=@(switch($case){'validation'{'--scene-fail'}'slow'{'--scene-low-performance-ms=0.001'}'loader'{'--scene-startup-fail'}})
 & ./target/release/examples/scene_preview.exe "evidence/bg-port/p5/failure-preview/recheck-$case" 0 @flags *> "evidence/bg-port/p5/failure-preview-recheck-$case.log"
 Set-Content "evidence/bg-port/p5/failure-preview-recheck-$case.exit" $LASTEXITCODE
 if($LASTEXITCODE -eq 0){throw "Expected diagnostic failure missing: $case"}
 $text=Get-Content "evidence/bg-port/p5/failure-preview-recheck-$case.log" -Raw
 $pattern=switch($case){'validation'{'Scene wgpu: Validation Error'}'slow'{'terminal performance fallback'}'loader'{'Diagnostic scene loader failure'}}
 if($text -notmatch $pattern){throw "Unexpected diagnostic error: $case"}
}
Set-Content evidence/bg-port/p5/failure-preview-complete.exit 0
