using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>OVERLAY / OVERLAY_LAYOUT: the same bytes the phone's OverlayFeedTest encodes.</summary>
public class OverlayTests
{
    internal const string LayoutBlob = "0160093804044d61696e000100010008001033036606800141";
    internal const string FramesBlob = "01010059011301d4bc00ff02000502000157010943726f756368206f6e010000800a00ff00010004486f6c6400";
    internal const string Frame = "040302010000803f000000bf0000803ee803000000200000000000000180030101003b010080004000000000000000000000000000000000" +
        "0100000000000000000000000000000000000000000000000000000000000000007fc1ff0000ff0000ff0000ff0000ff0000ff0000ff0000" +
        "0000803f00000000000000c0" + "000000400000000000000000" + "00000000000000000000000000000000";

    internal static byte[] Piece(ushort version, int index, int count, ReadOnlySpan<byte> data)
    {
        var p = new byte[Wire.OverlayLayoutPlain];
        Le.PutU16(p, 0, version);
        p[2] = (byte)index;
        p[3] = (byte)count;
        p[4] = (byte)data.Length;
        data.CopyTo(p.AsSpan(5));
        return p;
    }

    [Fact]
    public void Sizes()
    {
        Assert.Equal(180, PacketType.Size(PacketType.Overlay));
        Assert.Equal(228, PacketType.Size(PacketType.OverlayLayout));
        Assert.True(PacketType.IsPhoneToHostSealed(PacketType.OverlayLayout));
    }

    [Fact]
    public void DecodesTheController()
    {
        var b = new byte[Wire.OverlayPlain];
        var f = new OverlayFrame();
        f.Decode(b);
        Assert.False(f.PadAttached);
        b[136] = OverlayFrame.PadAttachedFlag;
        b[137] = 255;
        b[138] = 51;
        Le.PutU32(b, 140, OverlayFrame.PadSouth | OverlayFrame.PadL2 | OverlayFrame.PadLeft);
        b[144] = 127;
        b[145] = unchecked((byte)-127);
        b[146] = unchecked((byte)-128);
        f.Decode(b);
        Assert.True(f.PadAttached);
        Assert.Equal(1f, f.PadL2Pull, 3);
        Assert.Equal(0.2f, f.PadR2Pull, 3);
        Assert.True(f.PadHeld(OverlayFrame.PadSouth));
        Assert.True(f.PadHeld(OverlayFrame.PadLeft));
        Assert.False(f.PadHeld(OverlayFrame.PadEast));
        Assert.Equal(1f, f.PadSticks[0], 3);
        Assert.Equal(-1f, f.PadSticks[1], 3);
        Assert.Equal(-1f, f.PadSticks[2], 3); // -128 is held at -1
        var copy = new OverlayFrame();
        f.CopyTo(copy);
        Assert.Equal(f.PadButtons, copy.PadButtons);
        Assert.Equal(f.PadSticks, copy.PadSticks);
    }

    [Fact]
    public void DecodesTheFrame()
    {
        var f = new OverlayFrame();
        f.Decode(Hex.B(Frame));
        Assert.Equal(0x01020304u, f.PhoneTimeUs);
        Assert.Equal(1f, f.TurnYaw, 4);
        Assert.Equal(-0.5f, f.TurnPitch, 4);
        Assert.Equal(0.25f, f.TurnRoll, 4);
        Assert.Equal(0.01f, f.TurnSeconds, 5);
        Assert.Equal(0f, f.UpX, 3);
        Assert.Equal(1f, f.UpY, 3);
        Assert.Equal(-1f, f.NorthZ, 3);
        Assert.Equal(3, f.Axes);
        Assert.Equal(1, f.RatchetPresses);
        Assert.Equal(1, f.LayoutVersion);
        Assert.True(f.PoseLive);
        Assert.True(f.PadShown);
        Assert.False(f.GyroActive);
        Assert.True(f.HasUp);
        Assert.True(f.HasNorth);
        Assert.True(f.RatchetHeld);
        Assert.Equal(1f, f.CameraYaw, 4);
        Assert.Equal(-2f, f.CameraRoll, 4);
        Assert.Equal(2f, f.RwsYaw, 4);
        Assert.Equal(0f, f.RwsPitch, 4);
        Assert.Equal(1, f.FingerCount);
        Assert.Equal(0.5f, f.Fingers[0], 3);
        Assert.Equal(0.25f, f.Fingers[1], 3);
        Assert.True(f.IsPressed(0));
        Assert.False(f.IsPressed(1));
        Assert.False(f.IsLatched(0));
        Assert.Equal(1, f.StickCount);
        Assert.Equal(0, f.StickIndex[0]);
        Assert.Equal(1f, f.StickXY[0], 3);
        Assert.Equal(-63 / 127f, f.StickXY[1], 3);
    }

    [Fact]
    public void DecodesTheLayout()
    {
        var a = new OverlayLayoutAssembler();
        Assert.True(a.Add(Piece(7, 0, 1, Hex.B(LayoutBlob))));
        var l = a.Layout!;
        Assert.Equal(7, l.Version);
        Assert.Equal(2400, l.WidthPx);
        Assert.Equal(1080, l.HeightPx);
        Assert.Equal("Main", l.Workspace);
        Assert.Equal("", l.Layer);
        var c = Assert.Single(l.Controls);
        Assert.Equal(OverlayControlKind.Button, c.Kind);
        Assert.True(c.Round);
        Assert.Equal(0.25f, c.X, 3);
        Assert.Equal(0.5f, c.Y, 3);
        Assert.Equal(0.1f, c.W, 3);
        Assert.Equal(0.2f, c.H, 3);
        Assert.Equal(128 / 255f, c.Opacity, 3);
        Assert.Equal("A", c.Label);
        // The same version again changes nothing.
        Assert.False(a.Add(Piece(7, 0, 1, Hex.B(LayoutBlob))));
    }

