using GyroPhone.Protocol;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Perf;

namespace GyroPhone.Tests;

public class PerfTests
{
    private const int Game = 100, Other = 200, Self = 300;

    private static FrameStats Stats() => new(pid => pid == Self);

    /// <summary>Frames every <paramref name="ms"/> from <paramref name="t"/>; returns the time after the last.</summary>
    private static long Frames(FrameStats s, int pid, long t, int count, double ms)
    {
        for (int i = 0; i < count; i++, t += (long)(ms * 1000)) s.Present(pid, t);
        return t;
    }

    [Fact]
    public void ForegroundGameIsPickedAndSkippedProcessesNever()
    {
        var s = Stats();
        long t = Frames(s, Self, 0, 200, 5);
        Frames(s, Other, 0, 10, 100);
        Assert.Equal(Other, s.Tick(Self, t));
        Frames(s, Game, t, 100, 10);
        Frames(s, Other, t, 10, 100);
        Assert.Equal(Game, s.Tick(Game, t + 1_000_000));
        // In the background it stays the game while it shows frames.
        Frames(s, Game, t + 1_000_000, 100, 10);
        Frames(s, Other, t + 1_000_000, 10, 100);
        Assert.Equal(Game, s.Tick(Self, t + 2_000_000));
    }

    [Fact]
    public void ALongFrameIsAJumpAtAnyFrameRate()
    {
        foreach (double ms in new[] { 6.9, 11.1, 16.7, 33.3 })
        {
            var s = Stats();
            s.Present(Game, 0);
            s.Tick(Game, 0);
            long t = Frames(s, Game, 0, 100, ms);
            t += (long)(ms * 500); // one frame half as long again as the steady rhythm...
            t = Frames(s, Game, t, 1, ms);
            t += (long)(Math.Max(ms, 8) * 1000) * 2; // ...and one at least twice as long and 8 ms longer
            t = Frames(s, Game, t, 50, ms);
            var v = s.View(t);
            Assert.Equal(1, v.Jumps);
            Assert.True(v.WorstMs >= 2 * ms);
        }
    }

    [Fact]
    public void APauseIsNotAFrame()
    {
        var s = Stats();
        s.Present(Game, 0);
        s.Tick(Game, 0);
        long t = Frames(s, Game, 0, 100, 10);
        t = Frames(s, Game, t + 3_000_000, 100, 10);
        var v = s.View(t);
        Assert.Equal(0, v.Jumps);
        Assert.True(v.WorstMs < 11);
        Assert.Equal(99, v.Fps); // the first frame after the pause only starts the count
    }

    [Fact]
    public void Low1FpsIsTheSlowestFrames()
    {
        var ms = Enumerable.Repeat(10f, 990).Concat(Enumerable.Repeat(40f, 10)).ToList();
        Assert.Equal(25, FrameStats.Low1Fps(ms), 3);
        Assert.Equal(0, FrameStats.Low1Fps([]));
    }

    [Fact]
    public void RecordingCountsOnlyTheFocusedGameAndSumsTheLink()
    {
        var s = Stats();
        var r = new PerfRecording("PC hotspot", "Wi-Fi 5 GHz", 0, DateTime.Now, 0);
        s.Recording = r;
        s.Present(Game, 0);
        s.Tick(Self, 0); // the game, but not in front
        long t = Frames(s, Game, 0, 100, 10);
        Assert.Equal(0, r.GameSeconds);
        s.Tick(Game, t);
        // The first 3 s in front settle; then 1 s counts.
        t = Frames(s, Game, t, 400, 10);
        r.AddLoad(new LoadSample(60, 1, 2, 15), 500_000);
        var link = new List<LinkSecond> { new(1_000_000, 400, 3, 5, 120, 1, 0), new(2_000_000, 400, 0, 0, 0, 0, 1) };
        var (run, text) = r.Finish(t, link);
        Assert.Equal(100, run.AvgFps, 1);
        Assert.Equal(1.0, run.GameSeconds, 2);
        Assert.Equal(3, run.Lost);
        Assert.Equal(5, run.Late);
        Assert.Equal(120, run.MaxLateMs);
        Assert.Equal(1, run.Gaps);
        Assert.Equal(1, run.Releases);
        Assert.Equal(3, run.DriverPct, 3);
        Assert.Contains("PC hotspot", text);
    }

