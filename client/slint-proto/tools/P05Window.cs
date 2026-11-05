using System;
using System.Runtime.InteropServices;
public class P05Window {
 [StructLayout(LayoutKind.Sequential)] public struct RECT {public int L,T,R,B;}
 public delegate bool WindowEnum(IntPtr hwnd,IntPtr p);
 [DllImport("user32.dll")] static extern bool EnumWindows(WindowEnum callback,IntPtr p);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,System.Text.StringBuilder text,int max);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a,uint b,bool attach);
 [StructLayout(LayoutKind.Sequential)] struct MSG {public IntPtr hwnd;public uint message;public UIntPtr wParam;public IntPtr lParam;public uint time;public int x,y;}
 [DllImport("user32.dll")] static extern bool PeekMessage(out MSG msg,IntPtr hwnd,uint min,uint max,uint remove);
 [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int cmd);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 public static IntPtr Find(uint wanted) {
  IntPtr result=IntPtr.Zero;long area=0;
  EnumWindows((h,p)=>{uint pid;GetWindowThreadProcessId(h,out pid);RECT r;
   if(pid==wanted&&IsWindowVisible(h)&&GetWindowRect(h,out r)) {var title=new System.Text.StringBuilder(512);GetWindowText(h,title,512);if(title.ToString()!="RDI Slint Prototype")return true;long a=(long)(r.R-r.L)*(r.B-r.T);if(a>area){area=a;result=h;}}
   return true;},IntPtr.Zero);return result;
 }
 public static uint ForegroundPid() {uint p;GetWindowThreadProcessId(GetForegroundWindow(),out p);return p;}
 public static bool Focus(IntPtr hwnd) {
  MSG message;PeekMessage(out message,IntPtr.Zero,0,0,0);
  uint ignored;uint current=GetCurrentThreadId(),fg=GetWindowThreadProcessId(GetForegroundWindow(),out ignored),target=GetWindowThreadProcessId(hwnd,out ignored);
  bool a=fg!=current&&AttachThreadInput(current,fg,true),b=target!=current&&target!=fg&&AttachThreadInput(current,target,true);
  try {BringWindowToTop(hwnd);SetForegroundWindow(hwnd);return GetForegroundWindow()==hwnd;}
  finally {if(b)AttachThreadInput(current,target,false);if(a)AttachThreadInput(current,fg,false);}
 }
}
