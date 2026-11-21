using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Diagnostics' network arrival watch (docs/spec/pc-companion.md, "Diagnostics", "Network arrival"): the moment
/// Windows' network stack received each UDP datagram for GyroPhone's port, from Windows' own event tracing
/// (the Microsoft-Windows-Kernel-Network provider's "UDP received" events, with a QueryPerformanceCounter
/// timestamp, the clock both input programs stamp their packets with). Paired with the flight record, it splits
/// a late packet into "came late to the PC" and "waited in the PC before the input program took it".
/// <para>
/// It only listens, and only while Diagnostics is on. Every UDP datagram the PC receives then logs one small
/// event (well under a microsecond in the kernel), so it stays off otherwise. Starting it needs admin rights,
/// like <see cref="EtwPresents"/>: the FPS helper runs it. No dependencies: the helper compiles this file too.
/// </para>
/// </summary>
public sealed unsafe class EtwNetArrivals : IDisposable
{
    private static readonly string SessionName = "GyroPhone network watch " + UserSid();
    private static readonly Guid KernelNetwork = new("7DD42A49-5329-4832-8DFD-43D979153A88");
    /// <summary>UDPv4 and UDPv6 "data received".</summary>
    private const ushort UdpRecv4 = 43, UdpRecv6 = 59;
    /// <summary>KERNEL_NETWORK_KEYWORD_UDPIP: the keyword when the event filter can't be set.</summary>
    private const ulong KeywordUdp = 0x80;

    private static volatile EtwNetArrivals? _current;

    private readonly Action<string> _log;
    private readonly ushort _portNet, _portHost;
    private Action<long, int, int>? _arrived;
    private ulong _session, _trace = InvalidTrace;
    private Thread? _thread;
    private IntPtr _props;
    private const ulong InvalidTrace = ulong.MaxValue;

    public EtwNetArrivals(Action<string> log, int port)
    {
        _log = log;
        _portHost = (ushort)port;
        _portNet = (ushort)((port >> 8 & 0xFF) | (port & 0xFF) << 8);
    }

    /// <summary>
    /// Starts calling <paramref name="arrived"/>(QueryPerformanceCounter ticks, datagram bytes, sender's port) from its
    /// own thread for every UDP datagram to the port. 0, or the Windows error with <paramref name="step"/> naming the call.
    /// </summary>
    public int Start(Action<long, int, int> arrived, out string? step)
    {
        step = null;
        Stop();
        _arrived = arrived;
        _props = Properties();
        int rc = StartTraceW(out _session, SessionName, _props);
        if (rc == ErrorAlreadyExists)
        {
            ControlTraceW(0, SessionName, _props, ControlStop);
            Marshal.FreeHGlobal(_props);
            _props = Properties();
            rc = StartTraceW(out _session, SessionName, _props);
        }
        if (rc != 0)
        {
            Marshal.FreeHGlobal(_props);
            _props = IntPtr.Zero;
            _session = 0;
            _arrived = null;
            step = "StartTrace";
            return rc;
        }
        int e = EnableOnlyUdpReceive();
        if (e != 0)
        {
            // Event id filters need Windows 8.1: fall back to the UDP keyword (the callback checks the id anyway).
            var guid = KernelNetwork;
            e = EnableTraceEx2(_session, ref guid, EnableProvider, LevelInformation, KeywordUdp, 0, 0, IntPtr.Zero);
        }
        if (e != 0)
        {
            Stop();
            step = "EnableTrace";
            return e;
        }

        _current = this;
        var name = Marshal.StringToHGlobalUni(SessionName);
        var logfile = Marshal.AllocHGlobal(LogfileSize);
        new Span<byte>((void*)logfile, LogfileSize).Clear();
        Marshal.WriteIntPtr(logfile, LogfileLoggerName, name);
        Marshal.WriteInt32(logfile, LogfileProcessTraceMode, ModeRealTime | ModeEventRecord | ModeRawTimestamp);
        delegate* unmanaged<IntPtr, void> cb = &OnEvent;
        Marshal.WriteIntPtr(logfile, LogfileEventRecordCallback, (IntPtr)cb);
        _trace = OpenTraceW(logfile);
        Marshal.FreeHGlobal(name);
        Marshal.FreeHGlobal(logfile);
        if (_trace == InvalidTrace)
        {
            int le = Marshal.GetLastWin32Error();
            Stop();
            step = "OpenTrace";
            return le == 0 ? -1 : le;
        }
        ulong trace = _trace;
        _thread = new Thread(() =>
        {
            var handles = new[] { trace };
            int r = ProcessTrace(handles, 1, IntPtr.Zero, IntPtr.Zero);
            if (r != 0 && r != ErrorCancelled) _log($"network watch: ProcessTrace ended ({r})");
        })
        { IsBackground = true, Name = "gp-net-arrivals" };
        _thread.Start();
        return 0;
    }

