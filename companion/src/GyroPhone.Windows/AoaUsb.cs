using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;
using static GyroPhone.Windows.UsbNative;

namespace GyroPhone.Windows;

/// <summary>Where the direct USB link is, for the tray.</summary>
public enum UsbDirectState
{
    /// <summary>Turned off in the menu.</summary>
    Off,
    /// <summary>No Android phone with USB debugging on the cable.</summary>
    NoPhone,
    /// <summary>adb (or another app) holds the phone's USB interface, so it can't be switched.</summary>
    AdbBusy,
    /// <summary>The phone was asked to switch to accessory mode and should come back as one.</summary>
    Switching,
    /// <summary>
    /// adb is not the phone's first USB function (USB tethering or file transfer is on), and Windows
    /// only passes device requests from the first one, so the switch is refused.
    /// </summary>
    WrongUsbMode,
    /// <summary>The phone is in accessory mode but Windows has no WinUSB driver for it.</summary>
    NeedsDriver,
    Connected,
    Failed,
}

/// <summary>
/// The direct USB link (Android Open Accessory). Once a second thread gp-usb looks at the USB
/// devices: a phone in accessory mode with the WinUSB driver gets a <see cref="UsbBridge"/> into the
/// UDP server; an Android phone in normal mode (found by its adb interface, the one interface of
/// a phone Windows lets an app send requests to) is asked to switch to accessory mode, naming
/// GyroPhone, so the app on the phone picks the cable up. Nothing is installed here: the accessory
/// interface needs WinUSB once (Zadig), which the user does.
/// </summary>
public sealed class AoaUsbLink : IDisposable
{
    public const string Manufacturer = "GyroPhone";
    public const string Model = "GyroPhone Companion";

    private static readonly TimeSpan RetrySwitch = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan RetryNoAoa = TimeSpan.FromMinutes(1);

    private readonly int _serverPort;
    private readonly Action<string> _log;
    private readonly ManualResetEventSlim _wake = new(false);
    private readonly Dictionary<string, (DateTime At, TimeSpan Wait, bool WrongMode)> _tried = new(StringComparer.OrdinalIgnoreCase);
    private Thread? _thread;
    private volatile bool _stopping;
    private UsbBridge? _bridge;
    private string _lastLogged = "";
    private volatile bool _stopAdb;

    public AoaUsbLink(int serverPort, bool enabled, Action<string> log)
    {
        _serverPort = serverPort;
        _enabled = enabled;
        _log = log;
    }

    private volatile bool _enabled;

    public bool Enabled
    {
        get => _enabled;
        set
        {
            _enabled = value;
            _wake.Set();
        }
    }

    public UsbDirectState State { get; private set; } = UsbDirectState.Off;

    /// <summary>More about State: the error, or the bridge's counters.</summary>
    public string Detail { get; private set; } = "";

    /// <summary>
    /// The user asked to take the phone from adb: stops the adb server (it starts again on its next
    /// use, and finds the phone on the accessory's own adb interface) and switches the phone.
    /// </summary>
    public void TakeFromAdb()
    {
        _stopAdb = true;
        _wake.Set();
    }

    public void Start()
    {
        _thread = new Thread(Loop) { Name = "gp-usb", IsBackground = true };
        _thread.Start();
    }

