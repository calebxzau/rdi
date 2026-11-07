$ErrorActionPreference='Stop'
$screens=@('install-source','install-loading','install-empty','install-error','install-candidates','install-reading','install-directory','install-create','install-content-empty','install-content','install-missing','options-normal','options-loading','options-error','play-normal','console-normal','console-empty','task-normal','task-error')
foreach($scale in @(1,2)) {
    foreach($screen in $screens) {
        & "$PSScriptRoot/u4a-reference.ps1" -Screen $screen -Scale $scale -Tag compose
    }
}
0|Set-Content "$PSScriptRoot/../evidence/ui-port/u4a/compose-visuals.exit"