    [Fact]
    public void InputLogSecondsReachTheTimeline()
    {
        var rec = new InputRecorder(_ => { });
        var tl = new LinkTimeline();
        rec.Timeline = tl;
        rec.SessionStarted("phone", 0);
        rec.Lost(4, 10, 500_000);
        rec.ReleasedForSilence(2, 600_000);
        rec.Second(new LinkStats(), 1_000_000);
        rec.Second(new LinkStats(), 2_000_000);
        var secs = tl.Between(0, 3_000_000);
        Assert.Equal(2, secs.Count);
        Assert.Equal(4, secs[0].Lost);
        Assert.Equal(1, secs[0].Releases);
        Assert.False(secs[1].Trouble);
    }

    private sealed class FakeClock : IClock
    {
        public long NowUs { get; set; }
    }

    private sealed class FakeSource : IFrameSource
    {
        public Action<int, long>? Present;
        public FrameWatchState Answer = FrameWatchState.On;
        public FrameWatchState Start(Action<int, long> present, out string? error)
        {
            Present = present;
            error = null;
            return Answer;
        }
        public void Stop() => Present = null;
        public void Dispose() { }
    }

    private sealed class FakeForeground : IForeground
    {
        public int Pid = Game;
        public int ForegroundPid() => Pid;
        public string? NameOf(int pid) => pid == Game ? "Discovery" : "other";
    }

    [Fact]
    public void MonitorRecordsATimedTestAndKeepsIt()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gp-perf-" + Guid.NewGuid().ToString("N"));
        var clock = new FakeClock();
        var src = new FakeSource();
        var m = new PerfMonitor(src, null, new FakeForeground(), clock, dir, _ => { });
        PerfRun? ended = null;
        m.Ended += r => ended = r;
        m.PathNow = () => "Router Wi-Fi 5 GHz";
        m.Watch(true); // the FPS overlay is on
        m.StartRecording("Router", "Wi-Fi 5 GHz", 6);
        Assert.Equal(FrameWatchState.On, m.State);
        src.Present!(Game, 0);
        m.Tick();
        long t = 0;
        for (int i = 0; i < 450; i++) src.Present!(Game, t += 10_000);
        clock.NowUs = t;
        m.Tick();
        Assert.NotNull(m.Recording);
        clock.NowUs = 6_100_000;
        m.Tick();
        Assert.Null(m.Recording);
        Assert.Equal(FrameWatchState.On, m.State); // the overlay still is
        m.Watch(false);
        Assert.Equal(FrameWatchState.Off, m.State);
        var run = Assert.Single(m.Runs);
        Assert.Equal(run, ended);
        Assert.Equal("Router Wi-Fi 5 GHz", run.Path);
        Assert.True(run.Frames);
        Assert.Equal("Discovery", run.Game);
        Assert.Equal(100, run.AvgFps, 1);
        Assert.True(File.Exists(run.File));
        // Kept for next time.
        var again = new PerfMonitor(new FakeSource(), null, new FakeForeground(), clock, dir, _ => { });
        Assert.Equal("Router", Assert.Single(again.Runs).Name);
        Directory.Delete(dir, true);
    }

    [Fact]
    public void ALinkTestNeedsNoFrameWatch()
    {
        var dir = Path.Combine(Path.GetTempPath(), "gp-perf-" + Guid.NewGuid().ToString("N"));
        var clock = new FakeClock();
        var src = new FakeSource { Answer = FrameWatchState.NeedsPermission };
        var m = new PerfMonitor(src, null, new FakeForeground(), clock, dir, _ => { });
        string path = "Router Wi-Fi 5 GHz";
        m.PathNow = () => path;
        m.StartRecording("x", "y", 0);
        Assert.NotNull(m.Recording);
        Assert.Equal(FrameWatchState.Off, m.State); // no FPS overlay: nothing watches the game
        Assert.Null(src.Present);
        Assert.Null(m.SecondNote());
        path = "Phone hotspot";
        clock.NowUs = 1_000_000;
        m.Tick();
        m.StopRecording();
        var run = Assert.Single(m.Runs);
        Assert.False(run.Frames);
        Assert.Equal("Router Wi-Fi 5 GHz → Phone hotspot", run.Path);
        Directory.Delete(dir, true);
    }

    [Fact]
    public void BurstsJoinNearbySecondsAndKeepTheirTime()
    {
        var start = new DateTime(2026, 11, 11, 20, 17, 50);
        var r = new PerfRecording("t", "l", 0, start, 0);
        var link = new List<LinkSecond>();
        for (int s = 1; s <= 12; s++)
        {
            bool bad = s is 2 or 3 or 5 or 10;
            link.Add(new(s * 1_000_000L, 500, 0, bad ? 10 : 0, bad ? 100 : 0, 0, 0, 5, 9, 1));
        }
        var (run, text) = r.Finish(13_000_000, link);
        Assert.Equal(2, run.Bursts); // s1-4 (2, 3 and 5 keyed one lower) and s9
        Assert.Equal(5, run.LatP50Ms, 3);
        Assert.Equal(9, run.LatP99Ms, 3);
        Assert.Contains(":51", text); // the first burst's second of the minute
        Assert.Contains("not watched", text);
    }
}

