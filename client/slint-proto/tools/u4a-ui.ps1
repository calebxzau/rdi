param(
    [ValidateSet('Start','Control','State','Capture','Pick','Close','Measure','Click','Dpi','Game')][string]$Action='State',
    [string]$Tag='live-1', [double]$Scale=1,
    [string]$Command='', [string]$Name='', [string]$Text='', [double]$Value=0,
    [string]$Shot='screen', [string]$Path='', [string]$DialogTitle='选择客户端安装包', [int]$X=0,[int]$Y=0
)
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class U4aNative {
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls,string title);
 public delegate bool WindowEnum(IntPtr hwnd,IntPtr p);
 [DllImport("user32.dll")] static extern bool EnumWindows(WindowEnum callback,IntPtr p);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,System.Text.StringBuilder text,int max);
 [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr h,WindowEnum callback,IntPtr p);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd,System.Text.StringBuilder text,int max);
 [DllImport("user32.dll")] static extern int GetDlgCtrlID(IntPtr h);
 [DllImport("user32.dll")] static extern IntPtr GetParent(IntPtr h);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr SendMessage(IntPtr h,uint msg,IntPtr w,string text);
 [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h,uint msg,IntPtr w,IntPtr l);
 public static bool SelectFile(IntPtr dialog,string path) {
  IntPtr edit=IntPtr.Zero;
  EnumChildWindows(dialog,(h,p)=>{var b=new System.Text.StringBuilder(64);GetClassName(h,b,64);if(b.ToString()=="Edit" && (GetDlgCtrlID(h)==1001 || GetDlgCtrlID(h)==1148 || GetDlgCtrlID(GetParent(h))==1148)){edit=h;return false;}return true;},IntPtr.Zero);
  if(edit==IntPtr.Zero)return false;
  SendMessage(edit,0x000C,IntPtr.Zero,path);
  PostMessage(dialog,0x0111,new IntPtr(1),IntPtr.Zero);
  return true;
 }
 public static void Accept(IntPtr dialog) {PostMessage(dialog,0x0111,new IntPtr(1),IntPtr.Zero);}
 public static IntPtr Dialog(int process) {
  IntPtr result=IntPtr.Zero;
  EnumWindows((h,p)=>{uint id;GetWindowThreadProcessId(h,out id);if(id==(uint)process && IsWindowVisible(h)){var b=new System.Text.StringBuilder(512);GetWindowText(h,b,512);if(b.ToString().Contains("选择") || b.ToString().Contains("导出日志")){result=h;return false;}}return true;},IntPtr.Zero);
  return result;
 }
}
'@
$out="evidence/ui-port/u4a/$Tag"
$manifest="$out/run.json"
function Beat {
    $log=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
    $beats=[regex]::Matches($log,'Runtime probe data=(\{[^\r\n]+\})')
    if(!$beats.Count){throw 'No app heartbeat'}
    $beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
}
if($Action -eq 'Start') {
    if(Test-Path $manifest){throw 'Choose a new run tag'}
    New-Item -ItemType Directory -Force $out|Out-Null
    $env:RDI_PROTO_CONTROL=Join-Path $PWD "$out/control.json"
    $credentials=@(& wsl.exe -e cat /home/calebxzhou/.config/rdi/.test_usr_pwd)
    if($LASTEXITCODE -ne 0 -or $credentials.Count -lt 2){throw 'Test credentials unavailable'}
    try {
        $env:RDI_PROTO_USR=$credentials[0];$env:RDI_PROTO_PWD=$credentials[1]
        $app=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--scale',"$Scale",'--scene-memory-probe','--scene-probe-hold-ms=0') -RedirectStandardOutput "$out/app.log" -RedirectStandardError "$out/app.err"
        @{app=$app.Id;scale=$Scale;started=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content $manifest
        Get-FileHash ./target/release/rdi-slint-proto.exe|Select-Object Hash|ConvertTo-Json|Set-Content "$out/binary.json"
    } finally {
        Remove-Item Env:RDI_PROTO_USR,Env:RDI_PROTO_PWD,Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
        $credentials=$null
    }
    'Started'
    exit
}
$run=Get-Content $manifest -Raw|ConvertFrom-Json
$app=Get-Process -Id $run.app
$handle=$app.MainWindowHandle
switch($Action) {
    'Game' {
        $processes=Get-CimInstance Win32_Process
        $service=$processes|Where-Object {$_.ParentProcessId -eq $app.Id -and $_.Name -eq 'java.exe'}|Select-Object -First 1
        $game=$processes|Where-Object {$_.ParentProcessId -eq $service.ProcessId -and $_.Name -in @('java.exe','javaw.exe')}|Select-Object -First 1
        if(!$game){throw 'No game process owned by this run'}
        $gameProcess=Get-Process -Id $game.ProcessId
        $gameHandle=$gameProcess.MainWindowHandle
        if($gameHandle -eq [IntPtr]::Zero){throw 'Game has no window yet'}
        [P5Window]::Activate($gameHandle)|Out-Null
        Start-Sleep -Milliseconds 700
        Save-Window $gameHandle "$out/$Shot.png"
        @{id=$gameProcess.Id;title=$gameProcess.MainWindowTitle;core=($game.CommandLine -match 'rdi-5-mc-client');time=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content "$out/$Shot-game.json"
        "$out/$Shot.png"
    }
    'Dpi' { [P5Window]::Dpi($handle,[uint32](96*$Scale));Start-Sleep -Milliseconds 300;[P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null }
    'Click' {
        Require-Focus $handle
        $point=[IntPtr]::new($X -bor ($Y -shl 16))
        [P5Window]::PostMessage($handle,0x200,[IntPtr]::Zero,$point)|Out-Null
        [P5Window]::PostMessage($handle,0x201,[IntPtr]::new(1),$point)|Out-Null
        Start-Sleep -Milliseconds 120
        [P5Window]::PostMessage($handle,0x202,[IntPtr]::Zero,$point)|Out-Null
    }
    'Control' {
        $control="$out/control.json"
        $id=if(Test-Path $control){(Get-Content $control -Raw|ConvertFrom-Json).id+1}else{1}
        @{id=$id;action=$Command;name=$Name;text=$Text;value=$Value}|ConvertTo-Json -Compress|Set-Content "$control.tmp"
        Move-Item "$control.tmp" $control -Force
        [P5Window]::Activate($handle)|Out-Null
        "Command $id sent"
    }
    'State' {Beat|ConvertTo-Json -Compress}
    'Capture' {Require-Focus $handle;Save-Window $handle "$out/$Shot.png";Beat|ConvertTo-Json|Set-Content "$out/$Shot-state.json";"$out/$Shot.png"}
    'Pick' {
        $dialog=[U4aNative]::Dialog($app.Id)
        if($dialog -eq [IntPtr]::Zero) {
            $control="$out/control.json"
            $id=if(Test-Path $control){(Get-Content $control -Raw|ConvertFrom-Json).id+1}else{1}
            $picker=if($DialogTitle -eq '选择客户端安装包'){'installer.pickArchive'}else{'installer.pickDirectory'}
            @{id=$id;action='pack-action';name=$picker}|ConvertTo-Json -Compress|Set-Content "$control.tmp"
            Move-Item "$control.tmp" $control -Force
            [P5Window]::Activate($handle)|Out-Null
            Start-Sleep -Milliseconds 1500
            $dialog=[U4aNative]::Dialog($app.Id)
        }
        if($dialog -eq [IntPtr]::Zero -and $app.MainWindowTitle -eq $DialogTitle){$dialog=$app.MainWindowHandle}
        if($dialog -eq [IntPtr]::Zero){throw "Native picker not found. ProcessTitle=$($app.MainWindowTitle);Expected=$DialogTitle"}
        [P5Window]::Activate($dialog)|Out-Null
        Start-Sleep -Milliseconds 300
        if($DialogTitle -in @('选择整合包所在的文件夹','选择JDK目录')) {
            [Windows.Forms.Clipboard]::SetText([IO.Path]::GetFullPath($Path))
            [Windows.Forms.SendKeys]::SendWait('%d')
            [Windows.Forms.SendKeys]::SendWait('^a')
            [Windows.Forms.SendKeys]::SendWait('^v')
            [Windows.Forms.SendKeys]::SendWait('{ENTER}')
            Start-Sleep -Milliseconds 700
            [U4aNative]::Accept($dialog)
            'Native folder picker submitted'
            exit
        }
        if([U4aNative]::SelectFile($dialog,[IO.Path]::GetFullPath($Path))){'Native picker submitted via Win32 controls';exit}
        Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes
        $automation=[System.Windows.Automation.AutomationElement]::FromHandle($dialog)
        $edits=$automation.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::ControlTypeProperty,[System.Windows.Automation.ControlType]::Edit))
        foreach($edit in $edits) {
            "Edit=$($edit.Current.AutomationId):$($edit.Current.Name)"
            if($edit.Current.AutomationId -eq '1001') {
                $pattern=$edit.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                $pattern.SetValue([IO.Path]::GetFullPath($Path))
                $open=$automation.FindFirst([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.PropertyCondition]::new([System.Windows.Automation.AutomationElement]::AutomationIdProperty,'1'))
                $open.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
                'Native picker submitted via UI Automation'
                exit
            }
        }
        [Windows.Forms.Clipboard]::SetText([IO.Path]::GetFullPath($Path))
        [Windows.Forms.SendKeys]::SendWait('%n')
        [Windows.Forms.SendKeys]::SendWait('^a')
        [Windows.Forms.SendKeys]::SendWait('^v')
        Start-Sleep -Milliseconds 400
        [U4aNative]::Accept($dialog)
        [Windows.Forms.SendKeys]::SendWait('{ENTER}')
        Start-Sleep -Milliseconds 600
        if([U4aNative]::Dialog($app.Id) -ne [IntPtr]::Zero){[Windows.Forms.SendKeys]::SendWait('%o')}
        'Native path selected'
    }
    'Close' {Stop-Prototype $app $handle}
    'Measure' {
        $prefix="$out/app"
        $service=Get-CimInstance Win32_Process|Where-Object {$_.ParentProcessId -eq $app.Id -and $_.Name -eq 'java.exe'}|Select-Object -First 1
        $jvm=Get-Process -Id $service.ProcessId
        $samples=@(foreach($i in 1..5) {
            Require-Focus $handle
            Start-Sleep -Milliseconds 1200
            $state=Beat
            if(!$state.focused -or !$state.visible -or $state.minimized -or !$state.active -or $state.selected_tab -ne 1 -or $state.pack_screen -ne 0){throw 'Focused real-pack menu condition invalid'}
            if([P5Window]::ForegroundPid() -ne $app.Id){throw 'OS foreground invalid'}
            $app.Refresh();$jvm.Refresh()
            @{uiWorkingSet=$app.WorkingSet64;uiPrivate=$app.PrivateMemorySize64;serviceWorkingSet=$jvm.WorkingSet64;servicePrivate=$jvm.PrivateMemorySize64;foreground=[P5Window]::ForegroundPid();state=$state;utc=[DateTime]::UtcNow.ToString('o')}
        })
        $medians=@{}
        foreach($key in @('uiWorkingSet','uiPrivate','serviceWorkingSet','servicePrivate')){$medians[$key]=(@($samples|ForEach-Object {$_[$key]}|Sort-Object))[2]}
        @{samples=$samples;medians=$medians}|ConvertTo-Json -Depth 8|Set-Content "$out/memory.json"
        'Focused memory recorded'
    }
}
