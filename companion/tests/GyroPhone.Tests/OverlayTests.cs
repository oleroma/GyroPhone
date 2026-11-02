using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>OVERLAY / OVERLAY_LAYOUT: the same bytes the phone's OverlayFeedTest encodes.</summary>
public class OverlayTests
{
    private const string LayoutBlob = "0160093804044d61696e000100010008001033036606800141";
    private const string Frame = "04030201ff7f000000000000010003010080004000000000000000000000000000000000010000000000000000000000" +
        "0000000000000000000000000000000000000000007fc1ff0000ff0000ff0000ff0000ff0000ff0000ff000000000000";

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
        Assert.Equal(124, PacketType.Size(PacketType.Overlay));
        Assert.Equal(228, PacketType.Size(PacketType.OverlayLayout));
        Assert.True(PacketType.IsPhoneToHostSealed(PacketType.OverlayLayout));
    }

    [Fact]
    public void DecodesTheFrame()
    {
        var f = new OverlayFrame();
        f.Decode(Hex.B(Frame));
        Assert.Equal(0x01020304u, f.PhoneTimeUs);
        Assert.Equal(1f, f.Qw, 3);
        Assert.Equal(0f, f.Qx, 3);
        Assert.Equal(1, f.LayoutVersion);
        Assert.True(f.PoseLive);
        Assert.True(f.PadShown);
        Assert.False(f.GyroActive);
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
}