    [Fact]
    public void DecodesTheFrames()
    {
        Assert.Equal(228, PacketType.Size(PacketType.OverlayFrames));
        Assert.True(PacketType.IsPhoneToHostSealed(PacketType.OverlayFrames));
        var a = new OverlayMarksAssembler();
        Assert.True(a.Add(Piece(4, 0, 1, Hex.B(FramesBlob))));
        var m = a.Marks!;
        Assert.Equal(4, m.Version);
        Assert.Equal(1, m.LayoutVersion);
        Assert.Equal(34.5f, m.TextPx, 3);
        Assert.Equal(2.75f, m.Dp, 3);
        Assert.Equal(unchecked((int)0xFF00BCD4), m.BarColor);
        Assert.Equal(2, m.Marks.Count);
        var b = m.Marks[0];
        Assert.Equal(0, b.Control);
        Assert.True(b.Inside);
        Assert.False(b.Row);
        Assert.True(b.Set);
        Assert.Equal(["W", "Crouch on"], b.Lines.Select(l => l.Text));
        Assert.Equal(OverlayFrameLine.On, b.Lines[1].Color);
        var bar = Assert.Single(b.Bars);
        Assert.Equal(0, bar.Axis);
        Assert.Equal(0.5f, bar.Value, 3);
        Assert.Equal(10, bar.Steps);
        var pad = m.Marks[1];
        Assert.Equal(-1, pad.Control);
        Assert.Equal("Hold", Assert.Single(pad.Lines).Text);
        Assert.Empty(pad.Bars);
        Assert.False(a.Add(Piece(4, 0, 1, Hex.B(FramesBlob))));
        // Cut short: dropped.
        Assert.Null(OverlayMarks.Decode(5, Hex.B(FramesBlob).AsSpan(0, 30)));
        // Frames without keys (no Keys overlay open, or an older phone).
        Assert.Null(OverlayMarks.Decode(5, Hex.B(FramesBlob))!.Keys);
    }

    /// <summary>The phone's OverlayKeysTest.KEYS_BLOB: layout 7, no marks, then the Keys overlay's table.</summary>
    private const string KeysBlob = "0107000000640000000000004bfa001af2d987871af2d9878780f24de60b000001410553706163654300074c53206c65667401414100084c5320726967687401444000054c5320757001574200074c5320646f776e0153610009525320782061786973054d6f757365610009525320792061786973054d6f7573650700025254074d6f757365204c01030c42206c6f6e67207072657373014502800158014300000141074d6f757365205200";

    [Fact]
    public void KeysFollowTheMarks()
    {
        var m = OverlayMarks.Decode(9, Hex.B(KeysBlob))!;
        Assert.Empty(m.Marks);
        var t = m.Keys!;
        Assert.Equal(250, t.TapMs);
        Assert.Equal(11, t.Keys.Count);
        Assert.Equal(new OverlayKey(0, OverlayKey.Held, "A", "Space"), t.Keys[0]);
        Assert.Equal(new OverlayKey(OverlayKey.Way | 3, OverlayKey.Held, "LS left", "A"), t.Keys[1]);
        Assert.Equal(new OverlayKey(2, OverlayKey.Toggle, "X", "C"), t.Keys[9]);
        Assert.Equal(0.3f, t.Triggers[1].Press, 2);
        Assert.Equal(0.1f, t.Sticks[0].Inner, 2);
        Assert.Equal(135f, t.Sticks[1].PieY);
        // Cut short in the keys: the whole blob is dropped.
        Assert.Null(OverlayMarks.Decode(9, Hex.B(KeysBlob).AsSpan(0, 40)));
    }

