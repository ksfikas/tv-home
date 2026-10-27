# Windows version of tools/disable.sh: disable every package in a debloat list over ADB.
# Packages are disabled for user 0, never uninstalled. Undo with tools/windows/restore.ps1 <same list>.
#
#   adb connect 192.168.1.50:5555            # accept "Allow USB debugging?" on the TV
#   .\tools\windows\debloat.ps1 examples\xiaomi-tv-box-s-gen2\debloat.txt
#
# Needs adb on PATH (winget install Google.PlatformTools). One pm call per package, because
# chained pm calls can silently stop after the first.
param([Parameter(Mandatory = $true)][string]$List, [string]$Device = "")
$ErrorActionPreference = "Stop"
$target = if ($Device) { @("-s", $Device) } else { @() }
$packages = Get-Content $List | ForEach-Object { ($_ -replace "#.*$", "").Trim() } | Where-Object { $_ }
foreach ($p in $packages) {
    $out = & adb @target shell pm disable-user --user 0 $p 2>&1
    "{0,-48} {1}" -f $p, ($out -join " ")
}
