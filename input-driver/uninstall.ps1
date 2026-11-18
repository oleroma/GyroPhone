<#
  Removes the GyroPhone input driver (virtual mouse and keyboard) from this PC and everything install.ps1 added: the device, the
  driver in Windows' driver store, this PC's signing certificate (from all three stores, unless the
  pad driver still uses it) and the signed copy in %ProgramData%\GyroPhone\input-driver. Run uninstall.cmd; it asks for admin once.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Admin -Script $PSCommandPath

# The companion holds the device open while it uses the driver, and Windows then can't remove it until a restart.
if (Get-Process -Name 'GyroPhoneCompanion' -ErrorAction SilentlyContinue) {
    Write-Fail 'The GyroPhone companion is running. Quit it from its tray icon (turn off "Start when my phone appears" if it is on), then run uninstall.cmd again.'
    Wait-Close
    exit 1
}

$failed = $false
function Try-Step([string]$what, [scriptblock]$do) {
    try { & $do } catch { Write-Fail "$what failed: $($_.Exception.Message)"; $script:failed = $true }
}

Try-Step 'Removing the device' {
    foreach ($d in @(Get-GpDevices)) {
        Write-Step "Removing $($d.FriendlyName) ($($d.InstanceId))..."
        & pnputil.exe /remove-device $d.InstanceId | Out-Host
    }
}

Try-Step 'Removing the driver from the driver store' {
    # Our packages in the store are the oem*.inf files that install Root\GyroPhoneInput.
    $infs = Get-ChildItem (Join-Path $env:windir 'INF') -Filter 'oem*.inf' |
        Where-Object { Select-String -Path $_.FullName -Pattern $GpHardwareId -SimpleMatch -Quiet }
    foreach ($i in $infs) {
        Write-Step "Deleting driver package $($i.Name)..."
        & pnputil.exe /delete-driver $i.Name /uninstall /force | Out-Host
    }
}

Try-Step 'Removing the certificate' {
    if (Test-OtherDriver) {
        Write-Step 'Kept the certificate: the GyroPhone pad driver still uses it.'
        return
    }
    foreach ($store in 'Root', 'TrustedPublisher', 'My') {
        foreach ($c in @(Get-GpCertificates -Store $store)) {
            Remove-Item "Cert:\LocalMachine\$store\$($c.Thumbprint)" -DeleteKey:($store -eq 'My') -Force
            Write-Step "Removed certificate $($c.Thumbprint) from $store."
        }
    }
}

Try-Step 'Removing the signed copy' {
    if (Test-Path $GpStage) { Remove-Item $GpStage -Recurse -Force; Write-Step "Removed $GpStage." }
}

if ($failed) { Write-Fail 'Some steps failed (see above).' } else { Write-Done 'Done. The GyroPhone input driver is gone from this PC; the companion uses SendInput again after a restart.' }
Wait-Close
exit ([int]$failed)
