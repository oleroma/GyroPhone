using System.Diagnostics;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Split;

namespace GyroPhone.Tests;

/// <summary>Pairing raw mouse input with GyroPhone's handed-off moves (the delivery probe and the Delivery test).</summary>
public class DeliveryMatchTests
{
    private static readonly long Ms = Stopwatch.Frequency / 1000;

    private sealed class Rig
    {
        public readonly MoveRing Ring = new();
        public readonly List<Delivery> Got = new();
        public long Missed, Stray;
        public readonly DeliveryMatcher Matcher;

        public Rig() => Matcher = new DeliveryMatcher(Ring, Got.Add, n => Missed += n, () => Stray++);
    }

    [Fact]
    public void OneByOneMovesAreTimedEach()
    {
        var r = new Rig();
        r.Ring.Put(100 * Ms, 3, 0);
        r.Matcher.Raw(101 * Ms, 3, 0, driver: true);
        r.Ring.Put(110 * Ms, -2, 1);
        r.Matcher.Raw(113 * Ms, -2, 1, driver: true);
        Assert.Equal(2, r.Got.Count);
        Assert.Equal(1 * Ms, r.Got[0].Ticks);
        Assert.Equal(3 * Ms, r.Got[1].Ticks);
        Assert.Equal(10 * Ms, r.Got[1].GapTicks);
        Assert.Equal(0, r.Got[1].Merged);
        Assert.Equal(0, r.Missed);
    }

    [Fact]
    public void MergedRunIsTimedFromItsNewestMove()
    {
        var r = new Rig();
        r.Ring.Put(100 * Ms, 1, 0);
        r.Ring.Put(101 * Ms, 2, 0);
        r.Ring.Put(102 * Ms, 3, 1);
        r.Matcher.Raw(108 * Ms, 6, 1, driver: true);
        var d = Assert.Single(r.Got);
        Assert.Equal(6 * Ms, d.Ticks);
        Assert.Equal(2, d.Merged);
        Assert.Equal(1 * Ms, d.GapTicks);
    }

    [Fact]
    public void RawMoveWaitsForItsHandOffThenStrays()
    {
        var r = new Rig();
        r.Matcher.Raw(100 * Ms, 5, 5, driver: false);
        Assert.Empty(r.Got);
        r.Ring.Put(99 * Ms, 5, 5);
        r.Matcher.Settle(101 * Ms);
        Assert.Single(r.Got);
        r.Matcher.Raw(200 * Ms, 7, 7, driver: false);
        r.Matcher.Settle(300 * Ms);
        Assert.Equal(1, r.Stray);
    }

    [Fact]
    public void MovesThatNeverCameAreMissed()
    {
        var r = new Rig();
        r.Ring.Put(100 * Ms, 1, 0);
        r.Ring.Put(101 * Ms, 4, 0);
        r.Matcher.Raw(103 * Ms, 4, 0, driver: true);
        Assert.Equal(1, r.Missed);
        r.Ring.Put(110 * Ms, 9, 9);
        r.Matcher.Settle(300 * Ms);
        Assert.Equal(2, r.Missed);
    }

    [Fact]
    public void SkipForgetsWithoutCounting()
    {
        var r = new Rig();
        r.Ring.Put(100 * Ms, 1, 0);
        r.Ring.Put(101 * Ms, 1, 0);
        r.Matcher.Skip();
        r.Matcher.Settle(500 * Ms);
        Assert.Equal(0, r.Missed);
        r.Ring.Put(510 * Ms, 2, 2);
        r.Matcher.Raw(511 * Ms, 2, 2, driver: true);
        Assert.Equal(409 * Ms, Assert.Single(r.Got).GapTicks);
    }

    [Fact]
    public void FullRingDropsAndCounts()
    {
        using var ring = new MoveRing();
        for (int i = 0; i < MoveRing.Capacity + 5; i++) ring.Put(i, 1, 0);
        Assert.Equal(5, ring.TakeOverflow());
        Assert.Equal(0, ring.TakeOverflow());
        ring.Reset();
        Assert.Equal(0, ring.Head);
    }

    [Fact]
    public void TallyCountsSlowPer1000()
    {
        var t = new DeliveryTally();
        for (int i = 0; i < 998; i++) t.Add(new Delivery(Ms / 2, 0, true, 0));
        t.Add(new Delivery(3 * Ms, 0, true, 0));
        t.Add(new Delivery(20 * Ms, 1, true, 0));
        t.Missed(2);
        var n = t.Numbers();
        Assert.Equal(1000, n.N);
        Assert.Equal(2, n.Slow);
        Assert.Equal(2.0, n.SlowPer1000, 3);
        Assert.Equal(20.0, n.Max, 3);
        Assert.Equal(2, n.Unmatched);
        Assert.Equal(1, n.Merged);
    }

    [Fact]
    public void SharedRegionHasTheMoveRingAndNumbersCrossThePipe()
    {
        using var region = SharedRegion.InProcess();
        region.Moves.Put(5, 1, 2);
        Assert.Equal(1, region.Moves.Head);
        Assert.Equal(2, region.Moves.Dy(0));
        var n = new DeliveryNumbers(10, 0.5, 1.5, 3.0, 1, 0, 2, 0);
        var back = JsonSerializer.Deserialize<DeliveryNumbers>(JsonSerializer.Serialize(n, PipeChannel.Json), PipeChannel.Json);
        Assert.Equal(n, back);
    }
}
