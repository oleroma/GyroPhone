using GyroPhone.Host;
using Xunit;

namespace GyroPhone.Tests;

/// <summary>Snake zip back, as the phone's SnakeZipTest.</summary>
public class SnakeZipTests
{
    // The beam swept 60° to the right over 600 ms.
    private static void Swept(LaserTrace t, LaserTrace.Zip zip)
    {
        for (long ms = 0; ms <= 600; ms += 5)
        {
            float a = ms / 10f * MathF.PI / 180f;
            t.Add(MathF.Sin(a), 0f, -MathF.Cos(a), ms, draw: false, held: false, snakeDeg: 180f, zip: zip);
        }
    }

    private static void Still(LaserTrace t, LaserTrace.Zip zip, long from, long to, bool held = false)
    {
        float a = 60f * MathF.PI / 180f;
        for (long ms = from; ms <= to; ms += 5) t.Add(MathF.Sin(a), 0f, -MathF.Cos(a), ms, draw: false, held: held, snakeDeg: 180f, zip: zip);
    }

    [Fact]
    public void AStoppedBeamZipsTheTailIntoTheHeadBySpeed()
    {
        var t = new LaserTrace();
        var zip = new LaserTrace.Zip(true, false, true, 0.4f, 100f);
        Swept(t, zip);
        Assert.InRange(t.Strokes.BehindNewest(0), 59.4f, 60.6f);
        Still(t, zip, 605, 700);
        Assert.InRange(t.Strokes.BehindNewest(0), 59.4f, 60.6f);
        Still(t, zip, 705, 1050);
        Assert.InRange(t.Strokes.BehindNewest(0), 20f, 35f);
        Still(t, zip, 1055, 2000);
        Assert.Equal(0f, t.Strokes.BehindNewest(0), 4);
        Assert.Equal(1, t.Strokes.Count);
    }

    [Fact]
    public void OnRatchetZipsOnAPressOnly()
    {
        var t = new LaserTrace();
        var zip = new LaserTrace.Zip(false, true, true, 0.4f, 200f);
        Swept(t, zip);
        Still(t, zip, 605, 1000);
        Assert.InRange(t.Strokes.BehindNewest(0), 59.4f, 60.6f);
        Still(t, zip, 1005, 1400, held: true);
        Assert.Equal(0f, t.Strokes.BehindNewest(0), 4);
    }

    [Fact]
    public void TrimmingMovesTheTailAlongItsSpan()
    {
        var r = new LaserTrace.Ring(16);
        r.Add(0f, 0f, -1f, 0, true);
        float a = 10f * MathF.PI / 180f;
        r.Add(MathF.Sin(a), 0f, -MathF.Cos(a), 10, false);
        r.TrimTo(4f);
        Assert.Equal(4f, r.BehindNewest(0), 3);
        Assert.Equal(MathF.Sin(6f * MathF.PI / 180f), r.Point(0).X, 4);
    }
}
