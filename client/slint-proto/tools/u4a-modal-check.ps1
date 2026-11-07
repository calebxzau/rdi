$ErrorActionPreference='Stop'
. "$PSScriptRoot/p5-common.ps1"
foreach($scale in @(1,2)) {
    $out="evidence/ui-port/u4a/modal-scale$scale"
    if(Test-Path "$out/exit"){throw 'Preserve existing modal-check evidence'}
    New-Item -ItemType Directory -Force $out|Out-Null
    $control=Join-Path $PWD "$out/control.json"
    $env:RDI_PROTO_CONTROL=$control
    $app=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--gallery','--no-scene','--scene-memory-probe','--scale',"$scale") -RedirectStandardOutput "$out/app.log" -RedirectStandardError "$out/app.err"
    Remove-Item Env:RDI_PROTO_CONTROL
    $script:sequence=0
    function Command($action,$value=0) {
        $script:sequence++
        @{id=$script:sequence;action=$action;value=$value}|ConvertTo-Json -Compress|Set-Content "$control.tmp"
        Move-Item "$control.tmp" $control -Force
        $deadline=[DateTime]::UtcNow.AddSeconds(8)
        do {
            Start-Sleep -Milliseconds 100
            $log=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
            if($log -match "Runtime control applied\s+id=$($script:sequence)\b"){break}
            if([DateTime]::UtcNow -gt $deadline){throw 'Control acknowledgement missing'}
        }while($true)
    }
    try {
        Start-Sleep -Seconds 3
        $handle=[P5Window]::Find($app.Id)
        Require-Focus $handle
        [P5Window]::Dpi($handle,[uint32](96*$scale))
        Start-Sleep -Milliseconds 200
        [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$scale),(720*$scale),20)|Out-Null
        Start-Sleep -Seconds 1
        Save-Window $handle "$out/before.png"
        Command 'gallery-dialog' 1
        Command 'gallery-toast'
        Save-Window $handle "$out/modal-toast.png"
        $x=[int](894*$scale);$y=[int](680*$scale)
        $point=[IntPtr]::new($x -bor ($y -shl 16))
        [P5Window]::PostMessage($handle,0x200,[IntPtr]::Zero,$point)|Out-Null
        [P5Window]::PostMessage($handle,0x201,[IntPtr]::new(1),$point)|Out-Null
        Start-Sleep -Milliseconds 100
        [P5Window]::PostMessage($handle,0x202,[IntPtr]::Zero,$point)|Out-Null
        Start-Sleep -Milliseconds 1200
        $log=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
        $beats=[regex]::Matches($log,'Runtime probe data=(\{[^\r\n]+\})')
        $state=$beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
        if(!$state.modal -or $state.toast_visible){throw 'Toast did not close above the modal'}
        $state|ConvertTo-Json|Set-Content "$out/toast-dismissed.json"
        Save-Window $handle "$out/modal-toast-dismissed.png"
        Command 'gallery-dialog' 0
        Command 'gallery-page' 1
        Command 'gallery-state' 2
        Save-Window $handle "$out/empty-grid.png"
        Command 'gallery-state' 3
        Save-Window $handle "$out/failed-grid.png"
        0|Set-Content "$out/exit"
    } finally {Stop-Prototype $app $handle}
}
