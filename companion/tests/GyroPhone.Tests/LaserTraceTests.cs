using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The laser pointer overlay's strokes, as the phone's LaserTraceTest.</summary>
public class LaserTraceTests
{
    private static readonly float[] Identity = [1, 0, 0, 0, 1, 0, 0, 0, 1];

    [Fact]
    public void BeamPointsOutOfThePhonesBackIntoTheScreen()
    {
        var b = LaserTrace.Beam(Identity);
        Assert.Equal(-1f, b.Z);
        var tick = LaserTrace.RollTick(Identity, b);
        Assert.NotNull(tick);
        Assert.Equal(1f, tick.Value.Y, 5);
    }

    [Fact]
    public void ViewSeesTheFarSideAboveAndRightOfTheCentre()
    {
        var p = LaserTrace.ToView(0, 0, -1);
        Assert.True(p.X > 0 && p.Y > 0 && p.Z < 0);
        Assert.True(LaserTrace.ToView(0, 1, 0).Z > 0);
        Assert.NotEmpty(LaserTrace.Grid.Front);
        Assert.NotEmpty(LaserTrace.Grid.Back);
        foreach (var l in LaserTrace.Grid.Front.Concat(LaserTrace.Grid.Back))
            foreach (var (x, y) in l) Assert.True(x * x + y * y <= 1.0001f);
        // Axonometric of the identity is the view itself.
        Assert.Equal(LaserTrace.View, LaserTrace.Axonometric(Identity));
    }

    [Fact]
    public void BehindGoesFromAheadToTurnedRound()
    {
        Assert.Equal(0f, LaserTrace.Behind(-1));
        Assert.Equal(0.5f, LaserTrace.Behind(0));
        Assert.Equal(1f, LaserTrace.Behind(1));
    }

    [Fact]
    public void SphereTurnsKeepsTheBeamAhead()
    {
        // The phone turned 90° right: its back points to the right.
        float[] m = [0, 0, -1, 0, 1, 0, 1, 0, 0];
        var b = LaserTrace.Beam(m);
        Assert.Equal((1f, 0f, 0f), (b.X, b.Y, b.Z));
        Assert.Equal((0f, 0f, -1f), LaserTrace.ToView(b.X, b.Y, b.Z, false, m));
        Assert.Equal((-1f, 0f, 0f), LaserTrace.ToView(0, 0, -1, false, m));
        Assert.NotEmpty(LaserTrace.GridFor(false, m).Front);
    }

    [Fact]
    public void RifleShowsItsNearFacesFarthestFirst()
    {
        var faces = LaserTrace.RifleFaces(Identity, true, null);
        Assert.Equal(30, faces.Count); // 10 boxes, 3 faces of each toward the viewer
        for (int i = 1; i < faces.Count; i++) Assert.True(faces[i - 1].Depth <= faces[i].Depth);
        Assert.All(faces, f => Assert.InRange(f.Light, 0f, 1f));
    }

    [Theory]
    [InlineData(0, 0, 0, -1)]
    [InlineData(1, 0, 0, 1)]
    [InlineData(2, 1, 0, 0)]
    [InlineData(3, -1, 0, 0)]
    [InlineData(4, 0, 1, 0)]
    [InlineData(5, 0, -1, 0)]
    public void ATrackballFacesTheWayPicked(int faces, float x, float y, float z)
    {
        var b = LaserTrace.Beam(LaserTrace.Faces(Identity, faces));
        Assert.Equal((x, y, z), (b.X + 0f, b.Y + 0f, b.Z + 0f));
    }

    [Fact]
    public void AimsWithTheRollAxis()
    {
        var b = LaserTrace.Beam(LaserTrace.Aim(Identity));
        Assert.Equal((0f, 0f, -1f), (b.X, b.Y, b.Z));
        // A yaw turn (through the screen) of 90° clockwise: the top edge goes right.
        b = LaserTrace.Beam(LaserTrace.Aim([0, 1, 0, -1, 0, 0, 0, 0, 1]));
        Assert.Equal((1f, 0f, 0f), (b.X, b.Y, b.Z));
        // Pitch up 90° (the top comes toward the player): the beam points up.
        b = LaserTrace.Beam(LaserTrace.Aim([1, 0, 0, 0, 0, -1, 0, 1, 0]));
        Assert.Equal((0f, 1f, 0f), (b.X, b.Y, b.Z));
    }

    [Fact]
    public void FaceOnSeesTheBeamStraightInAsADot()
    {
        Assert.Equal((0f, 0f, -1f), LaserTrace.ToView(0, 0, -1, false));
        var g = LaserTrace.GridFor(false);
        Assert.NotEmpty(g.Front);
        Assert.NotEmpty(g.Back);
        foreach (var l in g.Front.Concat(g.Back))
            foreach (var (x, y) in l) Assert.True(x * x + y * y <= 1.0001f);
    }

