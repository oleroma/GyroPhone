using System.IO.Pipes;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Split;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The companion and the input process as two processes: the command and event pipe, and the shared memory.</summary>
public class SplitTests
{
    public interface ICalc
    {
        int Add(int a, int b);
        string? Echo(string? s);
        void Fail();
        HostSnapshot Snap();
        byte[]? Bytes(byte[]? b);
    }

    private sealed class Calc : ICalc
    {
        public int Add(int a, int b) => a + b;
        public string? Echo(string? s) => s;
        public void Fail() => throw new InvalidOperationException("no");
        public HostSnapshot Snap() => Snapshot();
        public byte[]? Bytes(byte[]? b) => b;
    }

    internal static HostSnapshot Snapshot() => new("OnePlus 15", true, false, OutputMode.DualShock4, true, true, false, false, true, 42,
        (7u, "Pixel", "400 588"), 950, 3, 1800, 3100, 400, new LinkInfo(1, 500, 1000, 0, 5, 0, 0, 0), true,
        [new PairedPhoneInfo([1, 2, 3], "OnePlus 15", new DateTime(2026, 11, 15, 10, 0, 0, DateTimeKind.Utc), true, "a.pub", "AB:CD")],
        new HostCounters(1, 2, 3, 4, 5, 6, 7, 8), "AA:BB", 5, null, "", true, true);

    private static (PipeChannel Server, PipeChannel Client, List<(string, JsonElement)> Events) Pair(object? target = null)
    {
        string name = "gp-test-" + Guid.NewGuid().ToString("N");
        var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
        var client = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
        var accept = server.WaitForConnectionAsync();
        client.Connect(5000);
        accept.Wait(5000);
        var events = new List<(string, JsonElement)>();
        var s = new PipeChannel(server, target == null ? null : PipeChannel.Dispatch((ICalc)target), null, name: "test-server");
        var c = new PipeChannel(client, null, (n, p) => { lock (events) events.Add((n, p)); }, name: "test-client");
        return (s, c, events);
    }

    [Fact]
    public void CallsGoThroughTheInterfaceAndComeBack()
    {
        var (server, client, _) = Pair(new Calc());
        using var _s = server;
        using var _c = client;
        var calc = client.Proxy<ICalc>();
        Assert.Equal(5, calc.Add(2, 3));
        Assert.Equal("hi", calc.Echo("hi"));
        Assert.Null(calc.Echo(null));
        Assert.Equal(new byte[] { 9, 8 }, calc.Bytes([9, 8]));
        var e = Assert.Throws<InvalidOperationException>(() => calc.Fail());
        Assert.Contains("no", e.Message);

        // A snapshot keeps every field (records, a tuple, a list of records, a LinkInfo) across the line.
        var want = Snapshot();
        var got = calc.Snap();
        Assert.Equal(want with { PairedPhones = [], PhoneLink = null }, got with { PairedPhones = [], PhoneLink = null });
        Assert.Equal(want.PhoneLink, got.PhoneLink);
        var p = Assert.Single(got.PairedPhones);
        Assert.Equal(new byte[] { 1, 2, 3 }, p.PhoneId);
        Assert.Equal("a.pub", p.KeyFile);
        Assert.Equal((7u, "Pixel", "400 588"), got.PairPrompt);
    }

    [Fact]
    public void EventsArriveInOrderAndAGoneSideFailsCalls()
    {
        var (server, client, events) = Pair(new Calc());
        using var _c = client;
        for (int i = 0; i < 50; i++) Assert.True(server.Post("n", i));
        Assert.True(SpinWait.SpinUntil(() => { lock (events) return events.Count == 50; }, 5000));
        lock (events)
            for (int i = 0; i < 50; i++) Assert.Equal(i, events[i].Item2.GetInt32());

        bool closed = false;
        client.Closed += () => closed = true;
        server.Dispose();
        Assert.True(SpinWait.SpinUntil(() => closed, 5000));
        Assert.Throws<PipeClosedException>(() => client.Proxy<ICalc>().Add(1, 2));
        Assert.False(client.Post("late"));
    }

    [Fact]
    public void TheOverlayRingCarriesPacketsAndMarksInOrder()
    {
        using var region = SharedRegion.InProcess();
        var data = new byte[Wire.OverlayPlain];
        data[0] = 7;
        Assert.True(region.Overlays.PutGated(data, 100, 5, 3, true, 2));
        region.Overlays.Mark(OverlayInbox.Kind.Clear, 200, flag: true);
        Assert.True(region.Overlays.TryPeek(out var a));
        Assert.Equal(OverlayInbox.Kind.Overlay, a.Kind);
        Assert.True(a.Gated && a.GateHeld);
        Assert.Equal(3, a.GateAxes);
        Assert.Equal(2, a.GatePresses);
        Assert.Equal(7, a.Data[0]);
        region.Overlays.Release();
        Assert.True(region.Overlays.TryPeek(out var b));
        Assert.Equal(OverlayInbox.Kind.Clear, b.Kind);
        Assert.True(b.Flag);
        Assert.Equal(200, b.RxUs);
        region.Overlays.Release();
        Assert.False(region.Overlays.TryPeek(out _));

        // A reader that comes back skips what waits.
        region.Overlays.Put(OverlayInbox.Kind.Samples, data, 1, 1);
        region.Overlays.SkipAll();
        Assert.False(region.Overlays.TryPeek(out _));
    }