    private void Loop()
    {
        while (!_stopping)
        {
            try
            {
                Scan();
            }
            catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException or ExternalException)
            {
                Set(UsbDirectState.Failed, $"{e.GetType().Name}: {e.Message}");
            }
            _wake.Wait(1000);
            _wake.Reset();
        }
        CloseBridge();
    }

    private void Scan()
    {
        if (_stopAdb)
        {
            _stopAdb = false;
            StopAdbServer();
            _tried.Clear();
        }
        if (!_enabled)
        {
            CloseBridge();
            Set(UsbDirectState.Off, "");
            return;
        }
        if (_bridge is { } b)
        {
            if (b.Alive)
            {
                Set(UsbDirectState.Connected, $"{b.FromPhone} in, {b.ToPhone} out, {b.WriteFailures} unsent");
                return;
            }
            CloseBridge();
        }

        var nodes = UsbDevices.List();
        var accessory = nodes.FirstOrDefault(n => n.IsAccessory);
        if (accessory != null)
        {
            OpenAccessory(accessory);
            return;
        }
        if (nodes.Any(n => n.InAccessoryMode))
        {
            // Switched, but its accessory interface isn't listed (yet): still enumerating.
            Set(UsbDirectState.Switching, "");
            return;
        }

        bool busy = false, waiting = false, wrongMode = false;
        var now = DateTime.UtcNow;
        foreach (var n in nodes.Where(n => n.IsAdb))
        {
            if (_tried.TryGetValue(n.InstanceId, out var t) && now - t.At < t.Wait)
            {
                waiting |= !t.WrongMode && t.Wait == RetrySwitch;
                wrongMode |= t.WrongMode;
                continue;
            }
            var path = UsbDevices.InterfacePath(n);
            if (path == null) continue;
            using var dev = WinUsbDevice.Open(path, out int err);
            if (dev == null)
            {
                busy |= err is ErrorAccessDenied or ErrorSharingViolation;
                continue;
            }
            if (Aoa.TrySwitch(dev, out string why))
            {
                _tried[n.InstanceId] = (now, RetrySwitch, false);
                _log($"usb: asked {n.Name} to switch to accessory mode");
                Set(UsbDirectState.Switching, n.Name);
                return;
            }
            // Windows hands device requests only from a composite device's first interface to it.
            bool notFirst = n.Interface is { } mi && mi != 0;
            _tried[n.InstanceId] = (now, notFirst ? RetrySwitch : RetryNoAoa, notFirst);
            wrongMode |= notFirst;
            _log($"usb: {n.Name} did not switch: {why}");
        }
        if (waiting) Set(UsbDirectState.Switching, "");
        else if (wrongMode) Set(UsbDirectState.WrongUsbMode, "");
        else if (busy) Set(UsbDirectState.AdbBusy, "");
        else Set(UsbDirectState.NoPhone, "");
    }

    private void StopAdbServer()
    {
        try
        {
            using var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("adb", "kill-server")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            bool done = p?.WaitForExit(5000) ?? false;
            _log($"usb: adb kill-server {(done ? $"exit {p!.ExitCode}" : "did not finish")}");
            Thread.Sleep(500); // let Windows close adb's handle
        }
        catch (Win32Exception e)
        {
            _log($"usb: adb not found on PATH ({e.NativeErrorCode})");
        }
    }

    private void OpenAccessory(UsbNode n)
    {
        var path = UsbDevices.InterfacePath(n);
        if (path == null)
        {
            Set(UsbDirectState.NeedsDriver, n.Name);
            return;
        }
        var dev = WinUsbDevice.Open(path, out int err);
        if (dev == null)
        {
            Set(UsbDirectState.Failed, $"cannot open the accessory (error {err})");
            return;
        }
        if (dev.BulkPipes() is not { } pipes)
        {
            dev.Dispose();
            Set(UsbDirectState.Failed, "the accessory has no bulk endpoints");
            return;
        }
        var bridge = new UsbBridge(new WinUsbPipe(dev, pipes.In, pipes.Out), _serverPort, _log);
        bridge.Start();
        _bridge = bridge;
        _log($"usb: accessory open ({n.Name}), bridged to UDP {_serverPort} from {bridge.LocalEndPoint}");
        Set(UsbDirectState.Connected, "");
    }

    private void CloseBridge()
    {
        var b = _bridge;
        _bridge = null;
        b?.Dispose();
    }

    private void Set(UsbDirectState state, string detail)
    {
        State = state;
        Detail = detail;
        string line = state == UsbDirectState.Connected ? "Connected" : $"{state} {detail}";
        if (line != _lastLogged)
        {
            _lastLogged = line;
            _log($"usb: {line}");
        }
    }

    public void Dispose()
    {
        _stopping = true;
        _wake.Set();
        if (_thread != null && !_thread.Join(3000)) CloseBridge();
    }
}

/// <summary>Android Open Accessory requests (AOA protocol v1).</summary>
internal static class Aoa
{
    private const byte GetProtocol = 51;
    private const byte SendString = 52;
    private const byte StartAccessory = 53;

