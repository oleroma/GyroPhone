using System.Text.Json;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class MouseDeltaTrackerTests
{
    private static JsonElement Seq => Vectors.Root.GetProperty("mouseSequence");

    private static IEnumerable<(uint Seq, long TMs, int[] Total)> Delivered() =>
        Seq.GetProperty("input").EnumerateArray()
            .Where(e => !(e.TryGetProperty("dropped", out var d) && d.GetBoolean()))
            .Select(e => ((uint)e.GetProperty("seq").GetInt64(), e.GetProperty("tMs").GetInt64(),
                e.GetProperty("total").EnumerateArray().Select(x => (int)x.GetInt64()).ToArray()));

    private static List<(uint Seq, int[]? Applied)> Expected() =>
        Seq.GetProperty("expected").EnumerateArray()
            .Select(e => ((uint)e.GetProperty("seq").GetInt64(),
                e.GetProperty("applied").ValueKind == JsonValueKind.Null
                    ? null
                    : e.GetProperty("applied").EnumerateArray().Select(x => x.GetInt32()).ToArray()))
            .ToList();

    [Fact]
    public void MatchesMouseSequenceVector()
    {
        var t = new MouseDeltaTracker();
        uint highest = 0;
        var actual = new List<(uint, int[]?)>();
        foreach (var (seq, tMs, total) in Delivered())
        {
            if (seq <= highest)
            {
                actual.Add((seq, null));
                continue;
            }
            highest = seq;
            t.Apply(total[0], total[1], total[2], tMs * 1000, out int dx, out int dy, out int dw);
            actual.Add((seq, [dx, dy, dw]));
        }
        var expected = Expected();
        Assert.Equal(expected.Count, actual.Count);
        for (int i = 0; i < expected.Count; i++)
        {
            Assert.Equal(expected[i].Seq, actual[i].Item1);
            Assert.Equal(expected[i].Applied, actual[i].Item2);
        }
    }

    /// <summary>The same sequence through the engine: real seq checks, real sealing, one Flush per datagram.</summary>
    [Fact]
    public void EngineAppliesMouseSequence()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        long t0 = h.Clock.NowUs;
        foreach (var (seq, tMs, total) in Delivered())
        {
            h.Clock.NowUs = t0 + tMs * 1000;
            phone.Seq = seq - 1;
            var d = phone.State(f => { f.MouseTotalX = total[0]; f.MouseTotalY = total[1]; f.WheelTotal = total[2]; });
            int before = h.KeyMouse.Calls.Count;
            h.Send(phone, d);
            var exp = Expected().First(e => e.Seq == seq).Applied;
            var calls = h.KeyMouse.Calls.Skip(before).SelectMany(c => c).ToList();
            int dx = calls.Where(e => (e.Flags & InjectEvent.MouseMove) != 0).Sum(e => e.Dx);
            int dy = calls.Where(e => (e.Flags & InjectEvent.MouseMove) != 0).Sum(e => e.Dy);
            int dw = calls.Where(e => (e.Flags & InjectEvent.MouseWheel) != 0).Sum(e => e.MouseData);
            if (exp == null)
                Assert.Empty(calls);
            else
                Assert.Equal(exp, new[] { dx, dy, dw / InjectEvent.WheelDelta });
        }
    }

    [Fact]
    public void ResetRebases()
    {
        var t = new MouseDeltaTracker();
        t.Apply(100, 0, 0, 0, out _, out _, out _);
        t.Apply(110, 0, 0, 1000, out int dx, out _, out _);
        Assert.Equal(10, dx);
        t.Reset();
        t.Apply(500, 0, 0, 2000, out dx, out _, out _);
        Assert.Equal(0, dx);
    }

    [Fact]
    public void WheelClampsTo64()
    {
        var t = new MouseDeltaTracker();
        t.Apply(0, 0, 0, 0, out _, out _, out _);
        t.Apply(0, -100000, 1000, 1000, out _, out int dy, out int dw);
        Assert.Equal(-Wire.MaxMouseDelta, dy);
        Assert.Equal(Wire.MaxWheelDelta, dw);
    }
}