    [Fact]
    public void KeysReaderWorksOutWhatFires()
    {
        var t = OverlayMarks.Decode(9, Hex.B(KeysBlob))!.Keys!;
        var r = new OverlayKeysReader();
        var f = new OverlayFrame();
        // Nothing pressed: only the always-shown sticks (LS and RS by default), not lit.
        var idle = r.Read(t, f, 1000)!;
        Assert.Equal(
            [
                new OverlayKeyStick(0, "", ["W", "D", "S", "A"], 0, ["", "", "", "", ""], 0),
                new OverlayKeyStick(1, "Mouse", ["", "", "", ""], 0, ["", "", "", "", ""], 0),
            ],
            idle.Shown.Select(x => x.Stick));
        Assert.All(idle.Shown, x => Assert.False(x.Lit));
        Assert.Empty(idle.Rows);
        Assert.Same(idle, r.Read(t, f, 1010));
        // Nothing changed and nothing waits on time: read again without making anything.
        long before = GC.GetAllocatedBytesForCurrentThread();
        Assert.Same(idle, r.Read(t, f, 1020));
        Assert.Equal(0, GC.GetAllocatedBytesForCurrentThread() - before);
        // A held (both its mappings), the left stick up and right (a diagonal), RT pulled, X pressed (Toggle on), B held.
        f.PadButtons = OverlayFrame.PadSouth | OverlayFrame.PadR2 | OverlayFrame.PadWest | OverlayFrame.PadEast;
        f.PadR2Pull = 0.6f;
        f.PadSticks[0] = 0.7f; f.PadSticks[1] = -0.7f;
        var k = r.Read(t, f, 1100)!;
        Assert.Equal(OverlayKeyStick.Up | OverlayKeyStick.Right, k.Shown[0].Stick!.Lit);
        Assert.Equal(
            [
                new OverlayKeyRow("A", "Space + Mouse R", 0),
                new OverlayKeyRow("RT", "Mouse L", OverlayKeyRow.RightTrigger),
                new OverlayKeyRow("X", "C on", OverlayKeyRow.Toggle),
            ],
            k.Rows);
        // B held past the tap time: its long press fires.
        Assert.Contains(new OverlayKeyRow("B long press", "E", 0), r.Read(t, f, 1400)!.Rows);
        // All let go: X's Toggle stays on until it is pressed again.
        f.PadButtons = 0; f.PadR2Pull = 0; f.PadSticks[0] = 0; f.PadSticks[1] = 0;
        Assert.Equal([new OverlayKeyRow("X", "C on", OverlayKeyRow.Toggle)], r.Read(t, f, 1500)!.Rows);
        f.PadButtons = OverlayFrame.PadWest;
        Assert.Empty(r.Read(t, f, 1600)!.Rows);
        // A button mapped twice to the same key (by its key and its axis, as the D-pad): shown once.
        var twice = t with { Keys = [new OverlayKey(14, 0, "↑", "Space"), new OverlayKey(14, 0, "↑", "Space")] };
        var r2 = new OverlayKeysReader();
        f.PadButtons = OverlayFrame.PadUp;
        Assert.Equal([new OverlayKeyRow("↑", "Space", 0)], r2.Read(twice, f, 1650)!.Rows);
        // Touch buttons: by the OVERLAY's pressed and latched bits, without a name.
        var touched = t with { Keys = [], Touch = [new OverlayTouchKey(2, 0, "Mouse L"), new OverlayTouchKey(5, OverlayKey.Toggle, "Space")] };
        var r3 = new OverlayKeysReader();
        var tf = new OverlayFrame();
        tf.Pressed[0] = 1 << 2;
        tf.Latched[0] = 1 << 5;
        var rows = r3.Read(touched, tf, 1000)!.Rows;
        Assert.Equal(2, rows.Count);
        Assert.Equal(("Mouse L", OverlayKeyRow.Touch), (rows[0].Target, rows[0].Flags));
        Assert.Equal(("Space on", OverlayKeyRow.Touch | OverlayKeyRow.Toggle), (rows[1].Target, rows[1].Flags));
        // A stick pushed only a little: under half its travel, no key.
        f.PadButtons = 0; f.PadSticks[0] = 0.3f;
        Assert.Equal(0, r.Read(t, f, 1700)!.Shown[0].Stick!.Lit);
        // Pushed to the edge, up: its Outer up and its key both fire; an always-shown A sits on top, dim until pressed.
        var outer = t with { Keys = [.. t.Keys, new OverlayKey(OverlayKey.Way | 0, OverlayKey.Outer, "LS up outer", "Shift"), new OverlayKey(OverlayKey.AnyWay | 0, 0, "LS outer", "Ctrl")] };
        var r4 = new OverlayKeysReader();
        f.PadSticks[0] = 0; f.PadSticks[1] = -1f;
        int shown = OverlayControls.Mask(["ls", "a"]);
        var v = r4.Read(outer, f, 2000, shown)!;
        var ls = v.Shown[0].Stick!;
        Assert.Equal(OverlayKeyStick.Up, ls.Lit);
        Assert.Equal(OverlayKeyStick.Up | OverlayKeyStick.Any, ls.OuterLit);
        Assert.Equal(["Shift", "", "", "", "Ctrl"], ls.Outer);
        Assert.Equal(new OverlayKeyRow("A", "Space + Mouse R", 0) { Lit = false }, v.Shown[1]);
        // RS isn't always shown here: its row comes only while it moves (it sends the mouse).
        Assert.DoesNotContain(v.Rows, x => x.Name == "RS");
        f.PadSticks[2] = 0.5f;
        Assert.Contains(r4.Read(outer, f, 2010, shown)!.Rows, x => x.Name == "RS" && x.Stick != null);
    }

    [Fact]
    public void PiecesInAnyOrderAndANewVersionStartsOver()
    {
        var blob = Hex.B(LayoutBlob);
        var a = new OverlayLayoutAssembler();
        Assert.False(a.Add(Piece(2, 1, 2, blob.AsSpan(10))));
        // A newer version's piece drops the half-done older one.
        Assert.False(a.Add(Piece(3, 1, 2, blob.AsSpan(10))));
        Assert.True(a.Add(Piece(3, 0, 2, blob.AsSpan(0, 10))));
        Assert.Equal(3, a.Layout!.Version);
        Assert.Equal("A", a.Layout.Controls[0].Label);
    }