    [Fact]
    public void TheHeldMirrorShowsTheStateAndThePcControllerOnlyWhileItsSwitchIsOn()
    {
        using var region = SharedRegion.InProcess();
        var held = region.Held;
        var s = new StateFrame { MouseButtons = 1, Rt = 1023 };
        held.PublishHeldOutputs(s);
        held.SetConnected(true);
        var scratch = new StateFrame();
        Assert.True(held.Connected);
        Assert.True(held.Held(LaserFire.Mouse, scratch)); // left mouse
        Assert.True(held.Held(LaserFire.Rt, scratch));
        Assert.False(held.Held(LaserFire.Lt, scratch));

        Span<float> axes = stackalloc float[6];
        held.PublishPcPad(true, 0, [0, 0, 0, 0, 0.9f, 0]);
        Assert.False(held.PcPad(out _, axes)); // the phone's switch is off
        Assert.False(held.Held(LaserFire.Lt, scratch));
        held.SetPadGyro(true);
        Assert.True(held.PcPad(out _, axes));
        Assert.Equal(0.9f, axes[4]);
        Assert.True(held.Held(LaserFire.Lt, scratch)); // the PC controller's left trigger
    }

    /// <summary>
    /// The Rust input process (companion/src/input, shared.rs) writes Diagnostics' raw events byte by byte at these
    /// places, and the whole region at these offsets: when this fails, change both sides and bump the layout version.
    /// </summary>
    [Fact]
    public unsafe void TheSharedMemoryLayoutIsTheOneTheRustInputProcessWrites()
    {
        Assert.Equal(2, SharedRegion.LayoutVersion);
        Assert.Equal(64, System.Runtime.CompilerServices.Unsafe.SizeOf<DebugRaw>());
        Assert.Equal(192 + 16_384 * 64, DebugQueue.BytesNeeded);
        Assert.Equal(264, OverlayInbox.MaxBytes);
        Assert.Equal(192 + 256 * 320, OverlayInbox.BytesNeeded);
        Assert.Equal(192 + 4096 * 16, MoveRing.BytesNeeded);
        Assert.Equal(4096 + 86_016 + 1_052_672 + 69_632, SharedRegion.TotalBytes);

        var raw = new DebugRaw
        {
            Us = 0x0102030405060708,
            Kind = DebugRaw.Type.Ds4,
            Inject = new GyroPhone.Protocol.Mapping.InjectEvent
            {
                Kind = GyroPhone.Protocol.Mapping.InjectKind.Mouse, Usage = 0x1D, Scan = 0xE01D, Vk = 0x1234,
                Flags = 0x8000000B, Dx = -3, Dy = 70_000, MouseData = -120,
            },
            X360 = new GyroPhone.Protocol.Mapping.X360Frame(0x9001, -32768, 32767, 1, -1, 200, 7),
            Ds4 = new GyroPhone.Protocol.Mapping.Ds4Frame(0x8040, 3, 8, 1, 2, 3, 4, 5, 6),
        };
        byte* p = (byte*)&raw;
        Assert.Equal(0x0102030405060708, *(long*)p);
        Assert.Equal(2, p[8]);
        // The inject event at 12: kind, usage, scan, vk, 2 spare, flags, dx, dy, mouse data.
        Assert.Equal(1, p[12]);
        Assert.Equal(0x1D, p[13]);
        Assert.Equal(0xE01D, *(ushort*)(p + 14));
        Assert.Equal(0x1234, *(ushort*)(p + 16));
        Assert.Equal(0x8000000Bu, *(uint*)(p + 20));
        Assert.Equal(-3, *(int*)(p + 24));
        Assert.Equal(70_000, *(int*)(p + 28));
        Assert.Equal(-120, *(int*)(p + 32));
        // The Xbox frame at 36: buttons, lx, ly, rx, ry, lt, rt.
        Assert.Equal(0x9001, *(ushort*)(p + 36));
        Assert.Equal(-32768, *(short*)(p + 38));
        Assert.Equal(32767, *(short*)(p + 40));
        Assert.Equal(1, *(short*)(p + 42));
        Assert.Equal(-1, *(short*)(p + 44));
        Assert.Equal(200, p[46]);
        Assert.Equal(7, p[47]);
        // The DualShock frame at 48: buttons, special, hat, lx, ly, rx, ry, lt, rt.
        Assert.Equal(0x8040, *(ushort*)(p + 48));
        for (int i = 0; i < 8; i++) Assert.Equal(new byte[] { 3, 8, 1, 2, 3, 4, 5, 6 }[i], p[50 + i]);
    }

