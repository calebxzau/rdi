param([string]$Tag='auth')
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u1/$Tag"
if (Test-Path $out) { throw 'Use a new evidence tag' }
New-Item -ItemType Directory -Force $out | Out-Null
$credentials = Get-Content '\\wsl.localhost\Debian\home\calebxzhou\.config\rdi\.test_usr_pwd'
if ($credentials.Count -lt 2) { throw 'Expected two credential lines' }
$env:RDI_PROTO_USR=$credentials[0];$env:RDI_PROTO_PWD=$credentials[1];$credentials=$null
$env:RDI_PROTO_CONTROL=[IO.Path]::GetFullPath("$out/control.json")
$prefix="$out/run"
$p=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--ui-proof','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$handle=[IntPtr]::Zero
try {
    Start-Sleep -Seconds 4
    $handle=[P5Window]::Find($p.Id)
    Require-Focus $handle
    @{id=1;action='proof-login'} | ConvertTo-Json -Compress | Set-Content $env:RDI_PROTO_CONTROL
    $deadline=(Get-Date).AddSeconds(40)
    do {
        Require-Focus $handle
        Start-Sleep -Seconds 1
        $text=Get-Content "$prefix-app.log" -Raw
        if ($text -match 'U1 login failed') { throw 'Login failed; inspect account-free error log' }
        if ((Get-Date) -gt $deadline) { throw 'Login timed out' }
    } until ($text -match 'U1 login succeeded')
    Save-Window $handle "$out/login-success.png"
    [P5Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero)|Out-Null
    if (!$p.WaitForExit(10000)) { throw 'Close timed out' }
    Set-Content "$out/exit.code" $p.ExitCode
} finally {
    if (!$p.HasExited) { Stop-Prototype $p $handle }
    Remove-Item Env:RDI_PROTO_USR,Env:RDI_PROTO_PWD,Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
}
