using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace GyroPhone.LatencyTest;

/// <summary>
/// Tells GyroPhone's input driver mouse apart from the PC's real mice in raw input, looked up once per device
/// handle (a copy of the companion's OwnInputDevice): a device is ours when its name carries the driver's ids or
/// it carries the driver's own container id.
/// </summary>
internal static class OwnMouse
{
    private const uint RidiDeviceName = 0x20000007;
    /// <summary>The input driver's hardware ids (HidInputSink.DeviceIds in the companion).</summary>
    private const string DeviceIds = "VID_1209&PID_6770";
    /// <summary>The input driver's own container id (GpiContainerId in input-driver/src/driver.c).</summary>
    private static readonly Guid ContainerId = new("b7d1e5a2-3c84-4f19-a6e0-5d2c8b91f473");
    private static readonly ConcurrentDictionary<IntPtr, bool> Known = new();

    public static bool Is(IntPtr device)
    {
        if (device == IntPtr.Zero) return false;
        if (Known.TryGetValue(device, out bool own)) return own;
        string? name = Name(device);
        own = name != null && (name.Contains(DeviceIds, StringComparison.OrdinalIgnoreCase) || InOurContainer(name));
        Known[device] = own;
        return own;
    }

    private static bool InOurContainer(string rawName)
    {
        string s = rawName.TrimEnd('\0');
        if (s.StartsWith(@"\\?\", StringComparison.Ordinal) || s.StartsWith(@"\\.\", StringComparison.Ordinal)) s = s[4..];
        int cls = s.LastIndexOf("#{", StringComparison.Ordinal);
        if (cls > 0) s = s[..cls];
        if (s.Length == 0 || !s.Contains('#')) return false;
        string instance = s.Replace('#', '\\');
        try
        {
            if (CM_Locate_DevNodeW(out uint node, instance, 0) != 0) return false;
            for (int i = 0; i < 3; i++)
            {
                if (ContainerOf(node) == ContainerId) return true;
                if (CM_Get_Parent(out uint parent, node, 0) != 0) return false;
                node = parent;
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
        return false;
    }

    private static Guid? ContainerOf(uint node)
    {
        const uint DevpropTypeGuid = 0x0D;
        var key = new DevPropKey { Fmtid = new Guid("8c7ed206-3f8a-4827-b3ab-ae9e1faefc6c"), Pid = 2 }; // DEVPKEY_Device_ContainerId
        var buf = new byte[16];
        uint size = 16;
        if (CM_Get_DevNode_PropertyW(node, ref key, out uint type, buf, ref size, 0) != 0 || type != DevpropTypeGuid || size != 16) return null;
        return new Guid(buf);
    }

    private static string? Name(IntPtr device)
    {
        uint size = 0;
        if (GetRawInputDeviceInfoW(device, RidiDeviceName, null, ref size) != 0 || size == 0 || size > 1024) return null;
        var buf = new char[size];
        if (GetRawInputDeviceInfoW(device, RidiDeviceName, buf, ref size) == unchecked((uint)-1)) return null;
        return new string(buf).TrimEnd('\0');
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DevPropKey
    {
        public Guid Fmtid;
        public uint Pid;
    }

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Locate_DevNodeW(out uint devInst, string deviceId, uint flags);

    [DllImport("cfgmgr32.dll")]
    private static extern uint CM_Get_Parent(out uint parent, uint devInst, uint flags);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Get_DevNode_PropertyW(uint devInst, ref DevPropKey key, out uint type, byte[] buffer, ref uint size, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint GetRawInputDeviceInfoW(IntPtr device, uint command, char[]? data, ref uint size);
}
