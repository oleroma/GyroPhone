using GyroPhone.Host;
using Xunit;

namespace GyroPhone.Tests;

/// <summary>The Speed graph's buckets, as the phone's GyroTrace.</summary>
public class SpeedTraceTests
{
    [Fact]
    public void SamplesAverageIntoBuckets()
    {
        var t = new SpeedTrace();
        var y = new float[SpeedTrace.Capacity];
        var p = new float[SpeedTrace.Capacity];
        var r = new float[SpeedTrace.Capacity];
        var h = new bool[SpeedTrace.Capacity];
        // 1 kHz samples at 120°/s yaw for 50 ms, a ratchet held for the last 10 ms.
        for (int k = 0; k < 50; k++) t.Add(0.12f, -0.03f, 0f, 0.001f, k >= 40);
        int n = t.Read(100, y, p, r, h);
        Assert.Equal(5, n);
        for (int j = 0; j < n; j++)
        {
            Assert.InRange(y[j], 119f, 121f);
            Assert.InRange(p[j], -30.5f, -29.5f);
        }
        Assert.False(h[0]);
        Assert.True(h[n - 1]);
    }

    [Fact]
    public void KeepsOnlyTheNewest()
    {
        var t = new SpeedTrace();
        var y = new float[SpeedTrace.Capacity];
        var o = new float[SpeedTrace.Capacity];
        var h = new bool[SpeedTrace.Capacity];
        for (int k = 0; k < SpeedTrace.Capacity + 10; k++) t.Add(k, 0f, 0f, 1f / SpeedTrace.RateHz + 1e-6f, false);
        int n = t.Read(3, y, o, o, h);
        Assert.Equal(3, n);
        Assert.True(y[0] < y[1] && y[1] < y[2]);
        Assert.Equal(SpeedTrace.Capacity + 9, y[2] / SpeedTrace.RateHz, 0);
    }
}
