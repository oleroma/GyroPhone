using System.Net;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

/// <summary>
/// The input thread (gp-rx) makes no new memory per packet: everything it uses is made once and reused, so .NET
/// never has to stop it to clean up after it. Fails as soon as a change brings garbage back onto the input path.
/// </summary>
public class ZeroAllocationTests
{
    private sealed class NullPad : IPadSink
    {
        public bool DriverOk => true;
        public OutputMode Plugged { get; private set; }
        public bool Plug(OutputMode mode) { Plugged = mode; return true; }
        public void Unplug() => Plugged = OutputMode.None;
        public void Submit(in X360Frame frame) { }
        public void Submit(in Ds4Frame frame, in Ds4Motion motion) { }
#pragma warning disable CS0067 // never raised here
        public event Action<byte, byte>? Rumble;
#pragma warning restore CS0067
    }

    private sealed class NullKeyMouse : IKeyMouseSink
    {
        public long Events;
        public bool Inject(ReadOnlySpan<InjectEvent> events)
        {
            Events += events.Length;
            return true;
        }
    }

    private sealed class NullSender : IDatagramSender
    {
        public long Sent;
        public void Send(ReadOnlySpan<byte> datagram, SocketAddress to) => Sent++;
    }

    /// <summary>What the phone sends while you play: STATEs (keys, buttons, sticks, mouse, wheel), pings, link info, overlay data.</summary>
    private static List<byte[]> Play(FakePhone phone, int count)
    {
        var list = new List<byte[]>(count * 2);
        var overlay = Hex.B(OverlayTests.Frame);
        var samples = new byte[Wire.OverlaySamplesPlain];
        var link = new byte[Wire.LinkInfoPlain];
        for (int i = 0; i < count; i++)
        {
            list.Add(phone.State(f =>
            {
                f.PhoneTimeUs += 1000;
                f.MouseTotalX += (i % 7) - 3;
                f.MouseTotalY += (i % 5) - 2;
                if (i % 50 == 0) f.WheelTotal += 120;
                f.Lx = (short)(i * 97);
                f.Rt = (ushort)(i % 2 == 0 ? 0 : 1023);
                f.Buttons = (ushort)((i / 20) % 2 == 0 ? 0 : 1);
                f.MouseButtons = (byte)((i / 30) % 2);
                // W held for a while, then let go.
                if ((i / 40) % 2 == 0) f.Keys[0x1A >> 3] |= 1 << (0x1A & 7);
                else f.Keys[0x1A >> 3] &= unchecked((byte)~(1 << (0x1A & 7)));
            }));
            if (i % 16 == 0)
            {
                Le.PutU32(overlay, 0, (uint)(i * 1000));
                list.Add(phone.Sealed(PacketType.OverlaySamples, samples));
                list.Add(phone.Sealed(PacketType.Overlay, overlay));
            }
            if (i % 200 == 0) list.Add(phone.Ping((ulong)i));
            if (i % 1000 == 0)
            {
                WireCodec.EncodeLinkInfo(new LinkInfo(0, 500, 1000, 0, 5, 0, 0, 0), link);
                list.Add(phone.Sealed(PacketType.LinkInfo, link));
            }
        }
        return list;
    }

    private static string? where;

    private static long RunAndMeasure(bool diagnostics)
    {
        var settings = new HostSettings { HostId = Hex.Range(0x10, 16), HostName = "DESKTOP-OLEX", OutputMode = OutputMode.Xbox360, Diagnostics = diagnostics };
        settings.PairedPhones.Add(new PairedPhone { PhoneId = Harness.Phone1Id, Name = "OnePlus 15", ProtectedKey = Harness.Phone1Key });
        settings.Overlays.Add(new OverlaySettings { Kind = OverlayKind.Shape, Shown = true, Gravity = true });
        settings.Overlays.Add(new OverlaySettings { Kind = OverlayKind.Graph, Shown = true });
        var clock = new FakeClock();
        var sender = new NullSender();
        var keyMouse = new NullKeyMouse();
        var engine = new HostEngine(settings, new NullPad(), keyMouse, new FakePrompt(), new PlainProtector(), clock)
        {
            PadWorker = job => job(),
            Recorder = new InputRecorder(_ => { }),
        };
        engine.AttachSender(sender);
        // The companion's overlay side on its own thread, as in the companion (here in the same process).
        var overlays = new OverlayHost(settings, engine.Shared, new AutoResetEvent(false), (on, c, s) => engine.SetOverlay(on, c, s));
        engine.OverlayWake = overlays.Signal;
        overlays.Start();

        var phone = FakePhone.Paired(Harness.Phone1Id, "OnePlus 15", Harness.Phone1Key);
        var welcome = new FakeSender();
        engine.AttachSender(welcome);
        engine.OnDatagram(phone.Hello(), phone.Addr, clock.NowUs);
        Assert.True(phone.OnWelcome(welcome.Last!));
        engine.AttachSender(sender);
        overlays.SetOverlay(true);

        // The first run warms everything up (first use, JIT). Then up to three measured runs, packet by packet, so a
        // failure names where the memory was made: the path is clean when one of them made none. A run can catch a
        // one-off on this thread that isn't the play path's (seen on Windows: about 1.4 KB once, in a different test
        // each time, most likely .NET's own work when a method gets hot); memory the play path makes shows in every run.
        long allocated = 0;
        for (int run = 0; run < 4; run++)
        {
            if (run >= 2 && allocated == 0) break;
            var packets = Play(phone, 3000);
            allocated = 0;
            int offenders = 0;
            where = null;
            for (int k = 0; k < packets.Count; k++)
            {
                var d = packets[k];
                clock.NowUs += 333;
                long b0 = GC.GetAllocatedBytesForCurrentThread();
                engine.OnDatagram(d, phone.Addr, clock.NowUs);
                long b1 = GC.GetAllocatedBytesForCurrentThread();
                engine.Flush(clock.NowUs);
                long b2 = GC.GetAllocatedBytesForCurrentThread();
                if (b2 == b0) continue;
                allocated += b2 - b0;
                offenders++;
                where ??= $"packet {k} of {packets.Count} (type 0x{d[3]:X2}): {b1 - b0} B in OnDatagram, {b2 - b1} B in Flush";
            }
            if (where != null) where += $"; {offenders} packets made memory in run {run}";
        }
        // It really played: every STATE was taken, moved the mouse and pressed keys, the pings were answered.
        Assert.True(engine.Connected);
        Assert.True(keyMouse.Events > 3000, $"injected {keyMouse.Events}");
        Assert.True(sender.Sent >= 30, $"sent {sender.Sent}");
        Assert.True(engine.Snapshot().Counters.States >= 6000, $"states {engine.Snapshot().Counters.States}");
        return allocated;
    }

    [Fact]
    public void ThePlayPathMakesNoNewMemory()
    {
        long made = RunAndMeasure(diagnostics: false);
        Assert.True(made == 0, $"{made} B made while playing; first at {where}");
    }

    [Fact]
    public void ThePlayPathMakesNoNewMemoryWithDiagnosticsOn()
    {
        long made = RunAndMeasure(diagnostics: true);
        Assert.True(made == 0, $"{made} B made while playing; first at {where}");
    }
}
