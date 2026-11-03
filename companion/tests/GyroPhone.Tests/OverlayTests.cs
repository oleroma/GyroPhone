using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>OVERLAY / OVERLAY_LAYOUT: the same bytes the phone's OverlayFeedTest encodes.</summary>
public class OverlayTests
{
    private const string LayoutBlob = "0160093804044d61696e000100010008001033036606800141";
    private const string Frame = "040302010000803f000000bf0000803ee803000000200000000000000180030101003b010080004000000000000000000000000000000000" +
        "0100000000000000000000000000000000000000000000000000000000000000007fc1ff0000ff0000ff0000ff0000ff0000ff0000ff0000" +
        "0000803f00000000000000c0" + "000000400000000000000000" + "00000000000000000000000000000000";

    private static byte[] Piece(ushort version, int index, int count, ReadOnlySpan<byte> data)
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
    public void MalformedLayoutIsDropped()
    {
        var a = new OverlayLayoutAssembler();
        Assert.False(a.Add(Piece(1, 0, 1, Hex.B(LayoutBlob)[..20])));
        Assert.Null(a.Layout);
        Assert.False(a.Add(Piece(1, 2, 1, Hex.B(LayoutBlob)))); // index past the count
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
        Assert.Equal(-1, h.Engine.OverlaySnapshot(frame, out _));

        h.Sender.Clear();
        h.Engine.SetOverlay(true);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.Overlay);

        h.Send(phone, phone.Sealed(PacketType.Overlay, Hex.B(Frame)));
        h.Send(phone, phone.Sealed(PacketType.OverlayLayout, Piece(1, 0, 1, Hex.B(LayoutBlob))));
        Assert.True(h.Engine.OverlaySnapshot(frame, out var layout) >= 0);
        Assert.True(frame.IsPressed(0));
        Assert.Equal("Main", layout!.Workspace);

        h.Sender.Clear();
        h.Engine.SetOverlay(false);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out st));
        Assert.Equal(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.Overlay);
        Assert.Equal(-1, h.Engine.OverlaySnapshot(frame, out layout));
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

    [Fact]
    public void PoseTurnsWithTheGyroAndResetsOnARatchetPress()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = false };
        var p = new GyroPhone.Host.OverlayPose();
        for (int i = 0; i < 10; i++) p.Feed(Motion(3f, 0.01f), s); // 30° right
        Assert.Equal(30f, HeadingDeg(p), 1);
        // The press counter changing is a press.
        p.Feed(Motion(0f, 0.01f, presses: 1), s);
        Assert.Equal(1f, p.W, 4);
        s.ResetOnRatchet = false;
        p.Feed(Motion(10f, 0.01f, presses: 1), s);
        p.Feed(Motion(0f, 0.01f, presses: 2), s);
        Assert.Equal(10f, HeadingDeg(p), 1);
    }

    [Fact]
    public void PoseEasesBackAfterTheDelay()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = false, Ease = true, EaseDelayMs = 100, EaseSeconds = 0.5f };
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
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = true, Ease = true, EaseDelayMs = 0, EaseSeconds = 0.2f };
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
        h.Engine.UpdateOverlaySettings(id, o => o.Shown = true);
        h.Engine.SetOverlay(true);
        h.Sender.Clear();
        h.Engine.UpdateOverlaySettings(id, o => o.Compass = true);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.OverlayCompass);
        h.Sender.Clear();
        h.Engine.UpdateOverlaySettings(id, o => o.Gravity = false);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out st));
        Assert.Equal(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.OverlayCompass);
    }

    [Fact]
    public void TrackballTurnsAgainstTheGyroAndWithOtherMoves()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Kind = GyroPhone.Host.Settings.OverlayKind.Shape, Trackball = true, TrackballRws = false, TrackballSpin = 2f };
        var p = new GyroPhone.Host.OverlayPose();
        // The gyro turns 10° right while moving the mouse: the ball turns 20° the other way.
        p.Feed(Motion(10f, 0.01f), s);
        Assert.Equal(-20f, HeadingDeg(p), 1);
        // Touchpad counts worth a 10° yaw turn turn it as that gyro turn would.
        var f = Motion(0f, 0.01f);
        f.CameraYaw = 10f;
        p.Feed(f, s);
        Assert.Equal(-40f, HeadingDeg(p), 1);
    }

    [Fact]
    public void EachShownOverlayHasItsOwnPose()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State());
        var a = h.Engine.Settings.Overlays[0].Id;
        h.Engine.UpdateOverlaySettings(a, o => { o.Shown = true; o.Gravity = false; o.Ease = false; });
        var b = h.Engine.AddOverlay(GyroPhone.Host.Settings.OverlayKind.Shape);
        h.Engine.UpdateOverlaySettings(b, o => { o.Trackball = true; o.TrackballRws = false; });
        h.Engine.SetOverlay(true);
        var frame = Hex.B(Frame); // 1° yaw, -0.5° pitch, 0.25° roll; axes yaw + pitch
        h.Send(phone, phone.Sealed(PacketType.Overlay, frame));
        var fa = new OverlayFrame();
        var fb = new OverlayFrame();
        Assert.True(h.Engine.OverlaySnapshot(fa, a, out _) >= 0);
        h.Engine.OverlaySnapshot(fb, b, out _);
        Assert.NotEqual(1f, fa.Qw);
        // Opposite turns: the trackball holds still against the world, and the frame's 1° of
        // touchpad yaw turns it as far again.
        Assert.Equal(-2 * fa.Qy, fb.Qy, 3);
        h.Engine.RemoveOverlay(b);
        Assert.Single(h.Engine.Settings.Overlays);
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
    public void ResetOnRatchetWithGravityShowsTheLevelGripFaceOnEvenLyingFlat()
    {
        var s = new GyroPhone.Host.Settings.OverlaySettings { Gravity = true, Ease = false };
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
        p.Feed(Flat(0f, 1), s);
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
        h.Engine.SetOverlayShortcut(GyroPhone.Host.Settings.OverlayAction.Edit, "Ctrl+Alt+R");
        Assert.Equal("Ctrl+Alt+R", keys.Edit);
        Assert.Equal("", keys.LevelAll); // one press never does two things
        h.Engine.SetOverlaysHidden(true);
        Assert.True(h.Engine.Settings.OverlaysHidden);
    }

    private static float Angle((float W, float X, float Y, float Z) q) =>
        2f * MathF.Acos(Math.Min(MathF.Abs(q.W), 1f)) * 180f / MathF.PI;
}
