# Shared by install.ps1 and uninstall.ps1.

$GpSubject = 'CN=GyroPhone pad driver (this PC only)'
$GpHardwareId = 'Root\GyroPhonePad'
$GpStage = Join-Path $env:ProgramData 'GyroPhone\driver'

function Write-Step([string]$text) { Write-Host $text }
function Write-Done([string]$text) { Write-Host $text -ForegroundColor Green }
function Write-Fail([string]$text) { Write-Host $text -ForegroundColor Red }
function Wait-Close { Write-Host ''; Read-Host 'Press Enter to close' | Out-Null }

# Runs the script again as administrator (one Windows prompt) and exits this copy.
function Assert-Admin([string]$Script) {
    $me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if ($me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { return }
    try {
        $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru `
            -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$Script`"")
        exit $p.ExitCode
    } catch {
        Write-Fail 'This needs administrator rights, and Windows did not give them (the prompt was declined).'
        Wait-Close
        exit 1
    }
}

# This PC's GyroPhone signing certificates in one LocalMachine store.
function Get-GpCertificates([string]$Store) {
    @(Get-ChildItem "Cert:\LocalMachine\$Store" | Where-Object { $_.Subject -eq $GpSubject })
}

# The "GyroPhone virtual pad" devices (present or not).
function Get-GpDevices {
    @(Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains $GpHardwareId })
}

# signtool.exe from an installed Windows SDK, newest first; $null when there is none.
function Find-SignTool {
    $roots = @("${env:ProgramFiles(x86)}\Windows Kits\10\bin", "$env:ProgramFiles\Windows Kits\10\bin")
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { continue }
        $t = Get-ChildItem $r -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
            Where-Object { $_.Directory.Name -eq 'x64' } | Sort-Object FullName -Descending | Select-Object -First 1
        if ($t) { return $t.FullName }
    }
    return $null
}

# SetupAPI calls for a root-enumerated device, as Microsoft's devcon tool makes one.
function Add-GpSetupApi {
    if ('GpSetup' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class GpSetup
{
    [StructLayout(LayoutKind.Sequential)]
    struct SP_DEVINFO_DATA { public uint cbSize; public Guid ClassGuid; public uint DevInst; public IntPtr Reserved; }

    [DllImport("setupapi.dll", SetLastError = true)]
    static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid classGuid, IntPtr hwndParent);
    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool SetupDiCreateDeviceInfoW(IntPtr set, string name, ref Guid classGuid, string description,
        IntPtr hwndParent, uint flags, ref SP_DEVINFO_DATA data);
    [DllImport("setupapi.dll", SetLastError = true)]
    static extern bool SetupDiSetDeviceRegistryPropertyW(IntPtr set, ref SP_DEVINFO_DATA data, uint property, byte[] buffer, uint size);
    [DllImport("setupapi.dll", SetLastError = true)]
    static extern bool SetupDiCallClassInstaller(uint function, IntPtr set, ref SP_DEVINFO_DATA data);
    [DllImport("setupapi.dll", SetLastError = true)]
    static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
    [DllImport("newdev.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool UpdateDriverForPlugAndPlayDevicesW(IntPtr hwndParent, string hardwareId, string infPath, uint flags, out bool reboot);

    const uint DICD_GENERATE_ID = 1, SPDRP_HARDWAREID = 1, DIF_REGISTERDEVICE = 0x19, INSTALLFLAG_FORCE = 1;
    static readonly Guid SystemClass = new Guid("4D36E97D-E325-11CE-BFC1-08002BE10318");

    public static void CreateRootDevice(string hardwareId)
    {
        Guid cls = SystemClass;
        IntPtr set = SetupDiCreateDeviceInfoList(ref cls, IntPtr.Zero);
        if (set == new IntPtr(-1)) throw new Win32Exception();
        try
        {
            var data = new SP_DEVINFO_DATA { cbSize = (uint)Marshal.SizeOf(typeof(SP_DEVINFO_DATA)) };
            if (!SetupDiCreateDeviceInfoW(set, "System", ref cls, null, IntPtr.Zero, DICD_GENERATE_ID, ref data))
                throw new Win32Exception();
            byte[] ids = Encoding.Unicode.GetBytes(hardwareId + "\0\0");
            if (!SetupDiSetDeviceRegistryPropertyW(set, ref data, SPDRP_HARDWAREID, ids, (uint)ids.Length))
                throw new Win32Exception();
            if (!SetupDiCallClassInstaller(DIF_REGISTERDEVICE, set, ref data))
                throw new Win32Exception();
        }
        finally { SetupDiDestroyDeviceInfoList(set); }
    }

    // Puts the driver from infPath on every device with this hardware id; true when Windows wants a restart.
    public static bool InstallDriver(string hardwareId, string infPath)
    {
        bool reboot;
        if (!UpdateDriverForPlugAndPlayDevicesW(IntPtr.Zero, hardwareId, infPath, INSTALLFLAG_FORCE, out reboot))
            throw new Win32Exception();
        return reboot;
    }
}
'@
}