    [Fact]
    public void TheInputProcessTraceForwardsLinesAndTheCompanionMakesTheText()
    {
        using var region = SharedRegion.InProcess();
        var lines = new List<DebugLine>();
        long now = 1_000_000;
        var input = new DebugTrace(() => now, queue: region.Debug, forward: (us, k, t) => lines.Add(new DebugLine(us, k, t)));
        var companion = new DebugTrace(() => now, queue: region.Debug);
        companion.SetOn(true, "test");
        input.SetOn(true, "test");
        Assert.Contains(lines, l => l.Text.StartsWith("diagnostics on", StringComparison.Ordinal));

        // The input path only copies raw events; the companion drains them into lines.
        Span<GyroPhone.Protocol.Mapping.InjectEvent> keys = [GyroPhone.Protocol.Mapping.InjectEvent.MouseButton(0, false)];
        input.OnInject(keys, now);
        input.OnX360(new GyroPhone.Protocol.Mapping.X360Frame(0x1000, 0, 0, 0, 0, 0, 0), now);
        Assert.Empty(input.Latest(10));
        companion.Drain();
        var text = companion.Latest(10).Select(e => e.Text).ToList();
        Assert.Contains("mouse left down", text);
        Assert.Contains("xbox A down", text);
        companion.SetOn(false, "test");
    }

    [Fact]
    public void StatusAndPerformanceSecondsSurviveTheLine()
    {
        var status = new InputStatus(Snapshot(), InputSettingsView.Of(new GyroPhone.Host.Settings.HostSettings { HostName = "PC", Diagnostics = true }),
            "Running", false, true, "locked", true, null, true, false, true, "Connected", "accessory", "2 phones", 4, 1,
            new ButtonLatency(9000, 4000, 12, 8000), true, @"C:\keys", null, 3);
        var back = JsonSerializer.Deserialize<InputStatus>(JsonSerializer.SerializeToUtf8Bytes(status, PipeChannel.Json), PipeChannel.Json)!;
        Assert.Equal(status with { Snapshot = back.Snapshot }, back);
        Assert.Equal("PC", back.Settings.HostName);
        Assert.True(back.Settings.Diagnostics);

        var link = new GyroPhone.Host.Perf.LinkSecond(5_000_000, 950, 2, 1, 3.5f, 1, 0);
        Assert.Equal(link, RoundTrip(link));
        var line = new DebugLine(42, DebugTrace.Kind.Link, "sec 1");
        Assert.Equal(line, RoundTrip(line));
    }

    private static T RoundTrip<T>(T value)
    {
        var e = JsonSerializer.SerializeToElement((object?)value, typeof(T), PipeChannel.Json);
        return e.Deserialize<T>(PipeChannel.Json)!;
    }

    [Fact]
    public void CallsWithoutALineReturnDefaultsAndSayWhy()
    {
        PipeChannel? none = null;
        var failed = new List<string>();
        var calc = PipeChannel.Proxy<ICalc>(() => none, TimeSpan.FromSeconds(1), (m, _) => failed.Add(m));
        Assert.Equal(0, calc.Add(1, 2));
        Assert.Null(calc.Echo("x"));
        Assert.Null(calc.Snap());
        Assert.Equal(["Add", "Echo", "Snap"], failed);

        // With a line, the same proxy goes through.
        var (server, client, _) = Pair(new Calc());
        using var _s = server;
        using var _c = client;
        none = client;
        Assert.Equal(3, calc.Add(1, 2));
    }

    [Fact]
    public async Task ANewInputProcessEndsAWriteACrashedOneLeftHalfDone()
    {
        var (held, memory) = MirrorWithAWriteCutOff();
        try
        {
            // A crash in the middle of a write leaves the counter odd: readers give up waiting instead of hanging.
            var scratch = new StateFrame();
            await Task.Run(() => held.Held(LaserFire.Mouse, scratch)).WaitAsync(TimeSpan.FromSeconds(5));
            held.Reset();
            Assert.False(held.Connected);
            Assert.False(held.PcPad(out _, new float[6]));
        }
        finally
        {
            Free(memory);
        }
    }

    private static unsafe (HeldMirror, IntPtr) MirrorWithAWriteCutOff()
    {
        var p = (byte*)System.Runtime.InteropServices.NativeMemory.AllocZeroed(HeldMirror.BytesNeeded);
        var held = new HeldMirror(p);
        held.SetConnected(true);
        *(int*)p += 1;
        return (held, (IntPtr)p);
    }

    private static unsafe void Free(IntPtr p) => System.Runtime.InteropServices.NativeMemory.Free((void*)p);
}
