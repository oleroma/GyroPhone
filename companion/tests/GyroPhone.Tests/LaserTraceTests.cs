using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The laser pointer overlay's trail and paint, as the phone's LaserTraceTest.</summary>
public class LaserTraceTests
{
    private static readonly float[] Identity = [1, 0, 0, 0, 1, 0, 0, 0, 1];

    [Fact]
    public void BeamPointsOutOfThePhonesBackIntoTheScreen()
    {
        var b = LaserTrace.Beam(Identity);
        Assert.Equal(-1f, b.Z);
        Assert.Equal(LaserTrace.Rows / 2, LaserTrace.RowOf(0.001f));
        Assert.Equal(LaserTrace.Cols / 2, LaserTrace.ColOf(0.001f));
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
        Assert.False(LaserTrace.CellFront[LaserTrace.Rows / 2 * LaserTrace.Cols + LaserTrace.Cols / 2]);
        Assert.NotEmpty(LaserTrace.Grid.Front);
        Assert.NotEmpty(LaserTrace.Grid.Back);
        foreach (var l in LaserTrace.Grid.Front.Concat(LaserTrace.Grid.Back))
            foreach (var (x, y) in l) Assert.True(x * x + y * y <= 1.0001f);
    }

    [Fact]
    public void PaintCoversTheBrushOnlyAndClears()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 1000, 500, true, 4, false);
        int first = t.Painted;
        Assert.InRange(first, 9, 20);
        t.Add(0, 0, -1, 1010, 500, true, 4, false);
        Assert.Equal(first, t.Painted);
        t.Clear();
        Assert.Equal(0, t.Painted);
        Assert.Equal(0, t.TrailCount);
    }

    [Fact]
    public void AFastSwingPaintsAContinuousStroke()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 0, true, 2, false);
        double a = 60 * Math.PI / 180;
        t.Add((float)Math.Sin(a), 0, -(float)Math.Cos(a), 16, 0, true, 2, false);
        for (int c = LaserTrace.Cols / 2; c < LaserTrace.Cols / 2 + 30; c++)
            Assert.NotEqual(0, t.LevelOf(LaserTrace.Rows / 2, c, 16, 0));
    }

    [Fact]
    public void HeldRatchetNeitherTrailsNorPaintsAndBreaksTheLine()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, 4, false);
        int painted = t.Painted;
        t.Add(1, 0, 0, 10, 500, true, 4, true);
        Assert.Equal(painted, t.Painted);
        Assert.Equal(1, t.TrailCount);
        t.Add(1, 0, 0, 20, 500, true, 4, false);
        Assert.Equal(2, t.TrailCount);
        Assert.True(t.TrailGap(1));
        Assert.Equal(0, t.LevelOf(LaserTrace.Rows / 2, LaserTrace.Cols / 2 + 22, 20, 0));
    }

    [Fact]
    public void TrailAndPaintFade()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, 4, false);
        int r = LaserTrace.Rows / 2, c = LaserTrace.Cols / 2;
        Assert.Equal(LaserTrace.Levels, t.LevelOf(r, c, 0, 5000));
        Assert.Equal(LaserTrace.Levels / 2, t.LevelOf(r, c, 2500, 5000));
        t.Expire(600, 500, 5000);
        Assert.Equal(0, t.TrailCount);
        Assert.True(t.Fading(5000));
        t.Expire(5001, 500, 5000);
        Assert.Equal(0, t.Painted);
        Assert.False(t.Fading(5000));
    }

    [Fact]
    public void RunsMergeNeighbouringCells()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 0, true, 6, false);
        int runs = 0, cells = 0;
        t.ForEachRun(0, 0, (_, c0, c1, level, _) =>
        {
            runs++;
            cells += c1 - c0 + 1;
            Assert.Equal(LaserTrace.Levels, level);
        });
        Assert.Equal(t.Painted, cells);
        Assert.True(runs <= 7, $"runs {runs}");
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
}
