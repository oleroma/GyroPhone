using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

internal sealed class FakeKeyListener : IKeyMouseListener
{
    public readonly List<PcPress> Held = new();
    public readonly List<PcPress> Once = new();
    public int Starts, Stops;
    public bool Running;

    public void Start()
    {
        Starts++;
        Running = true;
    }

    public void Stop()
    {
        Stops++;
        Running = false;
    }

    public int Read(Span<PcPress> into)
    {
        int n = 0;
        foreach (var p in Held.Concat(Once)) into[n++] = p;
        Once.Clear();
        return n;
    }
}

internal sealed class FakePcPad : IPcPad
{
    public bool Connected = true;
    public uint Held;
    public readonly uint[] Presses = new uint[18];
    public readonly float[] Axes = new float[6];
    public bool Running;

    public void Start(Func<int> ownSlot) => Running = true;

    public void Stop() => Running = false;

    public bool Read(uint[] presses, out uint held)
    {
        Array.Copy(Presses, presses, Math.Min(presses.Length, Presses.Length));
        held = Connected ? Held : 0;
        return Connected;
    }

    public bool ReadAxes(Span<float> axes)
    {
        Axes.CopyTo(axes);
        return Connected;
    }

    public bool Peek(out uint held, Span<float> axes)
    {
        held = Held;
        Axes.CopyTo(axes);
        return Connected;
    }

    public uint HeldAt(long us) => Held;

    public string? Name => Connected ? "Controller 1" : null;
}

public class PressCaptureTests
{
    private static List<PcPress> Decode(ReadOnlySpan<byte> b)
    {
        var into = new PcPress[Wire.CapturedMax];
        int n = WireCodec.DecodeCaptured(b, into);
        return into.Take(n).ToList();
    }

    [Fact]
    public void CapturedRoundTrips()
    {
        var held = new[] { new PcPress(PcPress.Key, 0x04), new PcPress(PcPress.PadAxis, 1, -1), new PcPress(PcPress.Wheel, 0, 1) };
        var b = new byte[Wire.CapturedPlain];
        Assert.Equal(64, WireCodec.EncodeCaptured(held, b));
        Assert.Equal("03000100040005ff01000301", Convert.ToHexString(b, 0, 12).ToLowerInvariant());
        Assert.Equal(held, Decode(b));
        Assert.Equal(Wire.Header + 64 + Wire.GcmTag, PacketType.Size(PacketType.Captured));
        Assert.Equal(Wire.Header + 4 + Wire.GcmTag, PacketType.Size(PacketType.Capture));
    }

    [Fact]
    public void EveryKeyMapsBackToItsUsage()
    {
        for (int u = 0; u < 256; u++)
        {
            var c = HidScanCodes.For(u);
            if (!c.IsMapped) continue;
            // 0x32 (non-US #) shares its scan code with backslash (0x31): the PC reads it as backslash.
            int want = u == 0x32 ? 0x31 : u;
            Assert.Equal(want, HidScanCodes.UsageOf(c.Scan, c.Extended, c.Vk));
        }
        // NumLock without its E0, as many keyboards send it; the fake shift's E0 2A is skipped by the listener.
        Assert.Equal(0x53, HidScanCodes.UsageOf(0x45, false, 0x90));
        Assert.Equal(0x48, HidScanCodes.UsageOf(0x45, false, InjectEvent.VkPause));
    }

    [Fact]
    public void SendsWhatIsHeldOnChangeAndKeepsATapBriefly()
    {
        var c = new PressCapture();
        var keys = new FakeKeyListener();
        var pad = new FakePcPad();
        var b = new byte[Wire.CapturedPlain];
        Assert.Equal(0, c.Tick(keys, pad, 0, b)); // not listening
        Assert.True(c.OnCapture(CaptureFlags.On, 0));

        Assert.Equal(64, c.Tick(keys, pad, 0, b));
        Assert.Empty(Decode(b));
        Assert.Equal(0, c.Tick(keys, pad, 50_000, b)); // nothing changed

        keys.Held.Add(new PcPress(PcPress.Key, 0xE0));
        keys.Once.Add(new PcPress(PcPress.Key, 0x06));
        Assert.Equal(64, c.Tick(keys, pad, 100_000, b));
        Assert.Equal([new PcPress(PcPress.Key, 0xE0), new PcPress(PcPress.Key, 0x06)], Decode(b));
        // The tap stays for HoldOverUs, then goes.
        Assert.Equal(0, c.Tick(keys, pad, 200_000, b));
        Assert.Equal(64, c.Tick(keys, pad, 300_000, b));
        Assert.Equal([new PcPress(PcPress.Key, 0xE0)], Decode(b));
        // Resent while unchanged.
        Assert.Equal(64, c.Tick(keys, pad, 500_000, b));
    }

