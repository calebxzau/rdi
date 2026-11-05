$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = [IO.Path]::GetFullPath('evidence/ui-port/u1/windows-final')
New-Item -ItemType Directory -Force $out | Out-Null
& target/release/examples/adapter_probe.exe *> "$out/adapter.log"
if ($LASTEXITCODE) { throw 'Adapter probe failed' }
& target/release/examples/scene_preview.exe $out 0 15 45 --crowd=50 --capture-only *> "$out/scene.log"
if ($LASTEXITCODE) { throw 'Windows scene preview failed' }
$env:PATH = (Resolve-Path 'third_party/dxc/x64').Path + ';' + $env:PATH
$test = Get-ChildItem target/debug/deps/rdi_renderer-*.exe | Sort-Object LastWriteTime -Descending | Select-Object -First 1
foreach ($compiler in 'fxc','dxc') {
    $env:WGPU_DX12_COMPILER=$compiler
    foreach ($scale in '1','1.25','2') {
        $env:GLASS_CAPTURE_SCALE=$scale
        $env:GLASS_CAPTURE_PATH="$out/glass-$compiler-$scale.png"
        & $test.FullName --ignored --exact glass::tests::capture_fixed_frame *> "$out/glass-$compiler-$scale.log"
        $captureExit = $LASTEXITCODE
        Set-Content "$out/glass-$compiler-$scale.exit" $captureExit
        if ($captureExit) { throw "Glass capture failed: exit=$captureExit" }
    }
}
Remove-Item Env:GLASS_CAPTURE_PATH,Env:GLASS_CAPTURE_SCALE,Env:WGPU_DX12_COMPILER
Get-FileHash target/release/rdi-slint-proto.exe | Select-Object Algorithm,Hash | ConvertTo-Json > "$out/binary.json"
Set-Content "$out/capture.exit" 0
