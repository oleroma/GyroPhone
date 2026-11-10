using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Calls back when any device interface arrives or is removed (CM_Register_Notification, no window
/// needed), so the direct USB link reacts to a plugged cable or a phone coming back in accessory mode at
/// once instead of at its next once-a-second scan. The callback runs on a system thread: keep it short.
/// </summary>
public sealed class DeviceChangeNotifier : IDisposable
{
    private readonly Action _changed;
    // Kept alive for as long as the registration: native code holds a pointer to it.
    private readonly Callback _callback;
    private IntPtr _handle;

    private DeviceChangeNotifier(Action changed)
    {
        _changed = changed;
        _callback = OnNotify;
    }

    /// <summary>Starts the notifications, or returns null (logging why) where they can't be had.</summary>
    public static DeviceChangeNotifier? TryStart(Action changed, Action<string> log)
    {
        var n = new DeviceChangeNotifier(changed);
        try
        {
            var filter = new CmNotifyFilter
            {
                Size = (uint)Marshal.SizeOf<CmNotifyFilter>(),
                Flags = FilterFlagAllInterfaceClasses,
                FilterType = FilterTypeDeviceInterface,
            };
            uint cr = CM_Register_Notification(ref filter, IntPtr.Zero, n._callback, out n._handle);
            if (cr == 0) return n;
            log($"usb: no device notifications (CONFIGRET {cr}); scanning every second");
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            log("usb: no device notifications on this Windows; scanning every second");
        }
        return null;
    }

    private uint OnNotify(IntPtr notify, IntPtr context, int action, IntPtr data, uint size)
    {
        if (action is ActionInterfaceArrival or ActionInterfaceRemoval)
        {
            try
            {
                _changed();
            }
            catch (Exception)
            {
                // never let an exception unwind into the system's thread
            }
        }
        return 0;
    }

    /// <summary>Stops the notifications; waits for a callback in progress.</summary>
    public void Dispose()
    {
        if (_handle != IntPtr.Zero) CM_Unregister_Notification(_handle);
        _handle = IntPtr.Zero;
    }

    private const uint FilterFlagAllInterfaceClasses = 0x1;
    private const int FilterTypeDeviceInterface = 0;
    private const int ActionInterfaceArrival = 0;
    private const int ActionInterfaceRemoval = 1;

    /// <summary>CM_NOTIFY_FILTER: a 16-byte header, then a union whose largest member is 200 WCHARs.</summary>
    [StructLayout(LayoutKind.Explicit, Size = 416)]
    private struct CmNotifyFilter
    {
        [FieldOffset(0)] public uint Size;
        [FieldOffset(4)] public uint Flags;
        [FieldOffset(8)] public int FilterType;
        [FieldOffset(12)] public uint Reserved;
        [FieldOffset(16)] public Guid ClassGuid;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate uint Callback(IntPtr notify, IntPtr context, int action, IntPtr data, uint size);

    [DllImport("cfgmgr32.dll")]
    private static extern uint CM_Register_Notification(ref CmNotifyFilter filter, IntPtr context, Callback callback, out IntPtr notify);

    [DllImport("cfgmgr32.dll")]
    private static extern uint CM_Unregister_Notification(IntPtr notify);
}
