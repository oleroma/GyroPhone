using System.Collections.Concurrent;
using System.Net;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public sealed class UsbFramingTests
{
    private static byte[] Frame(byte[] d)
    {
        var b = new byte[UsbFraming.MaxSize(d.Length)];
        return b[..UsbFraming.Encode(d, b)];
    }

    private static List<byte[]> Deframe(byte[] stream, int chunk)
    {
        var d = new UsbDeframer();
        var got = new List<byte[]>();
        for (int i = 0; i < stream.Length; i += chunk)
            d.Feed(stream.AsSpan(i, Math.Min(chunk, stream.Length - i)), f => got.Add(f.ToArray()));
        return got;
    }

    [Fact]
    public void RoundTripsInAnyChunking()
    {
        var a = Enumerable.Range(0, Wire.MaxDatagram).Select(i => (byte)(i ^ 0x47)).ToArray();
        var b = new byte[] { 1, 2, 3 };
        var stream = Frame(a).Concat(Frame(b)).Concat(Frame(a)).ToArray();
        foreach (int chunk in new[] { 1, 7, 64, stream.Length })
        {
            var got = Deframe(stream, chunk);
            Assert.Equal(3, got.Count);
            Assert.Equal(a, got[0]);
            Assert.Equal(b, got[1]);
            Assert.Equal(a, got[2]);
        }
    }

    [Fact]
    public void PadsFramesThatFillWholePackets()
    {
        Assert.Equal(65, Frame(new byte[61]).Length);
        Assert.Equal(63, Frame(new byte[60]).Length);
    }

    [Fact]
    public void SkipsGarbageAndBadLengths()
    {
        var junk = new byte[] { 1, 2, UsbFraming.Magic, 0, 0, UsbFraming.Magic, 0xFF, 0x7F };
        var got = Deframe(junk.Concat(Frame([9, 8, 7])).ToArray(), 64);
        Assert.Single(got);
        Assert.Equal(new byte[] { 9, 8, 7 }, got[0]);
    }

    [Fact]
    public void MatchesThePhoneByteForByte()
    {
        // Same vector as the phone's UsbFramesTest: 0x47, length LE, bytes.
        Assert.Equal(new byte[] { 0x47, 3, 0, 9, 8, 7 }, Frame([9, 8, 7]));
    }
}

/// <summary>The direct USB bridge with a fake cable in front of the real UDP server.</summary>
public sealed class UsbBridgeTests : IDisposable
{
    private sealed class FakePipe : IUsbPipe
    {
        public readonly BlockingCollection<byte[]> FromPhone = new();
        public readonly BlockingCollection<byte[]> ToPhone = new();
        private readonly CancellationTokenSource _cancel = new();

        public int Read(Span<byte> buffer)
        {
            try
            {
                var d = FromPhone.Take(_cancel.Token);
                d.CopyTo(buffer);
                return d.Length;
            }
            catch (Exception e) when (e is OperationCanceledException or InvalidOperationException)
            {
                throw new IOException("unplugged");
            }
        }

        public bool Write(ReadOnlySpan<byte> data)
        {
            ToPhone.Add(data.ToArray());
            return true;
        }

        public void Cancel() => _cancel.Cancel();

        public void Dispose() => _cancel.Dispose();
    }

    private readonly HostEngine _engine;
    private readonly UdpHostServer _server;
    private readonly FakePipe _pipe = new();
    private readonly UsbBridge _bridge;

    public UsbBridgeTests()
    {
        var clock = StopwatchClock.Instance;
        var settings = new HostSettings { HostId = Hex.Range(0x10, 16), HostName = "USB-PC" };
        _engine = new HostEngine(settings, new FakePad(), new FakeKeyMouse(), new FakePrompt(), new PlainProtector(), clock);
        _engine.Start(clock.NowUs);
        _server = new UdpHostServer(_engine, clock, new IPEndPoint(IPAddress.Loopback, 0));
        Assert.True(_server.Start());
        _bridge = new UsbBridge(_pipe, _server.LocalPort);
        _bridge.Start();
    }

    [Fact]
    public void DiscoverOverTheCableGetsAnAnnounceBack()
    {
        var disc = new byte[Wire.MaxDatagram];
        int n = WireCodec.EncodeDiscover(new Discover(77, Hex.Range(0x20, 16), 0), disc);
        var frame = new byte[UsbFraming.MaxSize(n)];
        int f = UsbFraming.Encode(disc.AsSpan(0, n), frame);
        // Split across two USB reads, as a slow cable could deliver it.
        _pipe.FromPhone.Add(frame[..2]);
        _pipe.FromPhone.Add(frame[2..f]);

        Assert.True(_pipe.ToPhone.TryTake(out var back, 2000));
        byte[]? announce = null;
        new UsbDeframer().Feed(back, d => announce = d.ToArray());
        Assert.NotNull(announce);
        Assert.True(WireCodec.TryDecodeAnnounce(announce, out var a));
        Assert.Equal(77u, a.ReqId);
        Assert.Equal("USB-PC", a.Name);
        Assert.True(_bridge.Alive);
    }

    [Fact]
    public void UnpluggingEndsTheBridge()
    {
        _pipe.Cancel();
        var until = DateTime.UtcNow.AddSeconds(2);
        while (_bridge.Alive && DateTime.UtcNow < until) Thread.Sleep(10);
        Assert.False(_bridge.Alive);
    }

    public void Dispose()
    {
        _bridge.Dispose();
        _server.Dispose();
    }
}
