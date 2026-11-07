using GyroPhone.Host;

namespace GyroPhone.Tests;

public class InputRecorderTests
{
    private static (InputRecorder rec, List<string> lines) New()
    {
        var lines = new List<string>();
        var rec = new InputRecorder(lines.Add);
        rec.SessionStarted("phone", 0);
        return (rec, lines);
    }

    [Fact]
    public void PacketsHeldUpOnTheWayAreOneLateLine()
    {
        var (rec, lines) = New();
        long t = 1_000_000;
        uint phone = 5_000;
        for (int i = 0; i < 200; i++, t += 1000, phone += 1000) rec.State(phone, t + 3000, true, true, false);
        // 30 packets sent on time arrive in a burst 40 ms late, then normal again.
        for (int i = 0; i < 30; i++, phone += 1000) rec.State(phone, t + 3000 + 40_000 - i * 1000 + i * 50, true, true, false);
        t += 30_000;
        for (int i = 0; i < 50; i++, t += 1000, phone += 1000) rec.State(phone, t + 3000 + 20_000, true, true, false);
        for (int i = 0; i < 50; i++, t += 1000, phone += 1000) rec.State(phone, t + 3000, true, true, false);
        var late = lines.Where(l => l.Contains(" late ")).ToList();
        Assert.Single(late);
        Assert.Contains("held up on the way", late[0]);
        Assert.Contains("up to 40.0 ms late", late[0]);
    }

    [Fact]
    public void PhoneNotSendingWhileMovingIsAPhoneGap()
    {
        var (rec, lines) = New();
        rec.State(1_000, 10_000, true, true, false);
        rec.State(2_000, 11_000, true, true, false);
        rec.State(52_000, 61_000, true, true, false);
        // A lost stretch is not the phone's gap, and an idle pause isn't either.
        rec.State(102_000, 111_000, true, true, lost: true);
        rec.State(103_000, 112_000, false, false, false);
        rec.State(200_000, 209_000, false, false, false);
        var gaps = lines.Where(l => l.Contains("phone gap")).ToList();
        Assert.Single(gaps);
        Assert.Contains("50.0 ms", gaps[0]);
    }

    [Fact]
    public void SecondLineAndSessionSummaryCountWithoutNamingKeys()
    {
        var (rec, lines) = New();
        rec.State(1_000, 10_000, true, true, false);
        rec.Inject(3, -2, 10_000, 10_100, false);
        rec.Inject(70, 0, 10_000, 13_000, false);
        rec.Lost(5, 42, 20_000);
        rec.ReleasedForSilence(2, 300_000);
        rec.Second(new LinkStats(), 1_000_000);
        rec.SessionEnded("timed out", 2_000_000);
        Assert.Contains(lines, l => l.Contains("big move") && l.Contains("70,0"));
        Assert.Contains(lines, l => l.Contains("slow hand-off") && l.Contains("3.0 ms"));
        Assert.Contains(lines, l => l.Contains("lost") && l.Contains("5 packets"));
        Assert.Contains(lines, l => l.Contains("release") && l.Contains("2 held"));
        var sec = Assert.Single(lines, l => l.Contains(" sec "));
        Assert.Contains("inj 2 px 75 maxstep 70", sec);
        var end = Assert.Single(lines, l => l.Contains("session end"));
        Assert.Contains("timed out", end);
        Assert.Contains("lost 5", end);
    }

    [Fact]
    public void NothingIsWrittenOutsideASession()
    {
        var lines = new List<string>();
        var rec = new InputRecorder(lines.Add);
        rec.State(1, 2, true, true, false);
        rec.Inject(100, 0, 0, 10_000, true);
        rec.Second(new LinkStats(), 1_000_000);
        Assert.Empty(lines);
    }
}
