using System.ComponentModel;
using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol.Mapping;
using Microsoft.Win32.SafeHandles;

namespace GyroPhone.Windows;

/// <summary>
/// Keyboard and mouse through the optional GyroPhone input driver (input-driver/ in the repo, installed by its
/// install.cmd): a virtual USB mouse and keyboard with media keys. Each Inject is one DeviceIoControl that carries
/// every report of the hand-off (<see cref="HidInputReports"/>), straight on the calling thread: no queue, no thread
/// hop. The control codes and protocol version match input-driver/src/gpinput_ioctl.h. The devices belong to this
/// handle and go away when it closes, so a crashed companion leaves no key held.
/// </summary>
public sealed class HidInputSink : IDisposable
{
    private static readonly Guid Interface = new("6E0B53F1-7C2D-4C6A-9E45-0A8D3B6F2C71");
    private const uint ProtocolVersion = 1;
    private const uint IoctlVersion = 0x222400, IoctlPlug = 0x222404, IoctlUnplug = 0x222408, IoctlSubmit = 0x22240C;
    // Events per SUBMIT, so even a release of everything fits HidInputReports.MaxBytes.
    private const int Chunk = 64;

    /// <summary>The virtual devices' ids, as in Windows' device paths (VID_1209&amp;PID_6770): raw input readers skip them.</summary>
    public const string DeviceIds = "VID_1209&PID_6770";

    private readonly Action<string>? _log;
    private readonly HidInputReports _reports = new();
    private readonly byte[] _buf = new byte[HidInputReports.MaxBytes];
    private SafeFileHandle? _handle;

    private HidInputSink(Action<string>? log) => _log = log;

    /// <summary>True when the driver is installed (its device interface is there).</summary>
    public static bool Installed => FindDevice() != null;

    /// <summary>The sink with its devices plugged, or null when the driver isn't installed or won't plug.</summary>
    public static HidInputSink? Create(Action<string>? log)
    {
        if (FindDevice() == null) return null;
        var s = new HidInputSink(log);
        if (!s.Plug()) return null;
        log?.Invoke("GyroPhone input driver found: keys and mouse go through its virtual mouse and keyboard");
        return s;
    }

    public bool Plugged => _handle != null;

    private bool Plug()
    {
        try
        {
            string path = FindDevice() ?? throw new IOException("the GyroPhone input driver is not installed");
            var h = CreateFileW(path, GenericRead | GenericWrite, FileShareRead | FileShareWrite, 0, OpenExisting, 0, 0);
            // Closed on every failure below, so a refused plug leaves no handle holding the device.
            try
            {
                if (h.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                Span<byte> buf = stackalloc byte[4];
                if (Control(h, IoctlVersion, [], buf) != 4 || BitConverter.ToUInt32(buf) != ProtocolVersion)
                    throw new IOException("the GyroPhone input driver is a different version; run its install.cmd again");
                BitConverter.TryWriteBytes(buf, ProtocolVersion);
                Control(h, IoctlPlug, buf, []);
            }
            catch
            {
                h.Dispose();
                throw;
            }
            _reports.Reset();
            _handle = h;
            return true;
        }
        catch (Exception e) when (e is IOException or Win32Exception)
        {
            _log?.Invoke($"input driver: plug failed: {e.Message}");
            return false;
        }
    }

    /// <summary>
    /// Sends the events as reports. False when the device went away underneath (driver disabled or reinstalled):
    /// the sink is then unplugged and Windows has let go of everything it held.
    /// </summary>
    public bool Inject(ReadOnlySpan<InjectEvent> events)
    {
        var h = _handle;
        if (h == null) return false;
        while (!events.IsEmpty)
        {
            int take = Math.Min(events.Length, Chunk);
            int n = _reports.Build(events[..take], _buf);
            events = events[take..];
            if (n == 0) continue;
            if (!Submit(h, n))
            {
                Unplug();
                return false;
            }
        }
        return true;
    }

    private unsafe bool Submit(SafeFileHandle h, int n)
    {
        fixed (byte* p = _buf)
        {
            if (DeviceIoControl(h, IoctlSubmit, p, n, null, 0, out _, 0)) return true;
        }
        _log?.Invoke($"input driver: submit failed (error {Marshal.GetLastPInvokeError()}); back to SendInput");
        return false;
    }

    public void Unplug()
    {
        var h = _handle;
        _handle = null;
        _reports.Reset();
        if (h == null) return;
        try
        {
            Control(h, IoctlUnplug, [], []);
        }
        catch (Exception e) when (e is IOException or Win32Exception)
        {
            _log?.Invoke($"input driver: unplug failed: {e.Message}");
        }
        h.Dispose();
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

/// <summary>
/// The engine's keyboard and mouse sink: SendInput, unless "Use input driver" (Performance) is on and the driver is installed
/// and plugged (<see cref="Retry"/>). Inject runs under the engine lock; <see cref="UseInputDriver"/> is changed
/// only through the engine (<c>HostEngine.SetUseInputDriver</c>), which lets go of everything first, so no key stays held
/// on the route it leaves.
/// </summary>
public sealed class KeyMouseRouter : IKeyMouseSink, IDisposable
{
    private readonly SendInputSink _sendInput;
    private readonly Action<string>? _log;
    private HidInputSink? _driver;
    private volatile bool _useInputDriver;
    private volatile bool _viaDriver;

    public KeyMouseRouter(SendInputSink sendInput, bool useInputDriver, Action<string>? log)
    {
        _sendInput = sendInput;
        _useInputDriver = useInputDriver;
        _log = log;
    }

    /// <summary>The input driver is installed and its devices are plugged.</summary>
    public bool DriverReady => _driver?.Plugged == true;

    /// <summary>"Use input driver" (Performance): keys and mouse through the driver when it is there; off (the default), SendInput.</summary>
    public bool UseInputDriver => _useInputDriver;

    /// <summary>
    /// Switches "Use input driver". On plugs the driver's mouse and keyboard now (when it's installed); off unplugs them, so
    /// no idle virtual mouse and keyboard stay in Windows and nothing holds the device (its uninstall can remove it).
    /// Called through <c>HostEngine.SetUseInputDriver</c>, under the engine lock, after everything held was let go.
    /// </summary>
    public void SetUseInputDriver(bool on)
    {
        _useInputDriver = on;
        if (on) Retry();
        else
        {
            _driver?.Dispose();
            _driver = null;
        }
    }

    public SendInputSink SendInput => _sendInput;

    public string Route => _viaDriver ? "input driver" : "SendInput";

    public bool Inject(ReadOnlySpan<InjectEvent> events)
    {
        var d = _driver;
        if (d != null && d.Plugged && _useInputDriver)
        {
            _viaDriver = true;
            // Gone underneath: false, so the engine sends the held set again (now through SendInput).
            return d.Inject(events);
        }
        _viaDriver = false;
        return _sendInput.Inject(events);
    }

    /// <summary>
    /// Plugs the driver's mouse and keyboard when "Use input driver" is on, it's installed and they aren't plugged yet:
    /// once this instance owns the port (an instance on its way out may still hold them), and again when the driver turns
    /// up later. Called through <c>HostEngine.ChangeKeyMouseRoute</c>, under the engine lock.
    /// </summary>
    public bool Retry()
    {
        if (!_useInputDriver) return false;
        if (DriverReady) return true;
        _driver?.Dispose();
        _driver = HidInputSink.Create(_log);
        return DriverReady;
    }

    public void Dispose() => _driver?.Dispose();
}