public class LinkPathTests
{
    private static LinkInfo Info(byte path, byte flags = 0, byte band = 5, ushort mhz = 5180) => new(0, 0, 500, -50, band, 866, mhz, flags, path);

    [Fact]
    public void PathsAreNamedAsTheSetups()
    {
        Assert.Equal("Phone hotspot", LinkPaths.Setup(Info(LinkPath.Hotspot), null));
        Assert.Equal("Router", LinkPaths.Setup(Info(LinkPath.Wifi), new PcAdapter("Ethernet", false)));
        Assert.Equal("PC hotspot", LinkPaths.Setup(Info(LinkPath.Wifi), new PcAdapter("Local Area Connection* 10", true)));
        Assert.Equal("Wired (USB)", LinkPaths.Setup(Info(LinkPath.UsbDirect, LinkInfoFlags.Usb | LinkInfoFlags.UsbDirect), null));
        Assert.Equal("Ethernet", LinkPaths.Setup(Info(LinkPath.Ethernet, LinkInfoFlags.Ethernet), null));
        Assert.Null(LinkPaths.Setup(Info(LinkPath.Unknown), null));
        Assert.Equal("Router Wi-Fi 5 GHz ch 36 · PC Ethernet", LinkPaths.Describe(Info(LinkPath.Wifi), new PcAdapter("Ethernet", false)));
        Assert.Equal("Phone hotspot", LinkPaths.Describe(Info(LinkPath.Hotspot), null));
    }
}

public class FpsCountTests
{
    [Fact]
    public void FpsCountsByTheFramesOwnTimesNotDelivery()
    {
        var s = new FrameStats(_ => false);
        s.Present(7, 0);
        s.Tick(7, 0);
        long t = 0;
        for (int i = 0; i < 300; i++) s.Present(7, t += 10_000); // 3 s at 100 fps
        // Windows delivered them late: now is 0.8 s past the newest frame.
        var v = s.View(t + 800_000);
        Assert.Equal(100, v.Fps, 0);
        Assert.Equal(100, s.LastSecond(t + 800_000).Frames);
        // Stopped showing frames: 0.
        Assert.Equal(0, s.View(t + 2_000_000).Fps);
    }
}

public class PcBusyTests
{
    private static LoadSample Load(params (string, double)[] top) =>
        new(60, 1, 1, 5, [.. top.Select(t => new ProgramLoad(t.Item1, t.Item2))]);

    [Fact]
    public void GameAndGyroPhoneDontCount()
    {
        Assert.Null(PcBusy.Of(Load(("Discovery", 55), ("GyroPhoneCompanion", 6), ("dwm", 4), ("chrome", 3)), "Discovery", "GyroPhoneCompanion"));
    }

    [Fact]
    public void ABuildIsNamed()
    {
        var b = PcBusy.Of(Load(("Discovery", 40), ("java", 31), ("dotnet", 9), ("chrome", 1)), "discovery", "GyroPhoneCompanion");
        Assert.NotNull(b);
        Assert.Equal("java 31%, dotnet 9%", b.Value.Text);
        Assert.Equal("java", b.Value.Top);
    }

    [Fact]
    public void RecordMarksBusySeconds()
    {
        var r = new PerfRecording("Phone hotspot", "Wi-Fi", 0, DateTime.Now, 0) { Self = "GyroPhoneCompanion" };
        r.AddLoad(Load(("Discovery", 40), ("java", 30)), 500_000, "Discovery");
        r.AddLoad(Load(("Discovery", 40), ("chrome", 3)), 1_500_000, "Discovery");
        var link = new List<LinkSecond> { new(1_000_000, 400, 5, 0, 0, 0, 0), new(2_000_000, 400, 0, 0, 0, 0, 0) };
        var (run, text) = r.Finish(2_000_000, link);
        Assert.Equal(1, run.BusySeconds);
        Assert.Equal("java", run.BusyTop);
        Assert.Contains("WARNING: the PC was busy", text);
        Assert.Contains("java 30%", text);
    }
}
