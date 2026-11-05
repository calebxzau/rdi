param([int]$ProcessId)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class P05Enum {
 public delegate bool CB(IntPtr h,IntPtr p);
 [DllImport("user32.dll")] public static extern bool EnumWindows(CB cb,IntPtr p);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h,StringBuilder s,int max);
 public static void List(uint wanted) {EnumWindows((h,x)=>{uint p;GetWindowThreadProcessId(h,out p);if(p==wanted){var s=new StringBuilder(512);GetWindowText(h,s,512);Console.WriteLine(h+" "+s);}return true;},IntPtr.Zero);}
}
'@
[P05Enum]::List($ProcessId)
