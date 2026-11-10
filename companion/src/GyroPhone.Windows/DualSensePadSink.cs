using System.ComponentModel;
using System.Runtime.InteropServices;
using GyroPhone.Protocol.Mapping;
using Microsoft.Win32.SafeHandles;

namespace GyroPhone.Windows;

/// <summary>
/// A virtual DualSense through the optional GyroPhone pad driver (driver/ in the repo, installed by
/// its install.cmd). The driver's device interface takes a PLUG, then each 64-byte input report, then
/// an UNPLUG; closing the handle unplugs too, so a crashed companion leaves no pad behind. The control
/// codes and protocol version match driver/src/gyropad_ioctl.h. Submit runs under the engine lock, Plug and
/// Unplug on its pad thread.
/// </summary>
public sealed class DualSensePadSink : IDisposable
{
    private static readonly Guid Interface = new("9B7491BC-0FE9-49AA-BA4A-D5C8040BE226");
    private const uint ProtocolVersion = 1;
    private const uint IoctlVersion = 0x222000, IoctlPlug = 0x222004, IoctlUnplug = 0x222008, IoctlSubmit = 0x22200C;
    private const uint PadDualSense = 1;
    private const long ReplugGapMs = 1000;

    private readonly Action<string>? _log;
    private readonly byte[] _report = new byte[DualSenseReport.Size];
    private SafeFileHandle? _handle;
    private long _replugAtMs = -ReplugGapMs;

    private DualSensePadSink(Action<string>? log) => _log = log;

    /// <summary>The sink, or null when the driver isn't installed (its device interface isn't there).</summary>
    public static DualSensePadSink? Create(Action<string>? log)
    {
        string? path = FindDevice();
        if (path == null) return null;
        log?.Invoke("GyroPhone pad driver found: DualSense output available");
        return new DualSensePadSink(log);
    }

    public bool Plugged => _handle != null;

    public bool Plug()
    {
        if (_handle != null) return true;
        try
        {
            string path = FindDevice() ?? throw new IOException("the GyroPhone pad driver is not installed");
            var h = CreateFileW(path, GenericRead | GenericWrite, FileShareRead | FileShareWrite, 0, OpenExisting, 0, 0);
            if (h.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            Span<byte> buf = stackalloc byte[8];
            if (Control(h, IoctlVersion, [], buf) != 4 || BitConverter.ToUInt32(buf) != ProtocolVersion)
            {
                h.Dispose();
                throw new IOException("the GyroPhone pad driver is a different version; run its install.cmd again");
            }
            BitConverter.TryWriteBytes(buf, ProtocolVersion);
            BitConverter.TryWriteBytes(buf[4..], PadDualSense);
            Control(h, IoctlPlug, buf, []);
            _handle = h;
            return true;
        }
        catch (Exception e) when (e is IOException or Win32Exception)
        {
            _log?.Invoke($"plug DualSense failed: {e.Message}");
            return false;
        }
    }

    public void Unplug()
    {
        var h = _handle;
        _handle = null;
        if (h == null) return;
        try
        {
            Control(h, IoctlUnplug, [], []);
        }
        catch (Exception e) when (e is IOException or Win32Exception)
        {
            _log?.Invoke($"unplug DualSense failed: {e.Message}");
        }
        h.Dispose();
    }

    public void Submit(in Ds4Frame f, in Ds4Motion m)
    {
        var h = _handle;
        if (h == null) return;
        DualSenseReport.Build(_report, f, m);
        try
        {
            Control(h, IoctlSubmit, _report, []);
        }
        catch (Win32Exception)
        {
            // The pad went away underneath (driver reinstalled or disabled): plug a new one, and fail so
            // the engine sends the frame again.
            Replug();
            throw;
        }
    }

    private void Replug()
    {
        long now = Environment.TickCount64;
        if (now - _replugAtMs < ReplugGapMs) return;
        _replugAtMs = now;
        _log?.Invoke("DualSense pad was unplugged underneath; plugging it again");
        Unplug();
        Plug();
    }

    public void Dispose() => Unplug();

    // ---- Win32 ----

    private static unsafe int Control(SafeFileHandle h, uint code, ReadOnlySpan<byte> input, Span<byte> output)
    {
        fixed (byte* i = input)
        fixed (byte* o = output)
        {
            if (!DeviceIoControl(h, code, i, input.Length, o, output.Length, out int returned, 0))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return returned;
        }
    }

    /// <summary>The path of the driver's device interface, or null when it isn't there.</summary>
    private static string? FindDevice()
    {
        var g = Interface;
        if (CM_Get_Device_Interface_List_SizeW(out uint len, ref g, null, 0) != 0 || len <= 1) return null;
        var buf = new char[len];
        if (CM_Get_Device_Interface_ListW(ref g, null, buf, len, 0) != 0) return null;
        string first = new string(buf).Split('\0', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
        return first.Length > 0 ? first : null;
    }

    private const uint GenericRead = 0x80000000, GenericWrite = 0x40000000, FileShareRead = 1, FileShareWrite = 2, OpenExisting = 3;

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern SafeFileHandle CreateFileW(string name, uint access, uint share, nint security, uint disposition, uint flags, nint template);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern unsafe bool DeviceIoControl(SafeFileHandle h, uint code, byte* input, int inputLength,
        byte* output, int outputLength, out int returned, nint overlapped);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern int CM_Get_Device_Interface_List_SizeW(out uint length, ref Guid interfaceClass, string? deviceId, uint flags);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern int CM_Get_Device_Interface_ListW(ref Guid interfaceClass, string? deviceId, char[] buffer, uint length, uint flags);
}
