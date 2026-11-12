using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>The pointer's paint map, as the phone's LaserPaintTest.</summary>
public class LaserPaintTests
{
    // The direction deg degrees right of straight ahead along the equator, up degrees up.
    private static (float X, float Y, float Z) At(float deg, float up = 0f) => LaserTrace.Dir(up * MathF.PI / 180f, deg * MathF.PI / 180f);

    private static int K(LaserPaint p, (float X, float Y, float Z) d) => p.TexelOf(d.X, d.Y, d.Z);
    private static float Amount(LaserPaint p, (float X, float Y, float Z) d) => p.Amount[K(p, d)] / 255f;
    private static float Fire(LaserPaint p, (float X, float Y, float Z) d) => p.Fire[K(p, d)] / 255f;

    // A line along the equator in 0.5° steps, as the trace stamps it.
    private static void Line(LaserPaint p, float from, float to, float up = 0f, float soft = 0.5f, bool fired = false, int? way = null, int? kind = null)
    {
        p.NewLine();
        double along = 0;
        for (float x = from; x < to; x += 0.5f)
        {
            var a = At(x, up);
            var b = At(x + 0.5f, up);
            along += 0.5;
            p.Stamp(a.X, a.Y, a.Z, b.X, b.Y, b.Z, along, 1f, kind ?? (fired ? LaserPaint.FireKind : LaserPaint.PaintKind), soft, way);
        }
    }

    [Fact]
    public void AimPaintAndGlowAreKeptApartFromFire()
    {
        var p = new LaserPaint();
        Line(p, 0f, 20f, kind: LaserPaint.AimKind);
        var a = At(10f);
        int t = p.TexelOf(a.X, a.Y, a.Z);
        Assert.InRange(p.Aim[t], 250, 255);
        Assert.InRange(p.Fire[t], 0, 5);
        var e = At(10f, 2f);
        int g = p.TexelOf(e.X, e.Y, e.Z);
        Assert.Equal(p.Glow[g], p.GlowAim[g]); // all of the glow there is aiming's
        Assert.Equal(0, p.GlowFire[g]);
        Line(p, 0f, 20f, fired: true); // a shot over it takes its place
        Assert.InRange(p.Aim[t], 0, 5);
        Assert.InRange(p.Fire[t], 250, 255);
        Assert.True(p.GlowFire[g] > p.GlowAim[g]);
    }

    [Fact]
    public void ALineIsEvenAlongItsLength()
    {
        var p = new LaserPaint();
        Line(p, 0f, 20f);
        Assert.Equal(1f, Amount(p, At(10f)), 0.02f);
        Assert.Equal(Amount(p, At(8f, 0.9f)), Amount(p, At(12f, 0.9f)), 0.03f);
        Assert.True(Amount(p, At(10f, 0.9f)) < 0.9f);
        Assert.True(Amount(p, At(10f, 2.5f)) < 0.01f);
        Assert.True(p.Glow[K(p, At(10f, 2f))] > 0);
    }

    [Fact]
    public void CrossingLinesBuildUp()
    {
        var p = new LaserPaint();
        Line(p, 0f, 20f, up: 0.9f);
        float before = Amount(p, At(10f));
        Line(p, 0f, 20f, up: -0.9f);
        float after = Amount(p, At(10f));
        var alone = new LaserPaint();
        Line(alone, 0f, 20f, up: -0.9f);
        float a = Amount(alone, At(10f));
        Assert.True(after > before + 0.05f);
        Assert.Equal(before + a * (1 - before), after, 0.03f);
    }

    [Fact]
    public void FirePaintBlendsUnderNewPaint()
    {
        var p = new LaserPaint();
        Line(p, 0f, 20f, fired: true);
        Assert.Equal(1f, Fire(p, At(10f)), 0.02f);
        Line(p, 0f, 20f);
        Assert.Equal(0f, Fire(p, At(10f)), 0.02f);
    }

    [Fact]
    public void KeepsWayColours()
    {
        var p = new LaserPaint();
        Line(p, 0f, 20f, way: 0x804020);
        Assert.NotNull(p.Way);
        Assert.Equal(0x804020, p.Way![K(p, At(10f))] & 0xFFFFFF);
    }

    [Fact]
    public void TraceStampsItsStrokes()
    {
        var t = new LaserTrace();
        t.Add(0f, 0f, -1f, 0, true, false, brushDeg: 4f);
        var e = At(10f);
        t.Add(e.X, e.Y, e.Z, 10, true, false, brushDeg: 4f);
        var p = t.Paint!;
        Assert.True(Amount(p, At(5f)) > 0.9f);
        t.Clear();
        Assert.Equal(0f, Amount(p, At(5f)));
    }

    [Fact]
    public void SnakeKeepsOnlyItsLength()
    {
        var t = new LaserTrace();
        for (int i = 0; i <= 30; i++)
        {
            var d = At(i);
            t.Add(d.X, d.Y, d.Z, i, false, false, snakeDeg: 10f);
        }
        Assert.Null(t.Paint);
        Assert.Equal(10f, t.Strokes.BehindNewest(0), 0.6f);
        t.Add(0f, 0f, -1f, 40, false, false);
        Assert.Equal(0, t.Strokes.Count);
    }
}