    /// <summary>Enables the provider for its two "UDP received" events only (an event id filter), so nothing else is logged.</summary>
    private int EnableOnlyUdpReceive()
    {
        // EVENT_FILTER_EVENT_ID: FilterIn, Reserved, Count, then the ids.
        const int idsSize = 4 + 2 * 2;
        var ids = Marshal.AllocHGlobal(idsSize);
        // EVENT_FILTER_DESCRIPTOR: Ptr, Size, Type.
        var desc = Marshal.AllocHGlobal(16);
        // ENABLE_TRACE_PARAMETERS (version 2): Version, EnableProperty, ControlFlags, SourceId, EnableFilterDesc, FilterDescCount.
        var p = Marshal.AllocHGlobal(48);
        try
        {
            new Span<byte>((void*)ids, idsSize).Clear();
            *(byte*)ids = 1;
            *(ushort*)(ids + 2) = 2;
            *(ushort*)(ids + 4) = UdpRecv4;
            *(ushort*)(ids + 6) = UdpRecv6;
            *(long*)desc = (long)ids;
            *(int*)(desc + 8) = idsSize;
            *(uint*)(desc + 12) = EventFilterTypeEventId;
            new Span<byte>((void*)p, 48).Clear();
            *(int*)p = 2;
            *(IntPtr*)(p + 32) = desc;
            *(int*)(p + 40) = 1;
            var guid = KernelNetwork;
            return EnableTraceEx2(_session, ref guid, EnableProvider, LevelInformation, ulong.MaxValue, 0, 0, p);
        }
        finally
        {
            Marshal.FreeHGlobal(p);
            Marshal.FreeHGlobal(desc);
            Marshal.FreeHGlobal(ids);
        }
    }

    public void Stop()
    {
        if (_session != 0 || _props != IntPtr.Zero)
        {
            ControlTraceW(_session, null, _props, ControlStop);
            _session = 0;
        }
        if (_trace != InvalidTrace)
        {
            CloseTrace(_trace);
            _trace = InvalidTrace;
        }
        _thread?.Join(2000);
        _thread = null;
        if (_props != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(_props);
            _props = IntPtr.Zero;
        }
        if (ReferenceEquals(_current, this)) _current = null;
        _arrived = null;
    }

    public void Dispose() => Stop();

    private static string UserSid()
    {
        using var id = System.Security.Principal.WindowsIdentity.GetCurrent();
        return id.User?.Value ?? "";
    }

    [UnmanagedCallersOnly]
    private static void OnEvent(IntPtr record)
    {
        try
        {
            var me = _current;
            if (me?._arrived is not { } arrived) return;
            if (*(Guid*)(record + RecordProviderId) != KernelNetwork) return;
            ushort id = *(ushort*)(record + RecordEventId);
            // PID u32, size u32, daddr, saddr, dport u16, sport u16 (ports in network byte order).
            int portAt = id switch { UdpRecv4 => 16, UdpRecv6 => 40, _ => -1 };
            if (portAt < 0) return;
            int length = *(ushort*)(record + RecordUserDataLength);
            if (length < portAt + 4) return;
            var data = *(byte**)(record + RecordUserData);
            ushort dport = *(ushort*)(data + portAt);
            if (dport != me._portNet && dport != me._portHost) return;
            ushort sport = *(ushort*)(data + portAt + 2);
            int sender = dport == me._portNet ? (sport >> 8 & 0xFF) | (sport & 0xFF) << 8 : sport;
            arrived(*(long*)(record + RecordTimeStamp), *(int*)(data + 4), sender);
        }
        catch
        {
            // Never let an exception cross back into ETW.
        }
    }

    private static IntPtr Properties()
    {
        int size = PropertiesSize + 2 * 260;
        var p = Marshal.AllocHGlobal(size);
        new Span<byte>((void*)p, size).Clear();
        Marshal.WriteInt32(p, 0, size);
        Marshal.WriteInt32(p, 40, 1); // QueryPerformanceCounter timestamps
        Marshal.WriteInt32(p, 44, WnodeFlagTracedGuid);
        Marshal.WriteInt32(p, 48, 64);
        // Room for a burst of a thousand packets a second for a while without losing events.
        Marshal.WriteInt32(p, 52, 16); // MinimumBuffers
        Marshal.WriteInt32(p, 64, EventTraceRealTimeMode | EventTraceUseMsFlushTimer);
        Marshal.WriteInt32(p, 68, 100);
        Marshal.WriteInt32(p, 116, PropertiesSize);
        return p;
    }

    // Layouts (64-bit).
    private const int PropertiesSize = 120;
    private const int LogfileSize = 448, LogfileLoggerName = 8, LogfileProcessTraceMode = 28, LogfileEventRecordCallback = 424;
    private const int RecordTimeStamp = 16, RecordProviderId = 24, RecordEventId = 40, RecordUserDataLength = 86, RecordUserData = 96;

    private const int WnodeFlagTracedGuid = 0x00020000, EventTraceRealTimeMode = 0x00000100, EventTraceUseMsFlushTimer = 0x00000010;
    private const int ModeRealTime = 0x00000100, ModeRawTimestamp = 0x00001000, ModeEventRecord = 0x10000000;
    private const uint ControlStop = 1, EnableProvider = 1, EventFilterTypeEventId = 0x80000200;
    private const byte LevelInformation = 4;
    private const int ErrorAlreadyExists = 183, ErrorCancelled = 1223;

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode)]
    private static extern int StartTraceW(out ulong handle, string name, IntPtr properties);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode)]
    private static extern int ControlTraceW(ulong handle, string? name, IntPtr properties, uint control);

    [DllImport("advapi32.dll")]
    private static extern int EnableTraceEx2(ulong handle, ref Guid provider, uint control, byte level, ulong matchAny, ulong matchAll, uint timeout, IntPtr parameters);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern ulong OpenTraceW(IntPtr logfile);

    [DllImport("advapi32.dll")]
    private static extern int ProcessTrace(ulong[] handles, uint count, IntPtr start, IntPtr end);

    [DllImport("advapi32.dll")]
    private static extern int CloseTrace(ulong handle);
}
