# Windows version of tools/restore.sh: re-enable every package in a debloat list, bring back the
# stock Google TV launcher and Google's setup app, and make the stock launcher the home screen again.
#
#   .\tools\windows\restore.ps1 examples\xiaomi-tv-box-s-gen2\debloat.txt
param(
    [Parameter(Mandatory = $true)][string]$List,
    [string]$Device = "",
    [string]$StockLauncher = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
)
$ErrorActionPreference = "Stop"
$target = if ($Device) { @("-s", $Device) } else { @() }
$packages = Get-Content $List | ForEach-Object { ($_ -replace "#.*$", "").Trim() } | Where-Object { $_ }
foreach ($p in $packages) {
    $out = & adb @target shell pm enable $p 2>&1
    "{0,-48} {1}" -f $p, ($out -join " ")
}
& adb @target shell pm enable com.google.android.tungsten.setupwraith
& adb @target shell pm enable ($StockLauncher -split "/")[0]
& adb @target shell cmd package set-home-activity --user 0 $StockLauncher
& adb @target shell input keyevent KEYCODE_HOME
"Restored. Press Home on the remote."
