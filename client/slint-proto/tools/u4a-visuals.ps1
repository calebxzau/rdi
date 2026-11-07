param([double]$Scale=1,[string]$Tag='visual')
. "$PSScriptRoot/p5-common.ps1"
$out="evidence/ui-port/u4a/$Tag-scale$Scale"
if(Test-Path "$out/run.json"){throw 'Choose a new visual tag'}
New-Item -ItemType Directory -Force $out|Out-Null
$control=Join-Path $PWD "$out/control.json"
$env:RDI_PROTO_CONTROL=$control
$app=Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList @('--gallery','--scale',"$Scale",'--scene-memory-probe','--scene-probe-hold-ms=0') -PassThru -RedirectStandardOutput "$out/app.log" -RedirectStandardError "$out/app.err"
Remove-Item Env:RDI_PROTO_CONTROL
@{app=$app.Id;scale=$Scale;fixtureOnly=$true}|ConvertTo-Json|Set-Content "$out/run.json"
Get-FileHash target/release/rdi-slint-proto.exe|Select-Object Hash|ConvertTo-Json|Set-Content "$out/binary.json"
$script:id=0
function Command($action,$name='',$text='') {
    $script:id++
    @{id=$script:id;action=$action;name=$name;text=$text}|ConvertTo-Json -Compress -Depth 20|Set-Content "$control.tmp"
    Move-Item "$control.tmp" $control -Force
    $deadline=[DateTime]::UtcNow.AddSeconds(8)
    do {
        Start-Sleep -Milliseconds 100
        $log=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
        if($log -match "Runtime control applied\s+id=$($script:id)\b"){return}
        if([DateTime]::UtcNow -gt $deadline){throw "Visual command $($script:id) was not acknowledged"}
    }while($true)
}
function Snapshot($name,$value) {Command 'pack-fixture' $name ($value|ConvertTo-Json -Compress -Depth 20)}
function Clone-DisplayState($value) { $value|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable }
function Capture($name) {
    Start-Sleep -Milliseconds 400
    Save-Window $handle "$out/$name.png"
}
try {
    Start-Sleep -Seconds 4
    $handle=[P5Window]::Find($app.Id)
    Require-Focus $handle
    [P5Window]::Dpi($handle,[uint32](96*$Scale))
    Start-Sleep -Milliseconds 300
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null
    Start-Sleep -Seconds 1
    $fixtures=Get-Content protocol/fixtures/messages.json -Raw|ConvertFrom-Json -AsHashtable
    $install=Clone-DisplayState ($fixtures|Where-Object {$_.event -eq 'installer.state'}).params
    $install.editor.name='测试整合包';$install.editor.comment='测试简介'
    $install.runtimes=@(
        @{mcVersion='V211';modLoader='neoforge';label='1.21.1 NeoForge'},
        @{mcVersion='V201';modLoader='forge';label='1.20.1 Forge'},
        @{mcVersion='V201';modLoader='Fabric';label='1.20.1 Fabric'}
    )
    $install.editor.contents=@(
        @{key='required';name='必需Mod';detail='整合包必需内容';kind='Mod';optional=$false;selected=$true;icon='';size=1024},
        @{key='optional';name='可选Mod';detail='可以选择是否安装';kind='Mod';optional=$true;selected=$false;icon='';size=1024}
    )
    $candidate=@{index=0;name='测试整合包';directory='C:/Games/.minecraft';gameDirectory='C:/Games/.minecraft/versions/Test';mcVersion='1.21.1';modLoader='neoforge'}
    Command 'packs-install'
    foreach($screen in @('install-source','install-loading','install-empty','install-error','install-candidates','install-reading','install-directory','install-create','install-content-empty','install-content','install-missing')) {
        $state=Clone-DisplayState $install
        switch($screen) {
            'install-source' {$state.Remove('editor');$state.candidates=@($candidate)}
            'install-loading' {$state.Remove('editor');$state.scanning=$true;$state.archiveLoading=$true}
            'install-empty' {$state.Remove('editor')}
            'install-error' {$state.Remove('editor');$state.error='无法读取整合包安装包'}
            'install-candidates' {$state.Remove('editor');$second=Clone-DisplayState $candidate;$second.name='另一个版本';$second.index=1;$state.pickedCandidates=@($candidate,$second)}
            'install-reading' {$state.editor.source='reading';$state.editor.previewLoading=$true;$state.editor.canSubmit=$false;$state.editor.progress='扫描压缩包条目';$state.editor.contents=@()}
            'install-directory' {$state.editor.source='directory';$state.editor.sourceDirectory='C:/Games/Test';$state.editor.versionSelectable=$true;$state.editor.contents=@()}
            'install-create' {$state.editor.source='empty';$state.editor.contents=@()}
            'install-content-empty' {$state.editor.contents=@()}
            'install-missing' {$state.editor.confirmMissingFiles=$true;$state.editor.missingFiles=@('无法下载的Mod')}
        }
        Snapshot 'installer.state' $state
        Capture $screen
    }
    $install.Remove('editor');Snapshot 'installer.state' $install
    Command 'packs-options'
    $options=Clone-DisplayState ($fixtures|Where-Object {$_.event -eq 'packOptions.state'}).params
    foreach($screen in @('options-normal','options-loading','options-error')) {
        $state=Clone-DisplayState $options
        if($screen -eq 'options-loading'){$state.loading=$true}
        if($screen -eq 'options-error'){$state.loadFailed=$true;$state.error='读取整合包设置失败：测试错误'}
        Snapshot 'packOptions.state' $state;Capture $screen
    }
    $play=Clone-DisplayState ($fixtures|Where-Object {$_.event -eq 'packPlay.state'}).params
    Snapshot 'packOptions.state' $options
    foreach($screen in @('play-normal','play-loading','play-error')) {
        $state=Clone-DisplayState $play
        if($screen -eq 'play-loading'){$state.busy=$true}
        if($screen -eq 'play-error'){$state.error='请输入游戏昵称';$state.nickname=''}
        Snapshot 'packPlay.state' $state;Capture $screen
    }
    Snapshot 'packPlay.state' @{closed=$true}
    Command 'shell-console'
    foreach($screen in @('console-normal','console-empty')) {
        $lines=if($screen -eq 'console-normal'){@(1..40|ForEach-Object {"[INFO] Minecraft已启动 · 日志行$_"})}else{@()}
        Snapshot 'console.state' @{sessionId='fixture';sessions=@(@{id='fixture';title='测试整合包'});lines=$lines}
        Capture $screen
    }
    Command 'shell-tasks'
    foreach($screen in @('task-normal','task-error','task-empty')) {
        $task=@{runId='fixture';title='导入测试整合包';message='复制文件';fraction=0.5;error='';terminal=$false;status=1;rows=@(@{key='root';title='复制文件';detail='50%';depth=0;group=$false;status=1;fraction=0.5})}
        if($screen -eq 'task-error'){$task.error='无法读取文件';$task.message='导入失败';$task.terminal=$true;$task.status=3;$task.rows[0].status=3}
        if($screen -eq 'task-empty'){$task.rows=@();$task.message='准备中';$task.fraction=-1}
        Snapshot 'task.state' $task;Capture $screen
    }
    $text=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
    $beats=[regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
    $beat=$beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
    if($beat.scale -ne $Scale -or $beat.width -ne 1280*$Scale -or $beat.height -ne 720*$Scale){throw 'Screenshot scale/size mismatch'}
    $beat|ConvertTo-Json|Set-Content "$out/final-state.json"
    0|Set-Content "$out/exit"
} finally {Stop-Prototype $app $handle}