    [Fact]
    public void ReadsControllerButtonsTapsAndAxes()
    {
        var c = new PressCapture();
        var keys = new FakeKeyListener();
        var pad = new FakePcPad();
        var b = new byte[Wire.CapturedPlain];
        c.OnCapture(CaptureFlags.On, 0);
        c.Tick(keys, pad, 0, b);
        pad.Held = 1u << 0; // south: A
        pad.Presses[14]++; // D-pad up pressed and let go between two reads
        pad.Axes[3] = -0.9f; // right stick up
        pad.Axes[5] = 0.7f; // right trigger
        pad.Axes[0] = 0.3f; // left stick a little: not pressed
        c.Tick(keys, pad, 50_000, b);
        Assert.Equal(
            [new PcPress(PcPress.PadButton, 0x1000), new PcPress(PcPress.PadButton, 0x0001),
             new PcPress(PcPress.PadAxis, 3, -1), new PcPress(PcPress.PadAxis, 5, 1)],
            Decode(b));
    }

    [Fact]
    public void StopsWhenToldOrAfterTheTimeout()
    {
        var c = new PressCapture();
        c.OnCapture(CaptureFlags.On, 0);
        Assert.False(c.Expired(2_000_000));
        c.OnCapture(CaptureFlags.On, 2_000_000);
        Assert.False(c.Expired(4_000_000));
        Assert.True(c.Expired(5_000_000));
        Assert.True(c.OnCapture(0, 5_000_000));
        Assert.False(c.On);
    }
}

public class PressCaptureEngineTests
{
    [Fact]
    public void ListensOnlyWhileThePhoneAsks()
    {
        using var h = new Harness();
        var keys = new FakeKeyListener();
        var pad = new FakePcPad();
        h.Engine.SetKeyMouseListener(keys);
        h.Engine.SetPcPad(pad);
        var phone = h.NewPhone();
        h.Connect(phone);
        Assert.False(keys.Running);
        Assert.False(pad.Running);

        Span<byte> on = stackalloc byte[Wire.CapturePlain];
        WireCodec.EncodeCapture(CaptureFlags.On, on);
        h.Send(phone, phone.Sealed(PacketType.Capture, on));
        Assert.True(keys.Running);
        Assert.True(pad.Running);

        keys.Held.Add(new PcPress(PcPress.Mouse, 3));
        h.Sender.Clear();
        h.Tick(50);
        var sent = h.Sender.Sent.Select(s => phone.TryOpen(s.Data, out var plain) == PacketType.Captured ? plain : null)
            .Where(p => p != null).ToList();
        Assert.Single(sent);
        var into = new PcPress[Wire.CapturedMax];
        Assert.Equal(1, WireCodec.DecodeCaptured(sent[0], into));
        Assert.Equal(new PcPress(PcPress.Mouse, 3), into[0]);

        // No CAPTURE for 3 s: it stops by itself.
        for (int i = 0; i < 61; i++) h.Tick(50);
        Assert.False(keys.Running);
        Assert.False(pad.Running);

        // Asked again, then told it closed.
        h.Send(phone, phone.Sealed(PacketType.Capture, on));
        Assert.True(keys.Running);
        Span<byte> off = stackalloc byte[Wire.CapturePlain];
        WireCodec.EncodeCapture(0, off);
        h.Send(phone, phone.Sealed(PacketType.Capture, off));
        Assert.False(keys.Running);
        Assert.False(pad.Running);
    }
}
