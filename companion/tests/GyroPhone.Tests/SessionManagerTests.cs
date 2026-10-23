using System.Net;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class SessionManagerTests
{
    private const int UsageW = 0x1A;

    [Fact]
    public void PendingSessionPromotedOnFirstState()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Equal(OutputMode.None, h.Pad.Plugged);

        h.Sender.Clear();
        h.Send(phone, phone.State());
        var snap = h.Engine.Snapshot();
        Assert.True(snap.Connected);
        Assert.Equal("OnePlus 15", snap.PhoneName);
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        // HOST_STATUS goes out right after promotion.
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.PadPlugged);
    }

    [Fact]
    public void ReplayedHelloDoesNotDisturbActiveSession()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        var hello = phone.Hello();
        var welcome = h.Send(phone, hello)!;
        Assert.True(phone.OnWelcome(welcome));
        h.Send(phone, phone.State(f => f.SetKey(UsageW, true)));
        int injections = h.KeyMouse.Calls.Count;

        // Exact retransmit: the same WELCOME, same sid.
        Assert.Equal(welcome, h.Send(phone, hello));
        // An attacker replays it from elsewhere: still nothing changes.
        var attacker = h.NewPhone("10.0.0.99", 1234);
        Assert.Equal(welcome, h.Send(attacker, hello));
        Assert.True(h.Engine.Snapshot().Connected);
        Assert.Equal(injections, h.KeyMouse.Calls.Count);
        Assert.NotEmpty(h.KeyMouse.HeldKeys);

        // An old HELLO (other nP) only makes a pending session that expires; the active one keeps working.
        var old = h.NewPhone("10.0.0.99", 1234);
        var oldHello = old.Hello();
        Assert.NotNull(h.Send(old, oldHello));
        h.Tick(Wire.PendingSessionMs + 50);
        h.Send(phone, phone.State());
        Assert.True(h.Engine.Snapshot().Connected);
    }

    [Fact]
    public void BadMacGetsNoReply()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        var hello = phone.Hello();
        hello[^1] ^= 1;
        Assert.Null(h.Send(phone, hello));
        Assert.Equal(1, h.Engine.Snapshot().Counters.BadAuth);
    }

    [Fact]
    public void UnknownPhoneGetsNotPaired()
    {
        var h = new Harness();
        var stranger = FakePhone.Paired(Hex.Range(0x90, 16), "Other", Hex.Range(0x20, 32));
        Assert.Equal("4750017f000000000000000002000100", Hex.S(h.Send(stranger, stranger.Hello())));
    }

    [Fact]
    public void UnknownSessionGetsSessionUnknown()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        // Host restarted: a fresh engine has no session.
        var h2 = new Harness();
        var reply = h2.Send(phone, phone.State());
        Assert.True(WireCodec.TryDecodeError(reply!, out var e));
        Assert.Equal(ErrorCode.SessionUnknown, e.Code);
    }

    [Fact]
    public void ReleaseAllExactlyOnceAt250Ms()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.MouseButtons = 1; f.Lx = 20000; }));
        Assert.Contains((ushort)0x11, h.KeyMouse.HeldKeys);
        int calls = h.KeyMouse.Calls.Count;
        int padSubmits = h.Pad.X360.Count;

        for (int i = 0; i < 4; i++) h.Tick(50); // 200 ms: nothing yet
        Assert.Equal(calls, h.KeyMouse.Calls.Count);
        h.Tick(50); // 250 ms
        Assert.Equal(calls + 1, h.KeyMouse.Calls.Count);
        Assert.True(h.KeyMouse.NothingHeld);
        Assert.Equal(X360Frame.Neutral, h.Pad.X360[^1]);
        Assert.Equal(padSubmits + 1, h.Pad.X360.Count);
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged); // the pad stays plugged

        for (int i = 0; i < 20; i++) h.Tick(50);
        Assert.Equal(calls + 1, h.KeyMouse.Calls.Count);

        // Traffic resumes: the held state comes back, mouse rebases (no jump).
        long mx = h.KeyMouse.MouseX;
        h.Send(phone, phone.State(f => f.MouseTotalX += 5000));
        Assert.Contains((ushort)0x11, h.KeyMouse.HeldKeys);
        Assert.Equal(mx, h.KeyMouse.MouseX);
    }

    [Fact]
    public void SessionDropsAt10Seconds()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Tick(Wire.SessionTimeoutMs - 100);
        Assert.True(h.Engine.Snapshot().Connected);
        h.Tick(150);
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Equal(OutputMode.None, h.Pad.Plugged);
        Assert.Equal(1, h.Pad.Unplugs);
    }

    [Fact]
    public void ByeReleases()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.MouseButtons = 0b10000; }));
        Assert.False(h.KeyMouse.NothingHeld);
        h.Send(phone, phone.Bye(ByeReason.TransportSwitch));
        Assert.True(h.KeyMouse.NothingHeld);
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Equal(OutputMode.None, h.Pad.Plugged);
    }

    [Fact]
    public void ReplacementSendsHostByeToOldEndpoint()
    {
        var h = new Harness();
        var secondId = Hex.Range(0x80, 16);
        var secondKey = Hex.Range(0xA0, 32);
        h.AddPaired(secondId, "Pixel", secondKey);
        var h2 = new Host.HostEngine(h.Settings, h.Pad, h.KeyMouse, h.Prompt, new PlainProtector(), h.Clock, h.Events);
        h2.AttachSender(h.Sender);

        var a = h.NewPhone("192.168.1.50", 40000);
        var b = FakePhone.Paired(secondId, "Pixel", secondKey, "192.168.1.77", 41000);
        byte[]? Send(FakePhone p, byte[] d)
        {
            int n = h.Sender.Sent.Count;
            h2.OnDatagram(d, p.Addr, h.Clock.NowUs);
            h2.Flush(h.Clock.NowUs);
            return h.Sender.Sent.Count > n ? h.Sender.Sent[n].Data : null;
        }

        Assert.True(a.OnWelcome(Send(a, a.Hello())!));
        Send(a, a.State(f => f.SetKey(UsageW, true)));
        Assert.False(h.KeyMouse.NothingHeld);

        Assert.True(b.OnWelcome(Send(b, b.Hello())!));
        Assert.Equal("OnePlus 15", h2.Snapshot().PhoneName); // HELLO alone does not replace
        h.Sender.Clear();
        Send(b, b.State());
        Assert.Equal("Pixel", h2.Snapshot().PhoneName);
        Assert.True(h.KeyMouse.NothingHeld);

        var toA = h.Sender.To(a.Addr);
        Assert.Equal(3, toA.Count);
        foreach (var d in toA)
        {
            Assert.Equal(PacketType.HostBye, a.TryOpen(d, out var plain));
            Assert.Equal(HostByeReason.Replaced, plain[0]);
        }
        // The old session is gone: its STATEs are now unknown.
        var reply = Send(a, a.State());
        Assert.True(WireCodec.TryDecodeError(reply!, out var e));
        Assert.Equal(ErrorCode.SessionUnknown, e.Code);
    }

    [Fact]
    public void SamePhoneReplacementKeepsHeldInput()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.MouseButtons = 1; f.Buttons = 1; f.MouseTotalX = 500; }));
        uint oldSid = phone.SessionId;
        var late = phone.State(); // an old-session copy still in flight
        int calls = h.KeyMouse.Calls.Count;
        int frames = h.Pad.X360.Count;
        long mx = h.KeyMouse.MouseX;

        // Network change: a new HELLO from the same phone; its totals restart at 0.
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        Assert.NotEqual(oldSid, phone.SessionId);
        h.Sender.Clear();
        h.Send(phone, phone.State(f => f.MouseTotalX = 0));
        Assert.True(h.Engine.Snapshot().Connected);
        Assert.Equal(calls, h.KeyMouse.Calls.Count); // no ups, no re-press
        Assert.DoesNotContain(X360Frame.Neutral, h.Pad.X360.Skip(frames));
        Assert.Contains((ushort)0x11, h.KeyMouse.HeldKeys);
        Assert.Contains("L", h.KeyMouse.HeldButtons);
        Assert.DoesNotContain(h.Sender.Sent, x => x.Data[3] == PacketType.HostBye);

        // The late old-session packet is dropped silently (an ERROR would restart the phone's session).
        h.Sender.Clear();
        Assert.Null(h.Send(phone, late));

        // Motion continues from the new baseline, and a dropped key is released once.
        h.Send(phone, phone.State(f => { f.MouseTotalX = 7; f.SetKey(UsageW, false); }));
        Assert.Equal(mx + 7, h.KeyMouse.MouseX); // the new session's first STATE is its baseline
        Assert.DoesNotContain((ushort)0x11, h.KeyMouse.HeldKeys);
        Assert.Single(h.KeyMouse.All, e => e.Kind == InjectKind.Key && e.IsKeyUp);
    }

    [Fact]
    public void TapInsideOneBurstIsStillInjected()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        int frames = h.Pad.X360.Count;
        // Press and release reach the socket together: one drain, one Flush.
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.MouseButtons = 1; f.Buttons = 1; }), flush: false);
        h.Send(phone, phone.State(f => { f.MouseTotalX += 3; }), flush: false); // a repeat with motion only
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, false); f.MouseButtons = 0; f.Buttons = 0; }), flush: false);
        h.Engine.Flush(h.Clock.NowUs);

        var all = h.KeyMouse.All.ToList();
        Assert.Contains(all, e => e.Kind == InjectKind.Key && !e.IsKeyUp);
        Assert.Contains(all, e => e.Kind == InjectKind.Key && e.IsKeyUp);
        Assert.Contains(all, e => (e.Flags & InjectEvent.MouseLeftDown) != 0);
        Assert.Contains(all, e => (e.Flags & InjectEvent.MouseLeftUp) != 0);
        Assert.True(h.KeyMouse.NothingHeld);
        Assert.Equal(3, h.KeyMouse.MouseX);

        // The pad button stays visible to a polling game for a moment, then lets go.
        Assert.Equal(XusbButtons.A, h.Pad.X360[^1].Buttons);
        Assert.Single(h.Pad.X360.Skip(frames), f => f.Buttons == XusbButtons.A);
        h.Clock.AdvanceMs(5);
        h.Send(phone, phone.State()); // a keepalive inside the hold changes nothing
        Assert.Equal(XusbButtons.A, h.Pad.X360[^1].Buttons);
        h.Tick(Wire.WatchdogTickMs);
        Assert.Equal(0, h.Pad.X360[^1].Buttons);
    }

    [Fact]
    public void LongHeldPadButtonReleasedInsideBurstLetsGoAtOnce()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => { f.Buttons = 1; f.Hat = 0; }));
        h.Clock.AdvanceMs(500);
        // One drain: the stick moves while A and up are still held, then both are released.
        h.Send(phone, phone.State(f => f.Rx = 9000), flush: false);
        h.Send(phone, phone.State(f => { f.Buttons = 0; f.Hat = StateFrame.HatCenter; }), flush: false);
        h.Engine.Flush(h.Clock.NowUs);
        Assert.Equal(0, h.Pad.X360[^1].Buttons);
    }

    [Fact]
    public void KeyEdgeDoesNotExtendHeldPadButton()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => f.Buttons = 1));
        h.Clock.AdvanceMs(500);
        // A key tap inside one drain while A is held steadily.
        h.Send(phone, phone.State(f => f.SetKey(UsageW, true)), flush: false);
        h.Send(phone, phone.State(f => f.SetKey(UsageW, false)), flush: false);
        h.Engine.Flush(h.Clock.NowUs);
        h.Clock.AdvanceMs(3);
        h.Send(phone, phone.State(f => f.Buttons = 0));
        Assert.Equal(0, h.Pad.X360[^1].Buttons);
    }

    [Fact]
    public void PadTapSpanningSeveralBurstFramesIsHeld()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        // A is pressed, a key edge follows, then A is released: all in one drain.
        h.Send(phone, phone.State(f => f.Buttons = 1), flush: false);
        h.Send(phone, phone.State(f => f.SetKey(UsageW, true)), flush: false);
        h.Send(phone, phone.State(f => { f.Buttons = 0; f.SetKey(UsageW, false); }), flush: false);
        h.Engine.Flush(h.Clock.NowUs);
        Assert.Equal(XusbButtons.A, h.Pad.X360[^1].Buttons);
        h.Tick(Wire.WatchdogTickMs);
        Assert.Equal(0, h.Pad.X360[^1].Buttons);
    }

    [Fact]
    public void PadFailureNeverStopsKeyboardMouseOrRelease()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Pad.FailWith = new InvalidOperationException("bus gone");

        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.Buttons = 1; f.MouseTotalX += 4; }));
        Assert.Contains((ushort)0x11, h.KeyMouse.HeldKeys);
        Assert.Equal(4, h.KeyMouse.MouseX);
        h.Send(phone, phone.State(f => f.Buttons = 2));
        Assert.Single(h.Events.Logs, l => l.StartsWith("pad submit failed"));

        // Silence still releases the keys, and the session still ends cleanly.
        h.Tick(Wire.ReleaseTimeoutMs + 10);
        Assert.True(h.KeyMouse.NothingHeld);
        h.Tick(Wire.SessionTimeoutMs);
        Assert.False(h.Engine.Snapshot().Connected);
    }

    [Fact]
    public void PadRecoversAfterFailure()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Pad.FailWith = new InvalidOperationException("bus gone");
        h.Send(phone, phone.State(f => f.Buttons = 1));
        h.Pad.FailWith = null;
        int frames = h.Pad.X360.Count;
        h.Send(phone, phone.State(f => f.MouseTotalX += 1)); // a broken bus is not retried on every packet
        Assert.Equal(frames, h.Pad.X360.Count);
        h.Clock.AdvanceMs(100);
        h.Send(phone, phone.State(f => f.MouseTotalX += 1)); // same pad frame: sent again, it never arrived
        Assert.Equal(XusbButtons.A, h.Pad.X360[^1].Buttons);
    }

    [Fact]
    public void MotionOnlyBurstInjectsOnce()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        int calls = h.KeyMouse.Calls.Count;
        for (int i = 0; i < 4; i++) h.Send(phone, phone.State(f => f.MouseTotalX += 2), flush: false);
        h.Engine.Flush(h.Clock.NowUs);
        Assert.Equal(calls + 1, h.KeyMouse.Calls.Count);
        Assert.Equal(8, h.KeyMouse.MouseX);
    }

    [Fact]
    public void OnlyAuthenticatedPacketMovesEndpoint()
    {
        var h = new Harness();
        var phone = h.NewPhone("192.168.1.50", 40000);
        h.Connect(phone);
        var spoof = new IPEndPoint(IPAddress.Parse("10.6.6.6"), 666).Serialize();

        // Tampered packet from another address: dropped, endpoint unchanged.
        var bad = phone.State();
        bad[^1] ^= 1;
        h.Engine.OnDatagram(bad, spoof, h.Clock.NowUs);
        // Replayed (old seq) valid packet from another address: dropped too.
        var first = phone.State();
        h.Send(phone, first);
        h.Engine.OnDatagram(first, spoof, h.Clock.NowUs);

        h.Sender.Clear();
        h.Tick(Wire.StatusIntervalMs);
        Assert.Single(h.Sender.Sent);
        Assert.True(h.Sender.Sent[0].To.Equals(phone.Addr));

        // A valid packet from the new address (phone roamed) moves it.
        var roamed = new IPEndPoint(IPAddress.Parse("192.168.1.51"), 40001).Serialize();
        h.Engine.OnDatagram(phone.State(), roamed, h.Clock.NowUs);
        h.Sender.Clear();
        h.Tick(Wire.StatusIntervalMs);
        Assert.True(h.Sender.Sent[0].To.Equals(roamed));
    }

    [Fact]
    public void PingGetsPong()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        var pong = h.Send(phone, phone.Ping(123));
        Assert.Equal(PacketType.Pong, phone.TryOpen(pong!, out var plain));
        var p = WireCodec.DecodePong(plain);
        Assert.Equal(123ul, p.T1);
        Assert.Equal((ulong)h.Clock.NowUs, p.T2);
    }

    [Fact]
    public void HostToPhoneSeqStrictlyIncreases()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        for (int i = 0; i < 5; i++) h.Send(phone, phone.Ping((ulong)i));
        uint last = 0;
        foreach (var (d, _) in h.Sender.Sent.Where(s => s.Data[3] is PacketType.Pong or PacketType.HostStatus))
        {
            uint seq = Le.U32(d, 8);
            Assert.True(seq > last);
            last = seq;
        }
    }

    [Fact]
    public void PauseInjectsNothingButKeepsBaseline()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => f.SetKey(UsageW, true)));
        h.Engine.SetPaused(true);
        Assert.True(h.KeyMouse.NothingHeld);
        int calls = h.KeyMouse.Calls.Count;
        h.Send(phone, phone.State(f => f.MouseTotalX += 100));
        Assert.Equal(calls, h.KeyMouse.Calls.Count);
        h.Engine.SetPaused(false);
        h.Send(phone, phone.State(f => f.MouseTotalX += 7));
        Assert.Equal(7, h.KeyMouse.MouseX); // the paused 100 never lands
    }

    [Fact]
    public void ForgetEndsSessionWithHostBye()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Sender.Clear();
        Assert.True(h.Engine.Forget(Harness.Phone1Id));
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Empty(h.Settings.PairedPhones);
        Assert.Equal(3, h.Sender.Sent.Count(s => phone.TryOpen(s.Data, out var p) == PacketType.HostBye && p[0] == HostByeReason.Forgotten));
    }

    [Fact]
    public void RumbleIsForwardedAndRateLimited()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Sender.Clear();
        h.Pad.RaiseRumble(255, 64);
        Assert.Empty(h.Sender.Sent); // the driver thread only records it
        h.Engine.Flush(h.Clock.NowUs);
        Assert.Single(h.Sender.Sent);
        Assert.Equal(PacketType.Rumble, phone.TryOpen(h.Sender.Sent[0].Data, out var p));
        Assert.Equal(new Rumble(255, 64), WireCodec.DecodeRumble(p));

        h.Pad.RaiseRumble(10, 10);
        h.Pad.RaiseRumble(0, 0);
        h.Engine.Flush(h.Clock.NowUs); // within 10 ms: held back
        Assert.Single(h.Sender.Sent);
        h.Clock.AdvanceMs(10);
        h.Engine.Flush(h.Clock.NowUs); // newest value wins
        Assert.Equal(2, h.Sender.Sent.Count);
        Assert.Equal(PacketType.Rumble, phone.TryOpen(h.Sender.Sent[1].Data, out p));
        Assert.Equal(new Rumble(0, 0), WireCodec.DecodeRumble(p));
    }
}