    public static bool TrySwitch(WinUsbDevice dev, out string why)
    {
        Span<byte> ver = stackalloc byte[2];
        if (!dev.Control(0xC0, GetProtocol, 0, 0, ver, out uint got) || got < 2)
        {
            why = $"no AOA answer (error {Marshal.GetLastPInvokeError()})";
            return false;
        }
        int version = ver[0] | (ver[1] << 8);
        if (version < 1)
        {
            why = "the phone does not support accessory mode";
            return false;
        }
        string[] strings =
        [
            AoaUsbLink.Manufacturer,
            AoaUsbLink.Model,
            "Gamepad, keyboard and mouse for this PC",
            "1",
            "https://github.com/oleroma/GyroPhone",
            Environment.MachineName,
        ];
        for (ushort i = 0; i < strings.Length; i++)
        {
            var bytes = Encoding.UTF8.GetBytes(strings[i] + "\0");
            if (!dev.Control(0x40, SendString, 0, i, bytes, out _))
            {
                why = $"string {i} refused (error {Marshal.GetLastPInvokeError()})";
                return false;
            }
        }
        if (!dev.Control(0x40, StartAccessory, 0, 0, [], out _))
        {
            why = $"start refused (error {Marshal.GetLastPInvokeError()})";
            return false;
        }
        why = "";
        return true;
    }
}

/// <summary>A USB device node (a device, or one interface of a composite device).</summary>
internal sealed record UsbNode(uint DevInst, string InstanceId, string HardwareId, string[] CompatibleIds, string[] InterfaceGuids)
{
    // Instance ids of USB nodes: USB\VID_xxxx&PID_xxxx\<serial> for a device, USB\VID_xxxx&PID_xxxx&MI_xx\... for
    // one interface of a composite device. (Hardware ids vary: an adb interface may be ...&REV_0612&ADB.)
    private static readonly Regex Ids = new(@"^USB\\VID_([0-9A-F]{4})&PID_([0-9A-F]{4})(?:&MI_([0-9A-F]{2}))?\\", RegexOptions.IgnoreCase);

    private Match Parsed => Ids.Match(InstanceId);

    /// <summary>The interface number of a composite device's function (MI_xx), or null for a whole device.</summary>
    public int? Interface => Parsed is { Success: true } m && m.Groups[3].Success ? Convert.ToInt32(m.Groups[3].Value, 16) : null;

    public string Name => HardwareId.StartsWith(@"USB\", StringComparison.OrdinalIgnoreCase) ? HardwareId[4..] : HardwareId;

    /// <summary>Any node of a phone in accessory mode (18D1:2D00 to 2D05): never asked to switch again.</summary>
    public bool InAccessoryMode
    {
        get
        {
            var m = Parsed;
            if (!m.Success || !m.Groups[1].Value.Equals("18D1", StringComparison.OrdinalIgnoreCase)) return false;
            int pid = Convert.ToInt32(m.Groups[2].Value, 16);
            return pid is >= 0x2D00 and <= 0x2D05;
        }
    }

    /// <summary>The accessory interface: 18D1:2D00 itself, or interface 0 of 18D1:2D01 (accessory + adb).</summary>
    public bool IsAccessory
    {
        get
        {
            if (!InAccessoryMode) return false;
            int pid = Convert.ToInt32(Parsed.Groups[2].Value, 16);
            int? mi = Interface;
            return (pid == 0x2D00 && mi == null) || (pid == 0x2D01 && mi == 0);
        }
    }

    /// <summary>An adb interface (class FF, subclass 42, protocol 1) of a phone not in accessory mode.</summary>
    public bool IsAdb =>
        !InAccessoryMode && InterfaceGuids.Length > 0 &&
        CompatibleIds.Any(c => c.Contains("Class_ff&SubClass_42&Prot_01", StringComparison.OrdinalIgnoreCase));
}

internal static unsafe class UsbDevices
{
    /// <summary>The USB device nodes present now.</summary>
    public static List<UsbNode> List()
    {
        var list = new List<UsbNode>();
        nint set = SetupDiGetClassDevsW(null, "USB", 0, DigcfPresent | DigcfAllClasses);
        if (set == InvalidHandle) throw new Win32Exception(Marshal.GetLastPInvokeError());
        try
        {
            var d = new SP_DEVINFO_DATA { cbSize = (uint)sizeof(SP_DEVINFO_DATA) };
            for (uint i = 0; SetupDiEnumDeviceInfo(set, i, ref d); i++)
            {
                var hw = MultiSz(set, ref d, SpdrpHardwareId);
                if (hw.Length == 0) continue;
                list.Add(new UsbNode(d.DevInst, InstanceId(set, ref d), hw[0], MultiSz(set, ref d, SpdrpCompatibleIds), InterfaceGuids(set, ref d)));
            }
        }
        finally
        {
            SetupDiDestroyDeviceInfoList(set);
        }
        return list;
    }

