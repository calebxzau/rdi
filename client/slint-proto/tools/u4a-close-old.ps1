$ErrorActionPreference = 'Stop'
$games = Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -match 'net.minecraft.client.main.Main|BootstrapLauncher|KnotClient' }
if ($games) { throw 'A game is running; preserve it.' }
Get-Process rdi-slint-proto -ErrorAction SilentlyContinue | ForEach-Object { $_.CloseMainWindow() | Out-Null }
