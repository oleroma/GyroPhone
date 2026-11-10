using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>The Snake's smooth curve, as the phone's SnakeCurveTest.</summary>
public class SnakeCurveTests
{
    private static (float X, float Y, float Z) At(float deg, float up = 0f) => LaserTrace.Dir(up * MathF.PI / 180f, deg * MathF.PI / 180f);

    [Fact]
    public void PassesThroughItsPointsAtUnitLength()
    {
        var (p0, p1, p2, p3) = (At(0), At(5, 3), At(12, 2), At(15, -4));
        var a = LaserTrace.Curve(p0, p1, p2, p3, 0f);
        var b = LaserTrace.Curve(p0, p1, p2, p3, 1f);
        Assert.Equal(p1.X, a.X, 4); Assert.Equal(p1.Y, a.Y, 4); Assert.Equal(p1.Z, a.Z, 4);
        Assert.Equal(p2.X, b.X, 4); Assert.Equal(p2.Y, b.Y, 4); Assert.Equal(p2.Z, b.Z, 4);
        var m = LaserTrace.Curve(p0, p1, p2, p3, 0.5f);
        Assert.Equal(1f, MathF.Sqrt(m.X * m.X + m.Y * m.Y + m.Z * m.Z), 5);
    }

    [Fact]
    public void AStraightRunStaysOnItsLine()
    {
        var (p0, p1, p2, p3) = (At(0), At(1), At(9), At(10));
        for (int i = 1; i <= 9; i++) Assert.Equal(0f, LaserTrace.Curve(p0, p1, p2, p3, i / 10f).Y, 4);
    }

    [Fact]
    public void ACornerIsRoundedWithoutOvershoot()
    {
        var q = LaserTrace.Curve(At(0), At(8), At(8, 8), At(8, 16), 0.5f);
        float lon = MathF.Atan2(q.X, -q.Z) * 180f / MathF.PI;
        Assert.True(lon > 8.3f && lon < 11f, $"lon {lon}");
    }

    [Fact]
    public void LineEndsUseTheEndAsNeighbour()
    {
        var (a, b) = (At(0), At(10));
        var q = LaserTrace.Curve(a, a, b, b, 0.5f);
        var mid = At(5);
        Assert.Equal(mid.X, q.X, 3); Assert.Equal(mid.Z, q.Z, 3);
    }
}