    /// <summary>The path to open the node with WinUSB, or null when it has no driver interface (no WinUSB).</summary>
    public static string? InterfacePath(UsbNode n)
    {
        foreach (var g in n.InterfaceGuids)
        {
            if (!Guid.TryParse(g, out var guid)) continue;
            nint set = SetupDiGetClassDevsW(&guid, null, 0, DigcfPresent | DigcfDeviceInterface);
            if (set == InvalidHandle) continue;
            try
            {
                var ifd = new SP_DEVICE_INTERFACE_DATA { cbSize = (uint)sizeof(SP_DEVICE_INTERFACE_DATA) };
                for (uint i = 0; SetupDiEnumDeviceInterfaces(set, 0, ref guid, i, ref ifd); i++)
                {
                    var dev = new SP_DEVINFO_DATA { cbSize = (uint)sizeof(SP_DEVINFO_DATA) };
                    SetupDiGetDeviceInterfaceDetailW(set, ref ifd, null, 0, out uint size, ref dev);
                    if (size < 8) continue;
                    var buf = new byte[size];
                    fixed (byte* p = buf)
                    {
                        // SP_DEVICE_INTERFACE_DETAIL_DATA_W: DWORD cbSize, then the path; cbSize is 8 on x64, 6 on x86.
                        *(uint*)p = IntPtr.Size == 8 ? 8u : 6u;
                        if (!SetupDiGetDeviceInterfaceDetailW(set, ref ifd, p, size, out _, ref dev)) continue;
                        if (dev.DevInst == n.DevInst) return new string((char*)(p + 4));
                    }
                }
            }
            finally
            {
                SetupDiDestroyDeviceInfoList(set);
            }
        }
        return null;
    }

    private static string InstanceId(nint set, ref SP_DEVINFO_DATA d)
    {
        char* buf = stackalloc char[512];
        return SetupDiGetDeviceInstanceIdW(set, ref d, buf, 512, out _) ? new string(buf) : "";
    }

    private static string[] MultiSz(nint set, ref SP_DEVINFO_DATA d, uint property)
    {
        SetupDiGetDeviceRegistryPropertyW(set, ref d, property, out _, null, 0, out uint size);
        if (size < 2) return [];
        var buf = new byte[size];
        fixed (byte* p = buf)
        {
            if (!SetupDiGetDeviceRegistryPropertyW(set, ref d, property, out _, p, size, out _)) return [];
        }
        return Encoding.Unicode.GetString(buf).Split('\0', StringSplitOptions.RemoveEmptyEntries);
    }

    /// <summary>DeviceInterfaceGUIDs from the device's registry key: set by WinUSB installs (INF, Zadig, MS OS descriptors).</summary>
    private static string[] InterfaceGuids(nint set, ref SP_DEVINFO_DATA d)
    {
        nint h = SetupDiOpenDevRegKey(set, ref d, DicsFlagGlobal, 0, DiregDev, KeyRead);
        if (h == InvalidHandle || h == 0) return [];
        using var key = RegistryKey.FromHandle(new SafeRegistryHandle(h, true));
        return key.GetValue("DeviceInterfaceGUIDs") switch
        {
            string[] many => many,
            string one => [one],
            _ => key.GetValue("DeviceInterfaceGUID") is string single ? [single] : [],
        };
    }
}

/// <summary>One USB interface opened with WinUSB.</summary>
internal sealed unsafe class WinUsbDevice : IDisposable
{
    private readonly SafeFileHandle _file;
    private nint _iface;

