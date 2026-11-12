using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

/// <summary>The virtual pad stays plugged across sessions, and plugging never runs under the engine lock.</summary>
public class PadPlugTests
{
    private const int UsageW = 0x1A;

    [Fact]
    public void PadStaysPluggedAcrossSessionsAndIsReused()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.State(f => f.Lx = 20000));
        h.Send(phone, phone.Bye());
        Assert.False(h.Engine.Snapshot().Connected);
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        Assert.Equal(X360Frame.Neutral, h.Pad.X360[^1]); // left at rest
        Assert.True(h.Engine.Snapshot().PadPlugged);

        h.Tick(30_000);
        h.Connect(phone);
        Assert.True(h.Engine.Snapshot().Connected);
        Assert.Equal(1, h.Pad.Plugs);
        Assert.Equal(0, h.Pad.Unplugs);
    }

    [Fact]
    public void PadUnpluggedAfterFiveMinutesWithoutSession()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Send(phone, phone.Bye());
        h.Tick(299_000);
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        h.Tick(2_000);
        Assert.Equal(OutputMode.None, h.Pad.Plugged);
        Assert.Equal(1, h.Pad.Unplugs);
        h.Tick(60_000);
        Assert.Equal(1, h.Pad.Unplugs);

        // The next session plugs it again.
        h.Connect(phone);
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        Assert.Equal(2, h.Pad.Plugs);
    }

    [Fact]
    public void OutputModeChangeReplugsWithSessionAndUnplugsWithout()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Engine.SetOutputMode(OutputMode.DualShock4);
        Assert.Equal(OutputMode.DualShock4, h.Pad.Plugged);

        h.Send(phone, phone.Bye());
        Assert.Equal(OutputMode.DualShock4, h.Pad.Plugged);
        h.Engine.SetOutputMode(OutputMode.Xbox360);
        Assert.Equal(OutputMode.None, h.Pad.Plugged);
    }

    [Fact]
    public void ForgetAndExitUnplug()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        Assert.True(h.Engine.Forget(Harness.Phone1Id));
        Assert.Equal(OutputMode.None, h.Pad.Plugged);

        var h2 = new Harness();
        var phone2 = h2.NewPhone();
        h2.Connect(phone2);
        h2.Send(phone2, phone2.Bye());
        Assert.Equal(OutputMode.Xbox360, h2.Pad.Plugged);
        h2.Engine.Shutdown();
        Assert.Equal(OutputMode.None, h2.Pad.Plugged);
    }

    [Fact]
    public void ForgettingAnotherPhoneKeepsThePadForTheLastOne()
    {
        var h = new Harness();
        var otherId = Hex.Range(0x80, 16);
        h.AddPaired(otherId, "Pixel", Hex.Range(0xA0, 32));
        var engine = new Host.HostEngine(h.Settings, h.Pad, h.KeyMouse, h.Prompt, new PlainProtector(), h.Clock, h.Events) { PadWorker = job => job() };
        engine.AttachSender(h.Sender);
        var phone = h.NewPhone();
        byte[]? Send(byte[] d)
        {
            int n = h.Sender.Sent.Count;
            engine.OnDatagram(d, phone.Addr, h.Clock.NowUs);
            engine.Flush(h.Clock.NowUs);
            return h.Sender.Sent.Count > n ? h.Sender.Sent[n].Data : null;
        }
        Assert.True(phone.OnWelcome(Send(phone.Hello())!));
        Send(phone.State());
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        Send(phone.Bye());
        Assert.False(engine.Snapshot().Connected);
        Assert.True(engine.Forget(otherId));
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        Assert.True(engine.Forget(Harness.Phone1Id));
        Assert.Equal(OutputMode.None, h.Pad.Plugged);
    }

    [Fact]
    public async Task PlugRunsOnPadThreadWhileInputGoesOn()
    {
        var jobs = new Queue<Action>();
        var h = new Harness(padWorker: jobs.Enqueue);
        var phone = h.NewPhone();
        Assert.True(phone.OnWelcome(h.Send(phone, phone.Hello())!));
        h.Send(phone, phone.State(f => { f.SetKey(UsageW, true); f.Lx = 20000; }));

        // Promoted and typing, the plug still waiting: nothing went to the pad and the phone hears it isn't plugged.
        Assert.True(h.Engine.Snapshot().Connected);
        Assert.Contains((ushort)0x11, h.KeyMouse.HeldKeys);
        Assert.Single(jobs);
        Assert.Equal(0, h.Pad.Plugs);
        Assert.Empty(h.Pad.X360);
        Assert.False(h.Engine.Snapshot().PadPlugged);

        // The pad thread plugs outside the lock; the STATE already handled then goes to the pad.
        h.Sender.Clear();
        await Task.Run(jobs.Dequeue());
        Assert.Equal(OutputMode.Xbox360, h.Pad.Plugged);
        Assert.Equal(20000, h.Pad.X360[^1].Lx);
        Assert.True(h.Engine.Snapshot().PadPlugged);
        Assert.Equal(PacketType.HostStatus, phone.TryOpen(h.Sender.Last!, out var st));
        Assert.NotEqual(0, WireCodec.DecodeHostStatus(st).Flags & HostStatusFlags.PadPlugged);
    }

    [Fact]
    public async Task ModeChangeDuringPlugIsTakenByTheSameJob()
    {
        var jobs = new Queue<Action>();
        var h = new Harness(padWorker: jobs.Enqueue);
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Engine.SetOutputMode(OutputMode.DualShock4);
        Assert.Single(jobs); // the running job takes the new mode
        await Task.Run(jobs.Dequeue());
        Assert.Equal(OutputMode.DualShock4, h.Pad.Plugged);
        Assert.Empty(jobs);
    }
}
