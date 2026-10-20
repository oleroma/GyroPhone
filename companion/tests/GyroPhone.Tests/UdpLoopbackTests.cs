using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using Xunit.Abstractions;

namespace GyroPhone.Tests;

/// <summary>End to end over a real UDP socket on loopback, with a C# fake phone.</summary>
public sealed class UdpLoopbackTests : IDisposable
{
    private readonly ITestOutputHelper _out;
    private readonly GatedKeyMouse _km = new();
    private readonly FakePad _pad = new();
    private readonly AutoAllowPrompt _prompt = new();
    private readonly HostEngine _engine;
    private readonly UdpHostServer _server;
    private readonly Socket _phoneSock;
    private readonly IPEndPoint _host;
    private readonly SlowClock _clock = new();

    public UdpLoopbackTests(ITestOutputHelper output)
    {
        _out = output;
        var settings = new HostSettings { HostId = Hex.Range(0x10, 16), HostName = "LOOPBACK-PC" };
        _engine = new HostEngine(settings, _pad, _km, _prompt, new PlainProtector(), _clock);
        _prompt.Engine = _engine;
        _engine.Start(_clock.NowUs);
        _server = new UdpHostServer(_engine, _clock, new IPEndPoint(IPAddress.Loopback, 0));
        Assert.True(_server.Start());
        _host = new IPEndPoint(IPAddress.Loopback, _server.LocalPort);
        _phoneSock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { ReceiveTimeout = 250 };
        _phoneSock.Bind(new IPEndPoint(IPAddress.Loopback, 0));
    }

    private void Tx(byte[] d) => _phoneSock.SendTo(d, _host);

