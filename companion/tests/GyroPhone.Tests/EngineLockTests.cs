using System.Diagnostics;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The overlays, the UI and the input log never make gp-rx wait on the engine lock for them.</summary>
public class EngineLockTests
{
    private static bool Finishes(Action a) => Task.Run(a).Wait(5000);

    [Fact]
    public void OverlayAndMarkerReadsNeverTakeTheEngineLock()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        string id = h.Engine.AddOverlay(OverlayKind.Laser);
        h.Engine.SetOverlay(true);
        h.Send(phone, phone.Sealed(PacketType.Overlay, new byte[Wire.OverlayPlain]));
        // The left button held: the Fire display reads it from then on.
        h.Send(phone, phone.State(f => f.MouseButtons = 1));
        bool readersDone = false, snapshotBlocked = false;
        h.KeyMouse.OnInject = () =>
        {
            // Inject runs under the engine lock: every overlay read still finishes from another thread...
            readersDone = Finishes(() =>
            {
                var f = new OverlayFrame();
                Assert.True(h.Engine.OverlaySnapshot(f, id, out _) >= 0);
                Assert.True(h.Engine.OutputHeld(LaserFire.Mouse));
                h.Engine.OverlayResting(id);
                h.Engine.OverlayStillnessDps();
                Assert.True(h.Engine.Connected);
                h.Engine.HeldSnapshot(new byte[StateFrame.KeyBitmapSize], out _);
                _ = h.Engine.MouseCatchingUp;
            });
            // ...while a call that takes the lock waits (so the lock really is held here).
            snapshotBlocked = !Task.Run(() => h.Engine.Snapshot()).Wait(100);
        };
        h.Send(phone, phone.State(f => f.MouseTotalX += 20));
        Assert.True(readersDone);
        Assert.True(snapshotBlocked);
    }

    [Fact]
    public void FireReadsTheNewestStateEvenWhenItWasNeverFlushed()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        // A STATE with the left button that no Flush hands off, then the phone goes quiet: the release after
        // silence drops the pending STATE, and Fire still shows what the phone sent last (as before the view).
        h.Send(phone, phone.State(f => f.MouseButtons = 1), flush: false);
        h.Tick(Wire.ReleaseTimeoutMs + 50);
        Assert.True(h.Engine.OutputHeld(LaserFire.Mouse));
    }

    [Fact]
    public void InputLogLinesAreWrittenAfterTheLockIsLetGo()
    {
        var h = new Harness();
        var lines = new List<string>();
        bool allFree = true;
        h.Engine.Recorder = new InputRecorder(l =>
        {
            allFree &= Task.Run(() => h.Engine.Snapshot()).Wait(5000);
            lines.Add(l);
        });
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Tick(1000);
        Assert.Contains(lines, l => l.Contains("session start"));
        Assert.Contains(lines, l => l.Contains(" sec "));
        Assert.True(allFree);
    }

    [Fact]
    public async Task ASlowHandOffNamesWhoHeldTheLock()
    {
        var h = new Harness();
        var lines = new List<string>();
        h.Engine.Recorder = new InputRecorder(lines.Add);
        var phone = h.NewPhone();
        h.Connect(phone);
        string id = h.Engine.AddOverlay(OverlayKind.Phone);
        using var entered = new ManualResetEventSlim();
        using var go = new ManualResetEventSlim();
        // The UI holds the engine lock (a settings change) while a moving STATE comes in.
        var ui = Task.Run(() => h.Engine.UpdateOverlaySettings(id, _ =>
        {
            entered.Set();
            go.Wait(5000);
        }));
        Assert.True(entered.Wait(5000));
        var release = Task.Run(async () =>
        {
            await Task.Delay(30);
            h.Clock.AdvanceMs(5);
            go.Set();
        });
        long recv = h.Clock.NowUs;
        h.Engine.OnDatagram(phone.State(f => f.MouseTotalX += 20), phone.Addr, recv);
        h.Engine.Flush(h.Clock.NowUs);
        await ui;
        await release;
        var slow = Assert.Single(lines, l => l.Contains("slow hand-off"));
        Assert.Contains("5.0 ms from receive to SendInput: engine lock", slow);
        Assert.Contains("(ui had it)", slow);
    }

    [Fact]
    public void ASlowHandOffNamesTheTimeInSendInput()
    {
        var h = new Harness();
        var lines = new List<string>();
        h.Engine.Recorder = new InputRecorder(lines.Add);
        var phone = h.NewPhone();
        h.Connect(phone);
        h.KeyMouse.OnInject = () =>
        {
            Thread.Sleep(5);
            h.Clock.AdvanceMs(5);
        };
        h.Send(phone, phone.State(f => f.MouseTotalX += 20));
        var slow = Assert.Single(lines, l => l.Contains("slow hand-off"));
        Assert.Contains("SendInput", slow);
        Assert.DoesNotContain("engine lock", slow);
    }
}

public class SeqLockTests
{
    private sealed class Pair
    {
        public readonly SeqLock Seq = new();
        public long A, B;
    }

    [Fact]
    public async Task ReadersNeverKeepAHalfWrittenCopy()
    {
        var p = new Pair();
        using var stop = new CancellationTokenSource(300);
        var writer = Task.Run(() =>
        {
            for (long i = 1; !stop.IsCancellationRequested; i++)
            {
                p.Seq.BeginWrite();
                p.A = i;
                p.B = -i;
                p.Seq.EndWrite();
            }
        });
        long reads = 0;
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < 250)
        {
            long a, b;
            int s;
            do
            {
                s = p.Seq.ReadBegin();
                a = p.A;
                b = p.B;
            } while (p.Seq.ReadRetry(s));
            Assert.Equal(a, -b);
            reads++;
        }
        await writer;
        Assert.True(reads > 0);
    }
}