    [Fact]
    public void StrokesKeepAPointOnlyWhenTheBeamMoves()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, true, false);
        t.Add(0, 0, -1, 10, true, false);
        Assert.Equal(1, t.Strokes.Count);
        t.Add(0.1f, 0, -1, 20, true, false);
        Assert.Equal(2, t.Strokes.Count);
        Assert.True(t.Strokes.Gap(0));
        Assert.False(t.Strokes.Gap(1));
    }

    [Fact]
    public void DrawOffOrClearWipesTheStrokes()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, true, false);
        t.Add(1, 0, 0, 10, true, false);
        Assert.Equal(2, t.Strokes.Count);
        t.Add(1, 0, 0, 20, false, false);
        Assert.Equal(0, t.Strokes.Count);
        t.Add(0, 0, -1, 30, true, false);
        t.Clear();
        Assert.Equal(0, t.Strokes.Count);
    }

    [Fact]
    public void HeldRatchetDoesntPaintAndBreaksTheLine()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, true, false);
        t.Add(1, 0, 0, 5, true, true);
        Assert.Equal(1, t.Strokes.Count); // kept without Clear on ratchet
        t.Add(1, 0, 0, 6, true, false);
        t.Add(1, 0, 0, 10, true, true, true);
        Assert.Equal(0, t.Strokes.Count); // a ratchet press clears the drawing
        t.Add(1, 0, 0, 20, true, false);
        Assert.Equal(1, t.Strokes.Count);
        Assert.True(t.Strokes.Gap(0));
    }

    [Fact]
    public void StrokesFade()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, true, false);
        Assert.Equal(1f, LaserTrace.StrokeLife(0, 1, 0, 0, 5000));
        Assert.Equal(0.5f, LaserTrace.StrokeLife(0, 1, 0, 2500, 5000), 3);
        t.Expire(600, 5000);
        Assert.True(t.Fading(5000));
        t.Expire(5001, 5000);
        Assert.Equal(0, t.Strokes.Count);
        Assert.False(t.Fading(5000));
        // Without fading, older points are darker by their place.
        Assert.Equal(0f, LaserTrace.StrokeLife(0, 3, 0, 0, 0));
        Assert.Equal(1f, LaserTrace.StrokeLife(2, 3, 0, 0, 0));
    }

    [Fact]
    public void RingDropsTheOldestWhenFull()
    {
        var r = new LaserTrace.Ring(3);
        for (int i = 0; i < 5; i++) r.Add(i, 0, 0, i, false);
        Assert.Equal(3, r.Count);
        Assert.Equal(2f, r.Point(0).X);
        Assert.True(r.Gap(0));
    }

    [Fact]
    public void OverlayCarriesClearDisplaysPressesInByte148()
    {
        var b = new byte[Wire.OverlayPlain];
        b[148] = 7;
        var f = new OverlayFrame();
        f.Decode(b);
        Assert.Equal(7, f.ClearPresses);
        var copy = new OverlayFrame();
        f.CopyTo(copy);
        Assert.Equal(7, copy.ClearPresses);
    }

    [Fact]
    public void ACrosshairLiesOnTheSphereWhereTheBeamLands()
    {
        const float rad = MathF.PI / 180f;
        // Straight (beam into the screen), the phone rolled 90°: its up now points sideways.
        float[] m = [0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f];
        var b = LaserTrace.Beam(m);
        {
            var ch = LaserTrace.CrosshairOn(m, b, 0, 10 * rad, 2 * rad, true);
            Assert.Equal(2, ch.Lines.Count);
            foreach (var l in ch.Lines)
                foreach (var p in l)
                    Assert.Equal(1f, p.X * b.X + p.Y * b.Y + p.Z * b.Z, 4);
            var up = ch.Lines[1];
            float dx = up[1].X - up[0].X, dy = up[1].Y - up[0].Y;
            Assert.Equal(0f, dy, 4);
            Assert.Equal(20 * rad, MathF.Abs(dx), 4);
            Assert.NotNull(ch.Dot);
        }
        Assert.Equal(3, LaserTrace.CrosshairOn(m, b, 5, 0.2f, 0.05f, false).Lines.Count);
        var dot = LaserTrace.CrosshairOn(m, b, 4, 0.2f, 0.05f, false);
        Assert.Empty(dot.Lines);
        Assert.NotNull(dot.Dot);
    }

    [Fact]
    public void FireIsHeldWhileItsOutputIsSentOrPressedOnThePcController()
    {
        var s = new StateFrame();
        Assert.False(LaserFire.Held(LaserFire.Default, s));
        s.MouseButtons = 1;
        Assert.True(LaserFire.Held(LaserFire.Default, s));
        s.SetKey(0x2C, true);
        Assert.True(LaserFire.Held(LaserFire.Key + 0x2C, s));
        s.Buttons = 32;
        Assert.True(LaserFire.Held(LaserFire.Pad + 5, s));
        Assert.False(LaserFire.Held(LaserFire.Pad + 4, s));
        // The PC's controller: LB by place, RT pulled.
        Assert.True(LaserFire.Held(LaserFire.Pad + 4, s, OverlayFrame.PadL1));
        Assert.True(LaserFire.Held(LaserFire.Rt, s, 0, 0f, 0.8f));
        Assert.False(LaserFire.Held(LaserFire.Lt, s, 0, 0f, 0.8f));
    }

    [Fact]
    public void FiringPaintsInItsOwnColoursAndCanBeTheOnlyPaint()
    {
        var t = new LaserTrace();
        void Aim(int deg, bool fire, bool only = false)
        {
            float a = deg * MathF.PI / 180f;
            t.Add(MathF.Sin(a), 0f, -MathF.Cos(a), deg, true, false, fire: fire, fireOnly: only);
        }
        Aim(0, false); Aim(2, false); Aim(4, true); Aim(6, true);
        Assert.Equal(4, t.Strokes.Count);
        Assert.False(t.Strokes.Fire(1));
        Assert.True(t.Strokes.Fire(2));
        Aim(8, false, true); Aim(10, false, true);
        Assert.Equal(4, t.Strokes.Count);
        Aim(12, true, true);
        Assert.Equal(5, t.Strokes.Count);
        Assert.True(t.Strokes.Gap(4));
    }

    private static float[] GimbalPose(float a, float b, float c)
    {
        static float[] Mul(float[] x, float[] y)
        {
            var r = new float[9];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) r[i * 3 + j] = x[i * 3] * y[j] + x[i * 3 + 1] * y[3 + j] + x[i * 3 + 2] * y[6 + j];
            return r;
        }
        float[] ry = [MathF.Cos(a), 0f, MathF.Sin(a), 0f, 1f, 0f, -MathF.Sin(a), 0f, MathF.Cos(a)];
        float[] rx = [1f, 0f, 0f, 0f, MathF.Cos(b), -MathF.Sin(b), 0f, MathF.Sin(b), MathF.Cos(b)];
        float[] rz = [MathF.Cos(c), -MathF.Sin(c), 0f, MathF.Sin(c), MathF.Cos(c), 0f, 0f, 0f, 1f];
        return Mul(Mul(ry, rx), rz);
    }

    [Fact]
    public void GimbalAnglesReadBackAPose()
    {
        var g = LaserTrace.GimbalAngles(GimbalPose(0.5f, -0.3f, 0.2f), 0);
        Assert.Equal(0.5f, g[0], 4);
        Assert.Equal(-0.3f, g[1], 4);
        Assert.Equal(0.2f, g[2], 4);
        // Over the top with roll locked: read the way that keeps roll at 0.
        g = LaserTrace.GimbalAngles(GimbalPose(0.4f, 2.2f, 0f), 4);
        Assert.Equal(0f, g[2], 4);
        Assert.Equal(2.2f, g[1], 4);
        Assert.Equal(0.4f, g[0], 4);
    }

    [Fact]
    public void GimbalRingsNestAsAGimbal()
    {
        float[] angles = [0.7f, 0.4f, 0.3f];
        var f = GimbalPose(0.7f, 0.4f, 0f);
        var rings = new[] { LaserTrace.GimbalRing(0, angles), LaserTrace.GimbalRing(1, angles), LaserTrace.GimbalRing(2, angles) };
        for (int i = 0; i < 3; i++)
            foreach (var p in rings[i])
                Assert.Equal(LaserTrace.GimbalRadii[i], MathF.Sqrt(p.X * p.X + p.Y * p.Y + p.Z * p.Z), 4);
        Assert.Equal(0f, rings[0][0].Y, 4);
        Assert.Equal(LaserTrace.GimbalRadii[1] * f[0], rings[1][0].X, 4);
        Assert.Equal(LaserTrace.GimbalRadii[1] * f[3], rings[1][0].Y, 4);
        Assert.Equal(LaserTrace.GimbalRadii[2] * f[2], rings[2][16].X, 4);
        Assert.Equal(LaserTrace.GimbalRadii[2] * f[8], rings[2][16].Z, 4);
    }
}