    [Fact]
    public void RenewTakesTheSameVersionAgain()
    {
        var a = new OverlayLayoutAssembler();
        Assert.True(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob))));
        Assert.False(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob))));
        // A restarted app counts from 1 again: the old layout shows until its first one is in.
        a.Renew();
        Assert.NotNull(a.Layout);
        Assert.True(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob))));
        Assert.False(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob))));
    }

    [Fact]
    public void MalformedLayoutIsDropped()
    {
        var a = new OverlayLayoutAssembler();
        Assert.False(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob)[..20])));
        Assert.Null(a.Layout);
        Assert.False(a.Add(Piece(1, 2, 1, Hex.B(LayoutBlob)))); // index past the count
    }

    [Fact]
    public void AnotherPhoneDoesNotInheritTheLayout()
    {
        var h = new Harness();
        var secondId = Hex.Range(0x80, 16);
        var secondKey = Hex.Range(0xA0, 32);
        h.AddPaired(secondId, "Pixel", secondKey);
        var engine = new GyroPhone.Host.HostEngine(h.Settings, h.Pad, h.KeyMouse, h.Prompt, new PlainProtector(), h.Clock, h.Events);
        var overlays = new GyroPhone.Host.OverlayHost(h.Settings, engine.Shared, new AutoResetEvent(false), (on, c, s, k) => engine.SetOverlay(on, c, s, k), h.Clock);
        engine.OverlayWake = overlays.Signal;
        engine.AttachSender(h.Sender);
        void Send(FakePhone p, byte[] d)
        {
            engine.OnDatagram(d, p.Addr, h.Clock.NowUs);
            engine.Flush(h.Clock.NowUs);
        }
        overlays.SetOverlay(true);
        var a = h.NewPhone();
        engine.OnDatagram(a.Hello(), a.Addr, h.Clock.NowUs);
        Assert.True(a.OnWelcome(h.Sender.Sent[^1].Data));
        Send(a, a.State());
        Send(a, a.Sealed(PacketType.OverlayLayout, Piece(1, 0, 1, Hex.B(LayoutBlob))));
        Send(a, a.Sealed(PacketType.Overlay, Hex.B(Frame)));
        overlays.OverlaySnapshot(new OverlayFrame(), out var layout);
        Assert.NotNull(layout);

        var b = FakePhone.Paired(secondId, "Pixel", secondKey, "192.168.1.77", 41000);
        engine.OnDatagram(b.Hello(), b.Addr, h.Clock.NowUs);
        Assert.True(b.OnWelcome(h.Sender.Sent[^1].Data));
        Send(b, b.State());
        Assert.Equal(-1, overlays.OverlaySnapshot(new OverlayFrame(), out layout));
        Assert.Null(layout);
    }

    [Fact]
    public void SetOrientationPressOnThePhoneStartsEveryOverlayShownOver()
    {
        var h = new Harness();
        var tilted = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Shown = true, Gravity = true, Rest = [0.9f, 0.1f, 0, 0] };
        var free = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Shown = true, Gravity = false, Rest = [1, 0, 0, 0] };
        var hidden = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Shown = false, Gravity = false, Rest = [1, 0, 0, 0] };
        h.Settings.Overlays.AddRange([tilted, free, hidden]);
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        h.Overlays.SetOverlay(true);
        byte[] Frame(byte levelPresses)
        {
            var b = Hex.B(OverlayTests.Frame);
            b[139] = levelPresses;
            return b;
        }

        // The first count seen levels nothing: it may be from before.
        h.Send(phone, phone.Sealed(PacketType.Overlay, Frame(5)));
        h.Send(phone, phone.Sealed(PacketType.Overlay, Frame(5)));
        Assert.NotNull(tilted.Rest);
        Assert.NotNull(free.Rest);

        // A press: their saved grips go, the frames now come in the new base orientation.
        h.Send(phone, phone.Sealed(PacketType.Overlay, Frame(6)));
        Assert.Null(tilted.Rest);
        Assert.Null(free.Rest);
        Assert.NotNull(hidden.Rest);
    }

    [Fact]
    public void OverlayFlagAndDataOnlyWhileOn()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        var frame = new OverlayFrame();

        // Off: OVERLAY is ignored.
        h.Send(phone, phone.Sealed(PacketType.Overlay, Hex.B(Frame)));
        Assert.Equal(-1, h.Overlays.OverlaySnapshot(frame, out _));

        h.Sender.Clear();
        h.Overlays.SetOverlay(true);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.Overlay);

        h.Send(phone, phone.Sealed(PacketType.Overlay, Hex.B(Frame)));
        h.Send(phone, phone.Sealed(PacketType.OverlayLayout, Piece(1, 0, 1, Hex.B(LayoutBlob))));
        Assert.True(h.Overlays.OverlaySnapshot(frame, out var layout) >= 0);
        Assert.True(frame.IsPressed(0));
        Assert.Equal("Main", layout!.Workspace);

        h.Sender.Clear();
        h.Overlays.SetOverlay(false);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out st));
        Assert.Equal(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.Overlay);
        Assert.Equal(-1, h.Overlays.OverlaySnapshot(frame, out layout));
        Assert.Null(layout);
    }

    private static OverlayFrame Motion(float yawDeg, float seconds, byte presses = 0, bool up = false, bool held = false)
    {
        var f = new OverlayFrame
        {
            TurnYaw = yawDeg,
            TurnSeconds = seconds,
            Axes = 7,
            RatchetPresses = presses,
            UpY = 1f,
            Flags = (byte)(OverlayFrame.FlagPose | (up ? OverlayFrame.FlagUp : 0) | (held ? OverlayFrame.FlagRatchetHeld : 0)),
        };
        return f;
    }

    private static float HeadingDeg(GyroPhone.Host.OverlayPose p) => p.Heading() * 180f / MathF.PI;

    // Easing on a ratchet press runs by the PC's clock (it goes on while the gyro is paused): these sleep.
    private static void FeedFor(GyroPhone.Host.OverlayPose p, GyroPhone.Host.Settings.OverlaySettings s, OverlayFrame f, int ms)
    {
        for (int t = 0; t < ms; t += 20)
        {
            System.Threading.Thread.Sleep(20);
            p.Feed(f, s);
        }
    }

    [Fact]
    public void PoseTurnsWithTheGyroAndEasesBackOnARatchetPress()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings
        {
            Gravity = false, Ease = true, EaseOnRatchet = true, EaseDelayMs = 0, EaseBySpeed = true, EaseSpeed = 360,
        };
        var p = new GyroPhone.Host.OverlayPose();
        for (int i = 0; i < 10; i++) p.Feed(Motion(3f, 0.01f), s); // 30° right
        // Still, but no press: it stays.
        FeedFor(p, s, Motion(0f, 0.01f), 100);
        Assert.Equal(30f, HeadingDeg(p), 1);
        // The press counter changing is a press: it eases back while held, not at once.
        p.Feed(Motion(0f, 0.01f, presses: 1, held: true), s);
        Assert.True(HeadingDeg(p) > 10f);
        FeedFor(p, s, Motion(0f, 0.01f, presses: 1, held: true), 200);
        Assert.Equal(1f, p.W, 4);
        // Let go part way back: it stops there.
        for (int i = 0; i < 10; i++) p.Feed(Motion(3f, 0.01f, presses: 1), s); // 30° right
        p.Feed(Motion(0f, 0.01f, presses: 2, held: true), s);
        FeedFor(p, s, Motion(0f, 0.01f, presses: 2, held: true), 20);
        p.Feed(Motion(0f, 0.01f, presses: 2), s);
        float stopped = HeadingDeg(p);
        Assert.True(stopped > 0.5f && stopped < 30f);
        FeedFor(p, s, Motion(0f, 0.01f, presses: 2), 100);
        Assert.Equal(stopped, HeadingDeg(p), 2);
        // Easing off: a press does nothing.
        s.Ease = false;
        p.Feed(Motion(10f - stopped, 0.01f, presses: 2), s);
        p.Feed(Motion(0f, 0.01f, presses: 3, held: true), s);
        Assert.Equal(10f, HeadingDeg(p), 1);
    }

    [Fact]
    public void PoseEasesBackAfterTheDelay()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = true, EaseOnRatchet = false, EaseDelayMs = 100, EaseSeconds = 0.5f };
        var p = new GyroPhone.Host.OverlayPose();
        p.Feed(Motion(40f, 0.05f), s);
        float start = HeadingDeg(p);
        Assert.Equal(40f, start, 1);
        p.Feed(Motion(0f, 0.05f), s); // still within the delay
        Assert.Equal(40f, HeadingDeg(p), 1);
        for (int i = 0; i < 30; i++) p.Feed(Motion(0f, 0.05f), s);
        Assert.True(HeadingDeg(p) < 2f);
    }

    [Fact]
    public void GravityHoldsTiltAndEasesTheHeading()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = true, Ease = true, EaseOnRatchet = false, EaseDelayMs = 0, EaseSeconds = 0.2f };
        var p = new GyroPhone.Host.OverlayPose();
        p.Feed(Motion(30f, 0.01f, up: true), s);
        for (int i = 0; i < 100; i++) p.Feed(Motion(0f, 0.01f, up: true), s);
        // Upright (up measured straight up) and the heading back to facing the player.
        Assert.True(MathF.Abs(HeadingDeg(p)) < 2f);
        Assert.True(p.W > 0.99f);
    }

    [Fact]
    public void CompassIsAskedForOnlyWithGravity()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        var id = h.Engine.Settings.Overlays[0].Id;
        h.Overlays.UpdateOverlaySettings(id, o => o.Shown = true);
        h.Overlays.SetOverlay(true);
        h.Sender.Clear();
        h.Overlays.UpdateOverlaySettings(id, o => o.Compass = true);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.OverlayCompass);
        h.Sender.Clear();
        h.Overlays.UpdateOverlaySettings(id, o => o.Gravity = false);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out st));
        Assert.Equal(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.OverlayCompass);
    }

    [Fact]
    public void TrackballTurnsAsItsAxesSayAndWithOtherMoves()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Trackball, TrackballRws = false, TrackballSpin = 2f, TrackballAxes = 1 | (2 << 8) };
        var p = new GyroPhone.Host.OverlayPose();
        // Yaw on the up axis: the gyro turns 10° right while moving the mouse, the ball turns 20° with it.
        p.Feed(Motion(10f, 0.01f), s);
        Assert.Equal(20f, HeadingDeg(p), 1);
        // Touchpad counts worth a 10° yaw turn turn it as that gyro turn would.
        var f = Motion(0f, 0.01f);
        f.CameraYaw = 10f;
        p.Feed(f, s);
        Assert.Equal(40f, HeadingDeg(p), 1);
        // The up axis inverted: the other way.
        s.TrackballAxes = 1 | (2 << 8) | 0x80;
        var q = new GyroPhone.Host.OverlayPose();
        q.Feed(Motion(10f, 0.01f), s);
        Assert.Equal(-20f, HeadingDeg(q), 1);
    }

    [Fact]
    public void ALockedTrackballAxisNeverTurns()
    {
        float[] Run(int axes, bool own = false)
        {
            var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Trackball, TrackballRws = false, TrackballSpin = 1f, TrackballAxes = axes, TrackballOwnAxes = own };
            var p = new GyroPhone.Host.OverlayPose();
            OverlayFrame F(float pitch, float roll)
            {
                var f = Motion(0f, 0.01f);
                f.TurnPitch = pitch;
                f.TurnRoll = roll;
                return f;
            }
            p.Feed(F(0f, 90f), s); // 90° right
            p.Feed(F(30f, 0f), s); // 30° up
            p.Feed(F(0f, -90f), s); // 90° back left
            float w = p.W, x = p.X, y = p.Y, z = p.Z;
            // Its right's height, how far its back points sideways and up.
            return new[] { 2 * (x * y + w * z), -2 * (x * z + w * y), -2 * (y * z - w * x) };
        }
        // As the camera (the default, Yaw locked): straight ahead, 30° up, its right still level.
        var cam = Run(GyroPhone.Host.Settings.OverlaySettings.TrackballAsCamera);
        Assert.Equal(0f, cam[0], 3);
        Assert.Equal(0f, cam[1], 3);
        Assert.Equal(0.5f, cam[2], 3);
        // Roll set to None holds roll at 0 about the sphere's axes too.
        var none = Run(3 | (2 << 8));
        Assert.Equal(0f, none[0], 3);
        Assert.Equal(0.5f, none[2], 3);
        // About its own axes the same loop leaves it rolled 30°: the sphere's curvature.
        Assert.Equal(0.5f, MathF.Abs(Run(3 | (2 << 8), true)[0]), 3);
    }

    [Fact]
    public void TrackballAxesEaseBackEachOnItsOwn()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Trackball, TrackballRws = false, TrackballSpin = 1f };
        var e = s.EaseOf(0);
        e.On = true; e.OnRatchet = false; e.DelayMs = 0; e.Seconds = 0.2f;
        var p = new GyroPhone.Host.OverlayPose();
        OverlayFrame F(float pitch, float roll)
        {
            var f = Motion(0f, 0.01f);
            f.TurnPitch = pitch;
            f.TurnRoll = roll;
            return f;
        }
        for (int i = 0; i < 50; i++) p.Feed(F(0f, 0.6f), s); // 30° right
        for (int i = 0; i < 50; i++) p.Feed(F(0.6f, 0f), s); // 30° up
        for (int i = 0; i < 100; i++) p.Feed(F(0f, 0f), s); // still: left and right eases back, up and down stays
        float w = p.W, x = p.X, y = p.Y, z = p.Z;
        Assert.Equal(0f, 2 * (x * z + w * y), 3);
        Assert.Equal(0.5f, -2 * (y * z - w * x), 3);
    }

    [Fact]
    public void PitchWithoutItsStopGoesOverTheTopAndRightStaysRight()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Trackball, TrackballRws = false, TrackballSpin = 1f };
        s.TrackballAxes &= ~(GyroPhone.Host.Settings.OverlaySettings.TrackballStop << 8);
        var p = new GyroPhone.Host.OverlayPose();
        OverlayFrame F(float pitch, float roll)
        {
            var f = Motion(0f, 0.01f);
            f.TurnPitch = pitch;
            f.TurnRoll = roll;
            return f;
        }
        for (int i = 0; i < 100; i++) p.Feed(F(1.2f, 0f), s); // 120° up: over the top
        float BeamX() => -2 * (p.X * p.Z + p.W * p.Y);
        float BeamY() => -2 * (p.Y * p.Z - p.W * p.X);
        Assert.Equal(0.866f, BeamY(), 3);
        for (int i = 0; i < 20; i++) p.Feed(F(0f, 0.6f), s); // turn right
        Assert.True(BeamX() > 0.05f);
    }

    [Fact]
    public void CameraTurnsWithTheGyroOnAnyKindAndIgnoresGravity()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Horizon, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Camera, Gravity = true, Ease = false, TrackballRws = false, TrackballSpin = 2f };
        var p = new GyroPhone.Host.OverlayPose();
        var f = Motion(10f, 0.01f);
        f.CameraXAxis = 1; // yaw moves the mouse sideways
        f.CameraYAxis = 2;
        p.Feed(f, s);
        Assert.Equal(20f, HeadingDeg(p), 1);
        Assert.False(s.NeedsCompass);
    }

    [Fact]
    public void CameraTurnsAsTheGameCameraByTheMouseAxesAndNeverRolls()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Camera, Ease = false, TrackballRws = false, TrackballSpin = 1f };
        var p = new GyroPhone.Host.OverlayPose();
        // Older phones send no map: roll moves the mouse sideways, pitch up and down.
        var f = new OverlayFrame { TurnRoll = 30f, TurnPitch = 10f, TurnSeconds = 0.01f, Axes = 7, Flags = OverlayFrame.FlagPose };
        p.Feed(f, s);
        float w = p.W, x = p.X, y = p.Y, z = p.Z;
        // Right stays level (no roll); the back points 30° right and 10° up.
        float m3 = 2 * (x * y + w * z);
        Assert.Equal(0f, m3, 4);
        float bx = -(2 * (x * z + w * y)), by = -(2 * (y * z - w * x)), bz = -(1 - 2 * (x * x + y * y));
        Assert.Equal(30f, MathF.Atan2(bx, -bz) * 180f / MathF.PI, 2);
        Assert.Equal(10f, MathF.Asin(by) * 180f / MathF.PI, 2);
        // An inverted Y axis turns it down instead.
        f.CameraXAxis = 3;
        f.CameraYAxis = 0x82;
        f.TurnRoll = 0;
        p.Feed(f, s);
        w = p.W; x = p.X; y = p.Y; z = p.Z;
        by = -(2 * (y * z - w * x));
        Assert.Equal(0f, MathF.Asin(by) * 180f / MathF.PI, 2);
    }

    [Fact]
    public void SavedTrackballSwitchesBecomeTurnsWith()
    {
        var t = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Trackball = true };
        t.MigrateOld();
        Assert.Equal(GyroPhone.Host.Settings.OverlayTurnsWith.Trackball, t.TurnsWith);
        Assert.Null(t.Trackball);
        var c = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Trackball = true, TrackballInvert = true };
        c.MigrateOld();
        Assert.Equal(GyroPhone.Host.Settings.OverlayTurnsWith.Camera, c.TurnsWith);
        var n = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Trackball = false };
        n.MigrateOld();
        Assert.Equal(GyroPhone.Host.Settings.OverlayTurnsWith.Phone, n.TurnsWith);
    }

    [Fact]
    public void EachShownOverlayHasItsOwnPose()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        var a = h.Engine.Settings.Overlays[0].Id;
        h.Overlays.UpdateOverlaySettings(a, o => { o.Shown = true; o.Gravity = false; o.Ease = false; });
        var b = h.Overlays.AddOverlay(GyroPhone.Host.Settings.OverlayKind.Shape);
        h.Overlays.UpdateOverlaySettings(b, o => { o.TurnsWith = GyroPhone.Host.Settings.OverlayTurnsWith.Trackball; o.TrackballRws = false; o.TrackballAxes = GyroPhone.Host.Settings.OverlaySettings.TrackballAsPhone; });
        h.Overlays.SetOverlay(true);
        var frame = Hex.B(Frame); // 1° yaw, -0.5° pitch, 0.25° roll; axes yaw + pitch
        h.Send(phone, phone.Sealed(PacketType.Overlay, frame));
        var fa = new OverlayFrame();
        var fb = new OverlayFrame();
        Assert.True(h.Overlays.OverlaySnapshot(fa, a, out _) >= 0);
        h.Overlays.OverlaySnapshot(fb, b, out _);
        Assert.NotEqual(1f, fa.Qw);
        // The same way (a trackball set to turn as the phone), and the frame's 1° of
        // touchpad yaw turns it as far again.
        Assert.Equal(2 * fa.Qy, fb.Qy, 3);
        h.Overlays.RemoveOverlay(b);
        Assert.Single(h.Engine.Settings.Overlays);
    }

    /// <summary><see cref="Frame"/> as the totals form: yaw total, time total (10 us units), camera yaw total.</summary>
    private static byte[] TotalsFrame(uint timeUs, float yaw, int units, float camYaw = 0f, bool totals = true)
    {
        var b = Hex.B(Frame);
        Le.PutU32(b, 0, timeUs);
        Le.PutU32(b, 4, (uint)BitConverter.SingleToInt32Bits(yaw));
        Le.PutU32(b, 8, 0);
        Le.PutU32(b, 12, 0);
        Le.PutU16(b, 16, (ushort)(units & 0xFFFF));
        Le.PutU32(b, 112, (uint)BitConverter.SingleToInt32Bits(camYaw));
        Le.PutU32(b, 120, 0);
        if (totals) b[34] |= OverlayFrame.FlagTotals;
        return b;
    }

    private static OverlayFrame Converted(OverlayTotals t, byte[] b)
    {
        var f = new OverlayFrame();
        f.Decode(b);
        t.ToDeltas(f);
        return f;
    }

    [Fact]
    public void TotalsTurnBackIntoTheMotionSinceTheLastFrame()
    {
        var t = new OverlayTotals();
        // The first only sets the base.
        var f = Converted(t, TotalsFrame(100, 5f, 1000, camYaw: 1f));
        Assert.Equal(0f, f.TurnYaw);
        Assert.Equal(0f, f.TurnSeconds);
        Assert.Equal(0f, f.CameraYaw);
        // The frame at 200 is lost: the one at 300 carries both turns and both times.
        f = Converted(t, TotalsFrame(300, 20f, 4000, camYaw: 4f));
        Assert.Equal(15f, f.TurnYaw, 4);
        Assert.Equal(0.03f, f.TurnSeconds, 5);
        Assert.Equal(3f, f.CameraYaw, 4);
        // Across the wrap: 170 to -170 is 20 degrees on, the time from 65000 to 500 is 1036 units.
        Converted(t, TotalsFrame(400, 170f, 65000));
        f = Converted(t, TotalsFrame(500, -170f, 500));
        Assert.Equal(20f, f.TurnYaw, 3);
        Assert.Equal(0.01036f, f.TurnSeconds, 5);
        // A late frame is dropped, a per-frame one passes as it is and the next totals frame is a base again.
        Assert.True(t.IsLate(TotalsFrame(450, 0f, 0)));
        Assert.False(t.IsLate(TotalsFrame(450, 0f, 0, totals: false)));
        f = Converted(t, TotalsFrame(600, 2f, 1000, totals: false));
        Assert.Equal(2f, f.TurnYaw, 4);
        Assert.Equal(0.01f, f.TurnSeconds, 5);
        Assert.Equal(0f, Converted(t, TotalsFrame(700, 50f, 2000)).TurnYaw);
        t.Reset();
        Assert.False(t.IsLate(TotalsFrame(1, 0f, 0)));
        Assert.Equal(0f, Converted(t, TotalsFrame(1, 60f, 3000)).TurnYaw);
    }

    [Fact]
    public void ALostTotalsFrameLosesNoTurn()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        var a = h.Engine.Settings.Overlays[0].Id;
        h.Overlays.UpdateOverlaySettings(a, o => { o.Shown = true; o.Gravity = false; o.Ease = false; });
        h.Sender.Clear();
        h.Overlays.SetOverlay(true);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.OverlayTotals);
        float Turned()
        {
            var f = new OverlayFrame();
            h.Overlays.OverlaySnapshot(f, a, out _);
            return 2 * MathF.Acos(Math.Min(MathF.Abs(f.Qw), 1f)) * 180f / MathF.PI;
        }
        h.Send(phone, phone.Sealed(PacketType.Overlay, TotalsFrame(1000, 5f, 1000))); // the base
        Assert.Equal(0f, Turned(), 2);
        // 10 at 2000 is lost; 20 at 3000 brings the whole 15 degrees.
        h.Send(phone, phone.Sealed(PacketType.Overlay, TotalsFrame(3000, 20f, 3000)));
        Assert.Equal(15f, Turned(), 1);
        // The lost one coming late changes nothing.
        h.Send(phone, phone.Sealed(PacketType.Overlay, TotalsFrame(2000, 10f, 2000)));
        Assert.Equal(15f, Turned(), 1);
        // Overlay off and on again: the next frame is a base, whatever its totals.
        h.Overlays.SetOverlay(false);
        h.Overlays.SetOverlay(true);
        h.Send(phone, phone.Sealed(PacketType.Overlay, TotalsFrame(500, 90f, 5000)));
        Assert.Equal(0f, Turned(), 2);
    }

    [Fact]
    public void AddOverlayCopiesHandlesFromTheSameKind()
    {
        var h = new Harness();
        var a = h.Engine.Settings.Overlays[0].Id;
        h.Overlays.UpdateOverlaySettings(a, o =>
        {
            o.Handles = GyroPhone.Host.Settings.OverlayHandles.Always;
            o.HandleSource = GyroPhone.Host.Settings.OverlayHandleSource.Pc;
            o.Colors["handles"] = 0x11223344;
            o.Colors["body"] = 0x55667788;
        });
        var bId = h.Overlays.AddOverlay(GyroPhone.Host.Settings.OverlayKind.Phone);
        var b = h.Engine.Settings.Overlays.Find(o => o.Id == bId)!;
        Assert.Equal(GyroPhone.Host.Settings.OverlayHandles.Always, b.Handles);
        Assert.Equal(GyroPhone.Host.Settings.OverlayHandleSource.Pc, b.HandleSource);
        Assert.Equal(0x11223344, b.Color("handles"));
        Assert.False(b.Colors.ContainsKey("body"));
        var cId = h.Overlays.AddOverlay(GyroPhone.Host.Settings.OverlayKind.Shape);
        var c = h.Engine.Settings.Overlays.Find(o => o.Id == cId)!;
        Assert.Equal(GyroPhone.Host.Settings.OverlayHandles.Auto, c.Handles);
        Assert.Empty(c.Colors);
    }

    [Fact]
    public void OldSingleOverlayIsKept()
    {
        var s = new GyroPhone.Host.Settings.HostSettings { Overlay = new GyroPhone.Host.Settings.OverlaySettings { Shown = true, EaseSeconds = 3 } };
        s.Normalize();
        var o = Assert.Single(s.Overlays);
        Assert.Equal(3f, o.EaseSeconds);
        Assert.Null(s.Overlay);
        Assert.Equal(unchecked((int)0xFF2A2E35), o.Color("body"));
    }

    [Fact]
    public void LevelWithGravityKeepsOnlyTheTilt()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = true, Ease = false };
        var p = new GyroPhone.Host.OverlayPose();
        // Held tilted back about 45° (up measured between the phone's up and its screen), turned 30° right.
        var f = Motion(30f, 0.01f, up: true);
        f.UpY = 0.7071f; f.UpZ = 0.7071f;
        p.Feed(f, s);
        for (int i = 0; i < 100; i++)
        {
            var g = Motion(0f, 0.01f, up: true);
            g.UpY = 0.7071f; g.UpZ = 0.7071f;
            p.Feed(g, s);
        }
        Assert.True(MathF.Abs(HeadingDeg(p)) > 20f);
        var tilt = p.TiltOnly();
        // No heading left, the tilt kept: the turn about the phone's right edge (x) only.
        Assert.Equal(0f, tilt[2], 2);
        Assert.Equal(0f, tilt[3], 2);
        Assert.True(MathF.Abs(tilt[1]) > 0.3f);
        // The pose itself is untouched.
        Assert.True(MathF.Abs(HeadingDeg(p)) > 20f);
    }

    [Fact]
    public void EaseBackLeavesTheTurnAboutTheScreenAlone()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = false };
        var p = new GyroPhone.Host.OverlayPose();
        // Tilted back 45° (pitch), then turned 30° about the screen's axis (stored roll): the screen
        // still faces the same way, so the heading stays 0.
        var f = Motion(0f, 0.01f);
        f.TurnPitch = -45f;
        p.Feed(f, s);
        var g = Motion(0f, 0.01f);
        g.TurnRoll = 30f;
        p.Feed(g, s);
        Assert.Equal(0f, HeadingDeg(p), 1);
    }

    [Fact]
    public void EaseOnRatchetWithGravityShowsTheLevelGripFaceOnEvenLyingFlat()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings
        {
            Gravity = true, Ease = true, EaseOnRatchet = true, EaseDelayMs = 0, EaseBySpeed = true, EaseSpeed = 720,
        };
        var p = new GyroPhone.Host.OverlayPose();
        // Lying flat, screen up: gravity's up is the screen normal.
        OverlayFrame Flat(float turnDeg, byte presses)
        {
            var f = Motion(0f, 0.01f, presses, up: true);
            f.UpY = 0f; f.UpZ = 1f; f.TurnRoll = turnDeg;
            return f;
        }
        for (int i = 0; i < 100; i++) p.Feed(Flat(0f, 0), s);
        s.Rest = p.TiltOnly();
        // Turned 40° round the vertical, which here is about the screen's own axis.
        for (int i = 0; i < 10; i++) p.Feed(Flat(4f, 0), s);
        Assert.True(Angle(Shown(p, s.Rest)) > 30f);
        var held = Flat(0f, 1);
        held.Flags |= OverlayFrame.FlagRatchetHeld;
        p.Feed(held, s);
        FeedFor(p, s, held, 200);
        Assert.True(Angle(Shown(p, s.Rest)) < 2f);
    }

    [Fact]
    public void HeadingNeverJumpsAsThePhoneTiltsFlat()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = false };
        var p = new GyroPhone.Host.OverlayPose();
        p.Feed(Motion(30f, 0.01f), s);
        float last = HeadingDeg(p);
        for (int i = 0; i < 100; i++)
        {
            var f = Motion(0f, 0.01f);
            f.TurnPitch = -1f; // back to flat and a little past
            p.Feed(f, s);
            float h = HeadingDeg(p);
            Assert.True(MathF.Abs(h - last) < 5f, $"jumped from {last} to {h} at {i}°");
            last = h;
        }
    }

    [Fact]
    public void LevelWithTheTopTippedPastFlatKeepsTheGrip()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = false };
        foreach (float tip in new[] { -120f, 120f, -170f })
        {
            var p = new GyroPhone.Host.OverlayPose();
            var f = Motion(0f, 0.01f);
            f.TurnPitch = tip;
            p.Feed(f, s);
            // Only tipped: nothing to take out, so the grip is the pose itself (not turned round).
            var tilt = p.TiltOnly();
            Assert.True(Angle(Shown(p, tilt)) < 1f, $"tip {tip}");
        }
    }

    private static (float W, float X, float Y, float Z) Shown(GyroPhone.Host.OverlayPose p, float[] rest)
    {
        float rw = rest[0], rx = -rest[1], ry = -rest[2], rz = -rest[3];
        return (rw * p.W - rx * p.X - ry * p.Y - rz * p.Z,
            rw * p.X + rx * p.W + ry * p.Z - rz * p.Y,
            rw * p.Y - rx * p.Z + ry * p.W + rz * p.X,
            rw * p.Z + rx * p.Y - ry * p.X + rz * p.W);
    }

    [Fact]
    public void ShortcutsDefaultAndNeverShareKeys()
    {
        var s = new GyroPhone.Host.Settings.HostSettings { OverlayShortcuts = null!, OverlaysHidden = true };
        s.Normalize();
        Assert.Equal("Ctrl+Alt+R", s.OverlayShortcuts.LevelAll);
        Assert.Equal("Ctrl+Alt+O", s.OverlayShortcuts.HideAll);
        Assert.Equal("Ctrl+Alt+E", s.OverlayShortcuts.Edit);
        // A cleared one stays cleared.
        s.OverlayShortcuts.Edit = "";
        s.Normalize();
        Assert.Equal("", s.OverlayShortcuts.Edit);

        var h = new Harness();
        var keys = h.Engine.Settings.OverlayShortcuts;
        h.Overlays.SetOverlayShortcut(GyroPhone.Host.Settings.OverlayAction.Edit, "Ctrl+Alt+R");
        Assert.Equal("Ctrl+Alt+R", keys.Edit);
        Assert.Equal("", keys.LevelAll); // one press never does two things
        h.Overlays.SetOverlaysHidden(true);
        Assert.True(h.Engine.Settings.OverlaysHidden);
    }

    private static float Angle((float W, float X, float Y, float Z) q) =>
        2f * MathF.Acos(Math.Min(MathF.Abs(q.W), 1f)) * 180f / MathF.PI;
}
