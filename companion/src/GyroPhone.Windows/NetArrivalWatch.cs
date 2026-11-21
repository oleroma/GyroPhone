using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;
using System.Security.Principal;

namespace GyroPhone.Windows;

/// <summary>
/// Diagnostics' network arrival watch on the companion's side (docs/spec/pc-companion.md, "Diagnostics", "Network
/// arrival"): while Diagnostics is on, it reads from the FPS helper (or, running as administrator, from
/// <see cref="EtwNetArrivals"/> itself) the moment Windows received each UDP datagram for GyroPhone's port, keeps
/// them in a ring sized like the flight record, and a Diagnostics Save writes them as pc-&lt;time&gt;-net.bin beside
/// the flight record. tools/diag/gpdiag.py pairs the two: both are QueryPerformanceCounter microseconds.
/// <para>Nothing of it runs on the input path: its own thread, its own process for the events.</para>
/// </summary>
public sealed class NetArrivalWatch : IDisposable
{
    public const int Version = 1, RecordBytes = 16;
    /// <summary>Datagrams per minute kept: STATEs plus overlay and other packets, with room.</summary>
    public const int PerMinute = 120_000;
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;

    private readonly Action<string> _log;
    private readonly object _gate = new();
    private long[] _at = [];
    private int[] _size = [];
    private ushort[] _port = [];
    private long _head;
    private CancellationTokenSource? _stop;
    private Task? _run;
    private EtwNetArrivals? _direct;

    public NetArrivalWatch(Action<string> log) => _log = log;

    /// <summary>What a Save says about it: on and through what, or why not.</summary>
    public string State { get; private set; } = "off";

    /// <summary>Diagnostics on or off (UI thread), and the minutes it keeps.</summary>
    public void SetOn(bool on, int minutes)
    {
        int cap = Math.Clamp(minutes, 1, 30) * PerMinute;
        if (on && _at.Length == cap && (_stop != null || _direct != null)) return;
        Stop();
        if (!on)
        {
            lock (_gate)
            {
                _at = [];
                _size = [];
                _port = [];
                _head = 0;
            }
            State = "off";
            return;
        }
        lock (_gate)
        {
            _at = new long[cap];
            _size = new int[cap];
            _port = new ushort[cap];
            _head = 0;
        }
        if (ElevationProbe.IsSelfElevated)
        {
            var etw = new EtwNetArrivals(_log, FrameHelperWire.Port);
            int rc = etw.Start((qpc, size, port) => Add((long)(qpc * UsPerTick), size, port), out string? step);
            if (rc != 0)
            {
                etw.Dispose();
                State = $"not on: {step} failed ({rc})";
                _log($"network watch: {State}");
                return;
            }
            _direct = etw;
            State = "on (the companion runs as administrator)";
            _log("network watch: on, in the companion");
            return;
        }
        if (FpsHelperSetup.Installed() != FrameHelperWire.Version)
        {
            State = "not on: needs the FPS helper set up (Performance, Set up FPS helper)";
            _log($"network watch: {State}");
            return;
        }
        var stop = new CancellationTokenSource();
        _stop = stop;
        State = "starting";
        _run = Task.Run(() => Run(stop.Token));
    }

    private void Add(long atUs, int size, int port)
    {
        lock (_gate)
        {
            if (_at.Length == 0) return;
            int i = (int)(_head % _at.Length);
            _at[i] = atUs;
            _size[i] = size;
            _port[i] = (ushort)port;
            _head++;
        }
    }

    /// <summary>Count kept and the time span, for the Save's text.</summary>
    public long Count
    {
        get { lock (_gate) return Math.Min(_head, _at.Length); }
    }

