# Fails unless :mediaproc:test and :mediaproc:nativePreloadTest really ran, with no test skipped.
# A test that skips itself on some platform would otherwise look like a pass there.
$failed = $false
foreach ($task in 'test', 'nativePreloadTest') {
    $files = @(Get-ChildItem "client/mediaproc/build/test-results/$task" -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue)
    $tests = 0
    $skipped = 0
    foreach ($file in $files) {
        $suite = ([xml](Get-Content -Raw $file.FullName)).testsuite
        $tests += [int]$suite.tests
        $skipped += [int]$suite.skipped
        $output = $suite.SelectSingleNode('system-out').InnerText
        $output -split "`n" | Where-Object { $_ -like 'legacy sample *' } | ForEach-Object { Write-Output $_.Trim() }
    }
    Write-Output "${task}: $tests tests, $skipped skipped"
    if ($tests -eq 0 -or $skipped -gt 0) {
        Write-Output "::error::${task} must run at least one test and skip none"
        $failed = $true
    }
}
if ($failed) { exit 1 }
