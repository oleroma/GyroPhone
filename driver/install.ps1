<#
  Installs (or updates) the GyroPhone pad driver on this PC. Run install.cmd; it asks for admin once.

  1. Makes this PC's own signing certificate, once: "GyroPhone pad driver (this PC only)", kept in
     the computer's certificate store with a private key that can't be exported, valid 10 years.
  2. Trusts that certificate on this PC only (Trusted Root and Trusted Publishers), so Windows
     installs the driver without asking. No other PC trusts it.
  3. Signs this driver package with it: a copy in %ProgramData%\GyroPhone\driver.
  4. Creates the "GyroPhone virtual pad" device (Root\GyroPhonePad) if it isn't there yet and puts
     the driver on it.

  Test signing stays off: Windows checks the code signature of kernel drivers only, and this one runs
  in user mode (UMDF) on Windows' own Virtual HID Framework. uninstall.cmd undoes all of it.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-Admin -Script $PSCommandPath

try {
    $source = $PSScriptRoot
    foreach ($f in 'GyroPhonePad.inf', 'GyroPhonePad.dll', 'GyroPhonePad.cat') {
        if (-not (Test-Path (Join-Path $source $f))) { throw "$f is missing next to install.ps1." }
    }

    # 1. This PC's certificate.
    $cert = Get-GpCertificates -Store 'My' | Where-Object { $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date).AddDays(30) } |
        Sort-Object NotAfter -Descending | Select-Object -First 1
    if ($cert) {
        Write-Step "Using this PC's signing certificate ($($cert.Thumbprint))."
    } else {
        Write-Step 'Making a signing certificate for this PC...'
        $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject $GpSubject `
            -FriendlyName 'GyroPhone pad driver (this PC only)' `
            -CertStoreLocation 'Cert:\LocalMachine\My' -KeyExportPolicy NonExportable `
            -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -NotAfter (Get-Date).AddYears(10) `
            -TextExtension @('2.5.29.19={critical}{text}ca=0')
        Write-Step "  made $($cert.Thumbprint)"
    }

    # 2. Trust it on this PC (public part only).
    $cer = Join-Path $env:TEMP 'GyroPhonePad.cer'
    Export-Certificate -Cert $cert -FilePath $cer -Force | Out-Null
    foreach ($store in 'Root', 'TrustedPublisher') {
        if (-not (Test-Path "Cert:\LocalMachine\$store\$($cert.Thumbprint)")) {
            Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\LocalMachine\$store" | Out-Null
            Write-Step "Trusted it in $store."
        }
    }
    Remove-Item $cer -Force

    # 3. Sign a copy of the package.
    if (Test-Path $GpStage) { Remove-Item $GpStage -Recurse -Force }
    New-Item -ItemType Directory -Path $GpStage | Out-Null
    foreach ($f in 'GyroPhonePad.inf', 'GyroPhonePad.dll', 'GyroPhonePad.cat', 'VERSION.txt') {
        $p = Join-Path $source $f
        if (Test-Path $p) { Copy-Item $p $GpStage }
    }
    $cat = Join-Path $GpStage 'GyroPhonePad.cat'
    Write-Step 'Signing the driver package...'
    $signed = $false
    try {
        $r = Set-AuthenticodeSignature -FilePath $cat -Certificate $cert -HashAlgorithm SHA256
        $signed = $r.Status -eq 'Valid'
        if (-not $signed) { Write-Step "  PowerShell signing gave '$($r.Status)': $($r.StatusMessage)" }
    } catch {
        Write-Step "  PowerShell signing failed: $($_.Exception.Message)"
    }
    if (-not $signed) {
        $signtool = Find-SignTool
        if (-not $signtool) { throw 'Could not sign the package: PowerShell could not, and signtool.exe (Windows SDK) was not found.' }
        Write-Step "  trying $signtool"
        & $signtool sign /sm /s My /sha1 $cert.Thumbprint /fd sha256 $cat
        if ($LASTEXITCODE -ne 0) { throw "signtool failed ($LASTEXITCODE)." }
    }
    $check = Get-AuthenticodeSignature -FilePath $cat
    if ($check.Status -ne 'Valid') { throw "The package signature isn't valid: $($check.Status) $($check.StatusMessage)" }
    Write-Step '  signed.'

    # 4. The device and its driver.
    Add-GpSetupApi
    $inf = Join-Path $GpStage 'GyroPhonePad.inf'
    $devices = @(Get-GpDevices)
    if ($devices.Count -eq 0) {
        Write-Step 'Creating the "GyroPhone virtual pad" device...'
        [GpSetup]::CreateRootDevice($GpHardwareId)
    }
    Write-Step 'Installing the driver on it...'
    $reboot = [GpSetup]::InstallDriver($GpHardwareId, $inf)
    $devices = @(Get-GpDevices)
    foreach ($d in $devices) { Write-Step "  $($d.FriendlyName): $($d.Status)" }
    if ($reboot) { Write-Step 'Windows wants a restart to finish.' }
    Write-Done 'Done. Choose "DualSense" as the output in the GyroPhone companion.'
    $code = 0
} catch {
    Write-Fail $_.Exception.Message
    $code = 1
}
Wait-Close
exit $code