    /// <summary>
    /// Writes the arrivals after <paramref name="fromUs"/> (QPC µs), oldest first: "GPNETARR", version, entry size, count,
    /// QPC µs and Unix ms now; then per entry i64 arrival µs, i32 bytes, u16 sender's port, u16 0. Null when it isn't on.
    /// </summary>
    public string? Save(string path, long fromUs)
    {
        long[] at;
        int[] size;
        ushort[] port;
        lock (_gate)
        {
            if (_at.Length == 0) return null;
            long tail = Math.Max(0, _head - _at.Length);
            int n = (int)(_head - tail);
            at = new long[n];
            size = new int[n];
            port = new ushort[n];
            for (int k = 0; k < n; k++)
            {
                int i = (int)((tail + k) % _at.Length);
                at[k] = _at[i];
                size[k] = _size[i];
                port[k] = _port[i];
            }
        }
        int first = 0;
        while (first < at.Length && at[first] <= fromUs) first++;
        int count = at.Length - first;
        var buf = new byte[40 + count * RecordBytes];
        "GPNETARR"u8.CopyTo(buf);
        BinaryPrimitives.WriteInt32LittleEndian(buf.AsSpan(8), Version);
        BinaryPrimitives.WriteInt32LittleEndian(buf.AsSpan(12), RecordBytes);
        BinaryPrimitives.WriteInt64LittleEndian(buf.AsSpan(16), count);
        BinaryPrimitives.WriteInt64LittleEndian(buf.AsSpan(24), (long)(Stopwatch.GetTimestamp() * UsPerTick));
        BinaryPrimitives.WriteInt64LittleEndian(buf.AsSpan(32), DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        for (int k = 0; k < count; k++)
        {
            var s = buf.AsSpan(40 + k * RecordBytes, RecordBytes);
            BinaryPrimitives.WriteInt64LittleEndian(s, at[first + k]);
            BinaryPrimitives.WriteInt32LittleEndian(s[8..], size[first + k]);
            BinaryPrimitives.WriteUInt16LittleEndian(s[12..], port[first + k]);
        }
        File.WriteAllBytes(path, buf);
        return path;
    }

    private async Task Run(CancellationToken stop)
    {
        string sid;
        using (var id = WindowsIdentity.GetCurrent()) sid = id.User?.Value ?? "";
        string name = FrameHelperWire.NetPipeName(sid);
        const int size = FrameHelperWire.NetRecordSize;
        var buf = new byte[size * 512];
        string? lastTrouble = null;
        while (!stop.IsCancellationRequested)
        {
            string? trouble = null;
            try
            {
                using var pipe = new NamedPipeClientStream(".", name, PipeDirection.In, PipeOptions.Asynchronous);
                try
                {
                    await pipe.ConnectAsync(2000, stop).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    // The frame watch starts the helper's task when it isn't running; this only waits for it.
                    trouble = "the FPS helper isn't running";
                }
                if (pipe.IsConnected)
                {
                    var hello = new byte[FrameHelperWire.HelloSize];
                    await pipe.ReadExactlyAsync(hello, stop).ConfigureAwait(false);
                    uint magic = BinaryPrimitives.ReadUInt32LittleEndian(hello);
                    int version = BinaryPrimitives.ReadInt32LittleEndian(hello.AsSpan(4));
                    int rc = BinaryPrimitives.ReadInt32LittleEndian(hello.AsSpan(8));
                    if (magic != FrameHelperWire.Magic || version != FrameHelperWire.Version)
                    {
                        State = $"not on: the FPS helper answers version {version}, not {FrameHelperWire.Version} (set it up again)";
                        _log($"network watch: {State}");
                        return;
                    }
                    if (rc != 0)
                    {
                        State = $"not on: the FPS helper can't watch the network ({rc})";
                        _log($"network watch: {State}");
                        return;
                    }
                    State = "on, through the FPS helper";
                    _log("network watch: on, through the FPS helper");
                    lastTrouble = null;
                    int have = 0;
                    while (true)
                    {
                        int n = await pipe.ReadAsync(buf.AsMemory(have), stop).ConfigureAwait(false);
                        if (n == 0) break;
                        have += n;
                        int whole = have / size * size;
                        for (int i = 0; i < whole; i += size)
                        {
                            long qpc = BinaryPrimitives.ReadInt64LittleEndian(buf.AsSpan(i));
                            if (qpc == 0) continue; // heartbeat
                            Add((long)(qpc * UsPerTick), BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(i + 8)),
                                BinaryPrimitives.ReadUInt16LittleEndian(buf.AsSpan(i + 12)));
                        }
                        Buffer.BlockCopy(buf, whole, buf, 0, have - whole);
                        have -= whole;
                    }
                    trouble = "the FPS helper closed the pipe";
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or EndOfStreamException)
            {
                trouble = $"pipe: {e.GetType().Name}: {e.Message}";
            }
            if (trouble != null && trouble != lastTrouble) _log($"network watch: {trouble}");
            if (trouble != null) State = $"not on: {trouble}";
            lastTrouble = trouble;
            try { await Task.Delay(1000, stop).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
        }
    }

    private void Stop()
    {
        _direct?.Dispose();
        _direct = null;
        if (_stop is { } s)
        {
            s.Cancel();
            try { _run?.Wait(2000); }
            catch (AggregateException) { }
            s.Dispose();
            _stop = null;
            _run = null;
        }
    }

    public void Dispose() => Stop();
}
