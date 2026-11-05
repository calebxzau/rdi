$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p3/cpu | Out-Null
foreach($case in @('p2-create','p3-create','p2-render','p3-render')) {
 $flags=@('--frames=0')
 if($case -like 'p2-*'){$flags+='--scene-p2-only'}
 if($case -like '*-render'){$flags[0]='--frames=1800'}
 $prefix="evidence/bg-port/p3/cpu/$case"
 if(Test-Path "$prefix-memory.jsonl"){throw 'CPU diagnostic output already exists; archive before rerunning'}
 $p=Start-Process ./target/release/examples/p3_memory_probe.exe -ArgumentList $flags -PassThru -RedirectStandardOutput "$prefix.log" -RedirectStandardError "$prefix.err.log"
 $start=Get-Date
 while(!$p.HasExited) {
  $p.Refresh()
  if($p.HasExited){break}
  $gpu=@(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory -ErrorAction SilentlyContinue | Where-Object Name -Like "pid_$($p.Id)_*")
  [pscustomobject]@{Time=(Get-Date).ToUniversalTime().ToString('o');Seconds=((Get-Date)-$start).TotalSeconds;WorkingSet=$p.WorkingSet64;Private=$p.PrivateMemorySize64;Dedicated=($gpu|Measure-Object DedicatedUsage -Sum).Sum;Shared=($gpu|Measure-Object SharedUsage -Sum).Sum} | ConvertTo-Json -Compress | Add-Content "$prefix-memory.jsonl"
  Start-Sleep -Milliseconds 500
 }
 $p.WaitForExit()
 Set-Content "$prefix.exit" $p.ExitCode
 if($p.ExitCode -ne 0){throw "CPU diagnostic $case failed"}
}
Set-Content evidence/bg-port/p3/cpu/complete.exit 0