    private byte[]? Rx(Func<byte[], bool>? accept = null, int timeoutMs = 2000)
    {
        var buf = new byte[2048];
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            try
            {
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n = _phoneSock.ReceiveFrom(buf, ref from);
                var d = buf[..n];
                if (accept == null || accept(d)) return d;
            }
            catch (SocketException e) when (e.SocketErrorCode == SocketError.TimedOut)
            {
            }
        }
        return null;
    }

    /// <summary>Sends until a matching reply arrives, like the phone's retransmit loops.</summary>
    private byte[] Exchange(Func<byte[]> request, Func<byte[], bool> accept)
    {
        for (int i = 0; i < 40; i++)
        {
            Tx(request());
            var r = Rx(accept, 250);
            if (r != null) return r;
        }
        throw new Xunit.Sdk.XunitException("no reply");
    }

    private static bool WaitFor(Func<bool> cond, int timeoutMs = 5000)
    {
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            if (cond()) return true;
            Thread.Sleep(5);
        }
        return cond();
    }

    private FakePhone DiscoverPairAndConnect()
    {
        var phone = new FakePhone(Hex.Range(0x00, 16), "OnePlus 15");
        var ann = Exchange(() => phone.Discover(99), d => d[3] == PacketType.Announce);
        Assert.True(WireCodec.TryDecodeAnnounce(ann, out var a));
        Assert.Equal("LOOPBACK-PC", a.Name);
        Assert.NotEqual(0, a.Flags & AnnounceFlags.PairingOpen);

        var start = phone.PairStart();
        Assert.True(phone.OnPairHost(Exchange(() => start, d => d[3] == PacketType.PairHost)));
        var reveal = phone.PairReveal();
        var ack = Exchange(() => reveal, d => d[3] == PacketType.PairAck);
        Assert.Equal(PairAckStatus.Ok, ack[16]);
        var confirm = phone.PairConfirm();
        var done = Exchange(() => confirm, d => d[3] == PacketType.PairDone && d[16] != PairDoneStatus.Pending);
        Assert.True(phone.OnPairDone(done, out _));

        var hello = phone.Hello();
        Assert.True(phone.OnWelcome(Exchange(() => hello, d => d[3] == PacketType.Welcome)));
        Tx(phone.State());
        Assert.True(WaitFor(() => _engine.Snapshot().Connected));
        return phone;
    }

    [Fact]
    public void DiscoverPairHelloAndLossyStream()
    {
        var phone = DiscoverPairAndConnect();
        var rnd = new Random(1234);
        var packets = new List<byte[]>();
        var sendTimes = new List<long>();
        for (int i = 0; i < 1000; i++)
        {
            packets.Add(phone.State(f =>
            {
                f.MouseTotalX += rnd.Next(-40, 41);
                f.MouseTotalY += rnd.Next(-40, 41);
                if (i % 50 == 0) f.WheelTotal += 1;
                f.SetKey(0x04 + rnd.Next(0, 8), rnd.Next(2) == 0);
                f.MouseButtons = (byte)rnd.Next(0, 32);
                f.Lx = (short)rnd.Next(-32767, 32768);
            }));
        }
        // Final neutral STATE: nothing held, totals unchanged.
        packets.Add(phone.State(f => { f.ClearInputs(); }));

        int delivered = 0;
        for (int i = 0; i < packets.Count; i++)
        {
            bool last = i == packets.Count - 1;
            if (!last && rnd.NextDouble() < 0.10) continue; // 10 % loss
            if (!last && i + 1 < packets.Count - 1 && rnd.NextDouble() < 0.05)
            {
                Tx(packets[i + 1]); // reorder: the older one arrives late and is dropped
                Tx(packets[i]);
                delivered += 2;
                i++;
                continue;
            }
            Tx(packets[i]);
            delivered++;
            if (i % 100 == 0) Thread.Sleep(1); // keep well inside the socket buffer
        }

        // Like the phone's 20 ms keepalive: if the socket buffer overflowed and the final STATE was
        // lost, the next keepalive (same state, new seq) repairs it.
        var keepalive = Stopwatch.StartNew();
        bool done = WaitFor(() =>
        {
            if (keepalive.ElapsedMilliseconds >= 20)
            {
                Tx(phone.State());
                keepalive.Restart();
            }
            return _km.Snapshot(k => k.MouseX == phone.Frame.MouseTotalX && k.MouseY == phone.Frame.MouseTotalY && k.NothingHeld);
        });
        var (mx, my, wheel, nothingHeld) = _km.Snapshot(k => (k.MouseX, k.MouseY, k.Wheel, k.NothingHeld));
        _out.WriteLine($"delivered {delivered}, states {_engine.Snapshot().Counters.States}, injections {_km.Calls}");
        Assert.True(done, $"mouse ({mx},{my}) expected ({phone.Frame.MouseTotalX},{phone.Frame.MouseTotalY}), nothingHeld={nothingHeld}");
        Assert.Equal(phone.Frame.WheelTotal * InjectEvent.WheelDelta, wheel);
        Assert.Equal(0, _server.RxExceptions);
    }

    [Fact]
    public void BacklogOfHundredStatesGivesOneInjection()
    {
        var phone = DiscoverPairAndConnect();
        _km.Arm(); // the next Inject blocks the rx thread
        Tx(phone.State(f => f.SetKey(0x1A, true)));
        Assert.True(_km.WaitBlocked());
        int callsBefore = _km.Calls;
        for (int i = 0; i < 100; i++) Tx(phone.State(f => f.MouseTotalX += 1));
        Thread.Sleep(50);
        _km.Release();
        Assert.True(WaitFor(() => _km.Snapshot(k => k.MouseX) == 100));
        Thread.Sleep(50);
        Assert.Equal(callsBefore + 1, _km.Calls);

        // Informational only: send-to-inject time on loopback.
        var samples = new List<long>();
        long last = 0;
        _engine.LatencySampleHook = s => last = s.InjectUs;
        for (int i = 0; i < 200; i++)
        {
            long t0 = _clock.NowUs;
            Tx(phone.State(f => f.MouseTotalX += 1));
            if (WaitFor(() => Volatile.Read(ref last) >= t0, 200)) samples.Add((last - t0) * SlowClock.Scale);
        }
        if (samples.Count > 0)
        {
            samples.Sort();
            _out.WriteLine($"loopback send->inject p50 {samples[samples.Count / 2]} us, p99 {samples[(int)(samples.Count * 0.99)]} us (informational)");
        }
    }

    [Fact]
    public void SecondServerOnSamePortReportsPortInUse()
    {
        var other = new UdpHostServer(_engine, _clock, _host);
        bool started = other.Start();
        // Windows refuses the second bind (ExclusiveAddressUse); Linux may allow it.
        if (OperatingSystem.IsWindows()) Assert.False(started);
        if (!started) Assert.Equal(ServerState.PortInUse, other.State);
        other.Dispose();
    }

    public void Dispose()
    {
        _km.Release();
        _server.Dispose();
        _phoneSock.Dispose();
    }

    /// <summary>
    /// Host clock running 20x slower than real time, so a scheduler stall on a loaded machine
    /// (parallel test classes, CI) can never reach the 250 ms rebase gap and skew the mouse sum.
    /// </summary>
    private sealed class SlowClock : IClock
    {
        public const long Scale = 20;

        public long NowUs => StopwatchClock.Instance.NowUs / Scale;
    }

    private sealed class AutoAllowPrompt : IPairPrompt
    {
        public HostEngine? Engine;

        // Like the tray: answer later, from another thread.
        public void Show(uint attemptId, string phoneName, string sasDisplay) =>
            Task.Run(() => Engine!.AnswerPair(attemptId, true));

        public void Close(uint attemptId) { }
    }

    /// <summary>Thread-safe FakeKeyMouse that can block the rx thread inside Inject.</summary>
    private sealed class GatedKeyMouse : IKeyMouseSink
    {
        private readonly object _lock = new();
        private readonly FakeKeyMouse _inner = new();
        private readonly ManualResetEventSlim _gate = new(true);
        private readonly ManualResetEventSlim _blocked = new(false);
        private volatile bool _armed;

        public int Calls
        {
            get { lock (_lock) return _inner.Calls.Count; }
        }

        public T Snapshot<T>(Func<FakeKeyMouse, T> f)
        {
            lock (_lock) return f(_inner);
        }

        public void Arm()
        {
            _gate.Reset();
            _blocked.Reset();
            _armed = true;
        }

        public bool WaitBlocked() => _blocked.Wait(5000);

        public void Release() => _gate.Set();

        public void Inject(ReadOnlySpan<InjectEvent> events)
        {
            lock (_lock) _inner.Inject(events);
            if (_armed)
            {
                _armed = false;
                _blocked.Set();
                _gate.Wait(5000);
            }
        }
    }
}
