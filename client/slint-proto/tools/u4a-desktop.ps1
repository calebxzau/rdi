$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$processes = Get-CimInstance Win32_Process
$apps = @($processes | Where-Object { $_.Name -in @('java.exe','javaw.exe','rdi-slint-proto.exe') } | ForEach-Object {
    [pscustomobject]@{id=$_.ProcessId;name=$_.Name;compose=($_.CommandLine -match 'calebxzau.rdi.client.MainKt');minecraft=($_.CommandLine -match 'net.minecraft.client.main.Main|BootstrapLauncher|KnotClient');service=($_.CommandLine -match 'calebxzau.rdi.service.MainKt');parent=$_.ParentProcessId}
})
@{screens=@([Windows.Forms.Screen]::AllScreens | Select-Object DeviceName,Primary,Bounds);processes=$apps} | ConvertTo-Json -Depth 5
