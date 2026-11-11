using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// Every frame any program shows with Direct3D 9 to 12, from Windows' own event tracing (ETW), the way
/// PresentMon and the Xbox Game Bar read it: a real-time session gets the DXGI and Direct3D 9 "present"
/// events, which carry the process and a precise timestamp. It only listens: nothing touches the game.
/// Starting a session needs admin rights or the "Performance Log Users" group (<see cref="PerfPermission"/>).
/// </summary>
public sealed unsafe class FrameWatch : IFrameSource
{
    private const string SessionName = "GyroPhone frame watch";
    private static readonly Guid Dxgi = new("CA11C036-0102-4A2D-A6AD-F03CFED5D3C9");
    private static readonly Guid D3D9 = new("783ACA0A-790E-4D7F-8451-AA850511C6B9");
    private const ushort DxgiPresentStart = 42, DxgiPresentMpoStart = 55, D3D9PresentStart = 1;
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;

    // The one session's callback target (ETW calls a static function).
    private static volatile FrameWatch? _current;

    private readonly Action<string> _log;
    private readonly int _self = Environment.ProcessId;
    private Action<int, long>? _present;
    private ulong _session, _trace = InvalidTrace;
    private Thread? _thread;
    private IntPtr _props;
    private const ulong InvalidTrace = ulong.MaxValue;

    public FrameWatch(Action<string> log) => _log = log;

    public FrameWatchState Start(Action<int, long> present, out string? error)
    {
        error = null;
        Stop();
        _present = present;
        _props = Properties();
        int rc = StartTraceW(out _session, SessionName, _props);
        if (rc == ErrorAlreadyExists)
        {
            // Left over from a companion that didn't stop it: stop it and start ours.
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
            if (rc == ErrorAccessDenied) return FrameWatchState.NeedsPermission;
            error = new Win32Exception(rc).Message;
            return FrameWatchState.Failed;
        }
        foreach (var g in new[] { Dxgi, D3D9 })
        {
            var guid = g;
            int e = EnableTraceEx2(_session, ref guid, EnableProvider, LevelInformation, ulong.MaxValue, 0, 0, IntPtr.Zero);
            if (e != 0) _log($"frame watch: provider {g} not enabled ({e})");
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
            int e = Marshal.GetLastWin32Error();
            Stop();
            if (e == ErrorAccessDenied) return FrameWatchState.NeedsPermission;
            error = $"OpenTrace: {new Win32Exception(e).Message}";
            return FrameWatchState.Failed;
        }
        ulong trace = _trace;
        _thread = new Thread(() =>
        {
            var handles = new[] { trace };
            int r = ProcessTrace(handles, 1, IntPtr.Zero, IntPtr.Zero);
            if (r != 0 && r != ErrorCancelled) _log($"frame watch: ProcessTrace ended ({r})");
        })
        { IsBackground = true, Name = "gp-frames" };
        _thread.Start();
        return FrameWatchState.On;
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
        _present = null;
    }

    public void Dispose() => Stop();

    [UnmanagedCallersOnly]
    private static void OnEvent(IntPtr record)
    {
        try
        {
            var me = _current;
            if (me?._present is not { } present) return;
            var provider = *(Guid*)(record + RecordProviderId);
            ushort id = *(ushort*)(record + RecordEventId);
            bool frame = (provider == Dxgi && id is DxgiPresentStart or DxgiPresentMpoStart) || (provider == D3D9 && id == D3D9PresentStart);
            if (!frame) return;
            int pid = *(int*)(record + RecordProcessId);
            if (pid == me._self) return;
            long qpc = *(long*)(record + RecordTimeStamp);
            present(pid, (long)(qpc * UsPerTick));
        }
        catch
        {
            // Never let an exception cross back into ETW.
        }
    }

    /// <summary>EVENT_TRACE_PROPERTIES with room for the session name: real time, QPC timestamps, flushed every 100 ms.</summary>
    private static IntPtr Properties()
    {
        int size = PropertiesSize + 2 * 260;
        var p = Marshal.AllocHGlobal(size);
        new Span<byte>((void*)p, size).Clear();
        Marshal.WriteInt32(p, 0, size); // Wnode.BufferSize
        Marshal.WriteInt32(p, 40, 1); // Wnode.ClientContext: QueryPerformanceCounter
        Marshal.WriteInt32(p, 44, WnodeFlagTracedGuid); // Wnode.Flags
        Marshal.WriteInt32(p, 48, 64); // BufferSize, KB
        Marshal.WriteInt32(p, 64, EventTraceRealTimeMode | EventTraceUseMsFlushTimer); // LogFileMode
        Marshal.WriteInt32(p, 68, 100); // FlushTimer, ms (the frames come in small batches, not once a second)
        Marshal.WriteInt32(p, 116, PropertiesSize); // LoggerNameOffset
        return p;
    }

    // Layouts (64-bit).
    private const int PropertiesSize = 120;
    private const int LogfileSize = 448, LogfileLoggerName = 8, LogfileProcessTraceMode = 28, LogfileEventRecordCallback = 424;
    private const int RecordProcessId = 12, RecordTimeStamp = 16, RecordProviderId = 24, RecordEventId = 40;

    private const int WnodeFlagTracedGuid = 0x00020000, EventTraceRealTimeMode = 0x00000100, EventTraceUseMsFlushTimer = 0x00000010;
    private const int ModeRealTime = 0x00000100, ModeRawTimestamp = 0x00001000, ModeEventRecord = 0x10000000;
    private const uint ControlStop = 1, EnableProvider = 1;
    private const byte LevelInformation = 4;
    private const int ErrorAccessDenied = 5, ErrorAlreadyExists = 183, ErrorCancelled = 1223;

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
