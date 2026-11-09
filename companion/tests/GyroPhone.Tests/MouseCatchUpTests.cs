using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class MouseCatchUpTests
{
    private static (int X, int Y) Take(MouseCatchUp c, long nowUs)
    {
        c.Take(nowUs, out int dx, out int dy);
        return (dx, dy);
    }

    /// <summary>Learns a 1 ms spacing from ordinary packets, as a 1000 Hz phone sends them.</summary>
    private static MouseCatchUp Warmed(ref long now)
    {
        var c = new MouseCatchUp();
        for (int i = 0; i < 40; i++)
        {
            c.Add(3, 0, 1000, false);
            now += 1000;
            Take(c, now);
        }
        return c;
    }

    [Fact]
    public void OrdinaryPacketsGoOutAtOnce()
    {
        long now = 0;
        var c = Warmed(ref now);
        c.Add(7, -2, 1000, false);
        Assert.Equal((7, -2), Take(c, now + 1000));
        Assert.False(c.Gliding);
    }

    [Fact]
    public void JitterBurstGoesOutAtOnce()
    {
        long now = 0;
        var c = Warmed(ref now);
        // Three packets bunched by ordinary jitter: 3 ms of phone time, under the backlog limit.
        c.Add(5, 0, 1000, false);
        c.Add(5, 0, 1000, false);
        c.Add(5, 0, 1000, false);
        Assert.Equal((15, 0), Take(c, now + 3000));
        Assert.False(c.Gliding);
    }

    [Fact]
    public void MotionAfterAnIdlePauseIsNotDelayed()
    {
        long now = 0;
        var c = Warmed(ref now);
        // Still for 18 ms (keepalives carry no motion), then a move: nothing was lost, all of it is fresh.
        c.Add(0, 0, 18000, false);
        Take(c, now += 18000);
        c.Add(40, 10, 18000, false);
        Assert.Equal((40, 10), Take(c, now + 18000));
        Assert.False(c.Gliding);
    }

    [Fact]
    public void LostStretchGlidesOutAndNothingIsLost()
    {
        long now = 0;
        var c = Warmed(ref now);
        // 29 packets lost: the next one carries 30 ms of motion, 300 counts.
        c.Add(300, -60, 30000, true);
        now += 30000;
        var first = Take(c, now);
        // Only the newest 1 ms goes out now; the rest glides.
        Assert.Equal((10, -2), first);
        Assert.True(c.Gliding);
        Assert.Equal(now + 14500, c.GlideEndUs); // half the 29 ms backlog
        int sx = first.X, sy = first.Y, steps = 0, biggest = 0;
        while (c.Gliding)
        {
            now += 1000;
            var d = Take(c, now);
            sx += d.X;
            sy += d.Y;
            biggest = Math.Max(biggest, Math.Abs(d.X));
            steps++;
        }
        Assert.Equal((300, -60), (sx, sy));
        Assert.True(steps >= 14, $"{steps} steps");
        Assert.True(biggest <= 25, $"biggest step {biggest}");
    }

    [Fact]
    public void FreshMotionStillGoesOutAtOnceDuringAGlide()
    {
        long now = 0;
        var c = Warmed(ref now);
        c.Add(300, 0, 30000, true);
        Take(c, now += 30000);
        long before = c.GlideEndUs;
        now += 1000;
        // The same glide step plus a new packet's 50 counts.
        c.Add(50, 0, 1000, false);
        var d = Take(c, now);
        Assert.True(d.X >= 50 + 15, $"{d.X}");
        Assert.Equal(before, c.GlideEndUs);
    }

    [Fact]
    public void LateBurstGlidesAllButTheNewest()
    {
        long now = 0;
        var c = Warmed(ref now);
        // A 20 ms stall, then 20 late packets in one go, nothing lost.
        for (int i = 0; i < 20; i++) c.Add(4, 1, 1000, false);
        now += 20000;
        Assert.Equal((4, 1), Take(c, now));
        Assert.True(c.Gliding);
        int sx = 4, sy = 1;
        while (c.Gliding)
        {
            now += 1000;
            var d = Take(c, now);
            sx += d.X;
            sy += d.Y;
        }
        Assert.Equal((80, 20), (sx, sy));
    }

    [Fact]
    public void GlideCapsAtMaxGlide()
    {
        long now = 0;
        var c = Warmed(ref now);
        c.Add(2000, 0, 200000, true);
        Take(c, now += 200000);
        Assert.Equal(now + MouseCatchUp.MaxGlideUs, c.GlideEndUs);
    }

    [Fact]
    public void ClearDropsTheBacklog()
    {
        long now = 0;
        var c = Warmed(ref now);
        c.Add(300, 0, 30000, true);
        Take(c, now += 30000);
        c.Clear();
        Assert.False(c.Gliding);
        Assert.Equal((0, 0), Take(c, now + 1000));
    }

    [Fact]
    public void LearnsTheSpacing()
    {
        var c = new MouseCatchUp();
        for (int i = 0; i < 100; i++)
        {
            c.Add(1, 0, 4000, false);
            Take(c, i * 4000L);
        }
        Assert.InRange(c.FreshUs, 3800, 4000);
    }

    /// <summary>Through the engine: lost packets with real phone times glide out, Flush without packets moves the glide on.</summary>
    [Fact]
    public void EngineGlidesALostStretch()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        long t0 = h.Clock.NowUs;
        int total = 0;
        void Send(int i, int x)
        {
            h.Clock.NowUs = t0 + i * 1000L;
            h.Send(phone, phone.State(f => { f.PhoneTimeUs = (uint)(i * 1000); f.MouseTotalX = x; }));
        }
        int Moved() => h.KeyMouse.Calls.SelectMany(c => c).Where(e => (e.Flags & InjectEvent.MouseMove) != 0).Sum(e => e.Dx);
        for (int i = 0; i <= 40; i++) Send(i, total = i * 3);
        Assert.Equal(120, Moved());
        Assert.False(h.Engine.MouseGliding);

        // 29 packets lost (their seqs skipped), then one with 30 ms of motion.
        phone.Seq += 29;
        total += 300;
        Send(70, total);
        int afterArrival = Moved();
        Assert.InRange(afterArrival - 120, 1, 30);
        Assert.True(h.Engine.MouseGliding);
        for (int k = 1; k <= 20 && h.Engine.MouseGliding; k++)
        {
            h.Clock.NowUs = t0 + 70_000 + k * 1000L;
            h.Engine.Flush(h.Clock.NowUs);
        }
        Assert.False(h.Engine.MouseGliding);
        Assert.Equal(total, Moved());
    }
}
