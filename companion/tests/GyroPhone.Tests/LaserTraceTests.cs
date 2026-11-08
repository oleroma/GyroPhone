using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The laser pointer overlay's trail and strokes, as the phone's LaserTraceTest.</summary>
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
    public void StrokesKeepAPointOnlyWhenTheBeamMoves()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, false);
        t.Add(0, 0, -1, 10, 500, true, false);
        Assert.Equal(1, t.Strokes.Count);
        t.Add(0.1f, 0, -1, 20, 500, true, false);
        Assert.Equal(2, t.Strokes.Count);
        Assert.True(t.Strokes.Gap(0));
        Assert.False(t.Strokes.Gap(1));
    }

    [Fact]
    public void DrawOffOrClearWipesTheStrokes()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, false);
        t.Add(1, 0, 0, 10, 500, true, false);
        Assert.Equal(2, t.Strokes.Count);
        t.Add(1, 0, 0, 20, 500, false, false);
        Assert.Equal(0, t.Strokes.Count);
        t.Add(0, 0, -1, 30, 500, true, false);
        t.Clear();
        Assert.Equal(0, t.Strokes.Count);
        Assert.Equal(0, t.Trail.Count);
    }

    [Fact]
    public void HeldRatchetNeitherTrailsNorPaintsAndBreaksTheLine()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, false);
        t.Add(1, 0, 0, 5, 500, true, true);
        Assert.Equal(1, t.Strokes.Count); // kept without Clear on ratchet
        t.Add(1, 0, 0, 6, 500, true, false);
        t.Add(1, 0, 0, 10, 500, true, true, true);
        Assert.Equal(0, t.Strokes.Count); // a ratchet press clears the drawing
        Assert.Equal(0, t.Trail.Count);
        t.Add(1, 0, 0, 20, 500, true, false);
        Assert.Equal(1, t.Trail.Count);
        Assert.Equal(1, t.Strokes.Count);
        Assert.True(t.Strokes.Gap(0));
    }

    [Fact]
    public void TrailAndStrokesFade()
    {
        var t = new LaserTrace();
        t.Add(0, 0, -1, 0, 500, true, false);
        Assert.Equal(1f, LaserTrace.StrokeLife(0, 1, 0, 0, 5000));
        Assert.Equal(0.5f, LaserTrace.StrokeLife(0, 1, 0, 2500, 5000), 3);
        t.Expire(600, 500, 5000);
        Assert.Equal(0, t.Trail.Count);
        Assert.True(t.Fading(5000));
        t.Expire(5001, 500, 5000);
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
}
