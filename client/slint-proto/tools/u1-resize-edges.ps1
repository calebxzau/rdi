param([Parameter(Mandatory)][string]$Tag,[switch]$Menu)
# Drags all 8 window edges and corners with the real mouse, then checks that the middle of the
# window does not resize and that the title bar still drags afterwards.
. "$PSScriptRoot/p5-common.ps1"
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class U1ResizeMouse {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
 public static void Down(){mouse_event(2,0,0,0,UIntPtr.Zero);}
 public static void Up(){mouse_event(4,0,0,0,UIntPtr.Zero);}
}
'@
$out="evidence/ui-port/u1/$Tag"
if(Test-Path $out){throw 'Select a new tag'}
New-Item -ItemType Directory -Force $out|Out-Null
$args2=@('--scene-memory-probe','--scene-probe-hold-ms=0')
if(!$Menu){$args2=@('--ui-proof')+$args2}
$p=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList $args2 -RedirectStandardOutput "$out/run-app.log" -RedirectStandardError "$out/run-app.err"
$h=[IntPtr]::Zero
function Rect { $r=New-Object P5Window+RECT;[P5Window]::GetWindowRect($h,[ref]$r)|Out-Null;return $r }
function Drag([int]$X,[int]$Y,[int]$Dx,[int]$Dy){
 [U1ResizeMouse]::SetCursorPos($X,$Y)|Out-Null;Start-Sleep -Milliseconds 200
 [U1ResizeMouse]::Down();Start-Sleep -Milliseconds 150
 foreach($step in 1..5){[U1ResizeMouse]::SetCursorPos($X+$Dx*$step/5,$Y+$Dy*$step/5)|Out-Null;Start-Sleep -Milliseconds 40}
 [U1ResizeMouse]::Up();Start-Sleep -Milliseconds 700
}
$cases=@(
 @{name='left';   px=0;   py=0.5;dx=-60;dy=0;  moves='L'},
 @{name='right';  px=1;   py=0.5;dx=-60;dy=0;  moves='R'},
 @{name='top';    px=0.5; py=0;  dx=0;  dy=60; moves='T'},
 @{name='bottom'; px=0.5; py=1;  dx=0;  dy=-60;moves='B'},
 @{name='top-left';    px=0;py=0;dx=50; dy=50; moves='LT'},
 @{name='top-right';   px=1;py=0;dx=-50;dy=50; moves='RT'},
 # Outward: the inward drags above have brought the height close to the 600 logical minimum.
 @{name='bottom-left'; px=0;py=1;dx=50; dy=50; moves='LB'},
 @{name='bottom-right';px=1;py=1;dx=-50;dy=50; moves='RB'}
)
$rows=@();$failed=$false
try {
 Start-Sleep -Seconds 4;$h=[P5Window]::Find($p.Id);Require-Focus $h
 foreach($c in $cases){
  $b=Rect;$w=$b.R-$b.L;$t=$b.B-$b.T
  # 2 physical pixels inside the edge, which is inside the handle at every scale.
  $x=if($c.px -eq 0){$b.L+2}elseif($c.px -eq 1){$b.R-3}else{$b.L+[int]($w*0.37)}
  $y=if($c.py -eq 0){$b.T+2}elseif($c.py -eq 1){$b.B-3}else{$b.T+[int]($t*0.37)}
  Drag $x $y $c.dx $c.dy
  $a=Rect
  $d=@{L=$a.L-$b.L;T=$a.T-$b.T;R=$a.R-$b.R;B=$a.B-$b.B}
  $ok=$true
  foreach($side in 'L','T','R','B'){
   $moved=[math]::Abs($d[$side]) -ge 20
   if($moved -ne $c.moves.Contains($side)){$ok=$false}
  }
  if(!$ok){$failed=$true}
  $rows+=[pscustomobject]@{case=$c.name;dL=$d.L;dT=$d.T;dR=$d.R;dB=$d.B;ok=$ok}
 }
 # The old bug put the left and top handles on the center lines of the window.
 $b=Rect
 Drag ([int](($b.L+$b.R)/2)) ([int]($b.T+($b.B-$b.T)*0.37)) 40 0
 $a=Rect;$center=($a.L -eq $b.L -and $a.R -eq $b.R -and $a.T -eq $b.T -and $a.B -eq $b.B)
 if(!$center){$failed=$true}
 # A native resize swallows the button release, so check the grab is not stuck.
 $b=Rect
 Drag ($b.L+600) ($b.T+40) 40 30
 $a=Rect;$title=(($a.L-$b.L) -eq 40 -and ($a.T-$b.T) -eq 30 -and ($a.R-$a.L) -eq ($b.R-$b.L))
 if(!$title){$failed=$true}
 Save-Window $h "$out/after.png"
 $rows|Format-Table -AutoSize|Out-String|Write-Output
 "center_no_resize=$center title_drag_after=$title failed=$failed"
 @{rows=$rows;center_no_resize=$center;title_drag_after=$title;failed=$failed;menu=[bool]$Menu}|ConvertTo-Json -Depth 4 > "$out/result.json"
} finally {if(!$p.HasExited){Stop-Prototype $p $h}}
if($failed){exit 1}