    private WinUsbDevice(SafeFileHandle file, nint iface)
    {
        _file = file;
        _iface = iface;
    }

    public nint Interface => _iface;

    /// <summary>Opens path, or returns null with the Win32 error (access denied: another app has it).</summary>
    public static WinUsbDevice? Open(string path, out int error)
    {
        var file = CreateFileW(path, GenericRead | GenericWrite, FileShareRead | FileShareWrite, 0, OpenExisting,
            FileAttributeNormal | FileFlagOverlapped, 0);
        if (file.IsInvalid)
        {
            error = Marshal.GetLastPInvokeError();
            file.Dispose();
            return null;
        }
        if (!WinUsb_Initialize(file, out nint iface))
        {
            error = Marshal.GetLastPInvokeError();
            file.Dispose();
            return null;
        }
        error = 0;
        return new WinUsbDevice(file, iface);
    }

    public bool Control(byte requestType, byte request, ushort value, ushort index, Span<byte> data, out uint transferred)
    {
        var setup = new WINUSB_SETUP_PACKET
        {
            RequestType = requestType,
            Request = request,
            Value = value,
            Index = index,
            Length = (ushort)data.Length,
        };
        fixed (byte* p = data)
        {
            return WinUsb_ControlTransfer(_iface, setup, p, (uint)data.Length, out transferred, 0);
        }
    }

    /// <summary>The first bulk IN and OUT pipes, or null.</summary>
    public (byte In, byte Out)? BulkPipes()
    {
        if (!WinUsb_QueryInterfaceSettings(_iface, 0, out var desc)) return null;
        byte pin = 0, pout = 0;
        for (byte i = 0; i < desc.bNumEndpoints; i++)
        {
            if (!WinUsb_QueryPipe(_iface, 0, i, out var pipe) || pipe.PipeType != UsbdPipeTypeBulk) continue;
            if ((pipe.PipeId & 0x80) != 0) { if (pin == 0) pin = pipe.PipeId; }
            else if (pout == 0) pout = pipe.PipeId;
        }
        return pin != 0 && pout != 0 ? (pin, pout) : null;
    }

    public void Dispose()
    {
        if (_iface != 0)
        {
            WinUsb_Free(_iface);
            _iface = 0;
        }
        _file.Dispose();
    }
}

/// <summary>The accessory's bulk pipes for <see cref="UsbBridge"/>.</summary>
internal sealed unsafe class WinUsbPipe : IUsbPipe
{
    /// <summary>A write the phone does not take within this long is dropped (the app is not reading).</summary>
    private const uint WriteTimeoutMs = 250;

    private readonly WinUsbDevice _dev;
    private readonly byte _in, _out;
    private volatile bool _cancelled;

    public WinUsbPipe(WinUsbDevice dev, byte pipeIn, byte pipeOut)
    {
        _dev = dev;
        _in = pipeIn;
        _out = pipeOut;
        uint ms = WriteTimeoutMs;
        WinUsb_SetPipePolicy(dev.Interface, pipeOut, PipeTransferTimeout, sizeof(uint), ref ms);
    }

    public int Read(Span<byte> buffer)
    {
        while (true)
        {
            if (_cancelled) throw new IOException("closed");
            uint got;
            bool ok;
            fixed (byte* p = buffer)
            {
                ok = WinUsb_ReadPipe(_dev.Interface, _in, p, (uint)buffer.Length, out got, 0);
            }
            if (ok)
            {
                if (got > 0) return (int)got;
                continue; // a zero-length packet
            }
            int err = Marshal.GetLastPInvokeError();
            if (err == ErrorSemTimeout && !_cancelled) continue;
            throw new IOException($"USB read failed (error {err})");
        }
    }

    public bool Write(ReadOnlySpan<byte> data)
    {
        if (_cancelled) return false;
        fixed (byte* p = data)
        {
            return WinUsb_WritePipe(_dev.Interface, _out, p, (uint)data.Length, out uint sent, 0) && sent == data.Length;
        }
    }

    public void Cancel()
    {
        _cancelled = true;
        WinUsb_AbortPipe(_dev.Interface, _in);
        WinUsb_AbortPipe(_dev.Interface, _out);
    }

    public void Dispose() => _dev.Dispose();
}
