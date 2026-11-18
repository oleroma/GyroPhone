using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class PairingManagerTests
{
    private static Harness NewHost(bool openWindow = true)
    {
        var h = new Harness(pairDefault: false);
        if (openWindow) h.Engine.Start(h.Clock.NowUs); // nothing paired: window opens automatically
        return h;
    }

    private static FakePhone NewPhone(byte idSeed = 0x60, string ip = "192.168.1.60") =>
        new(Hex.Range(idSeed, 16), "OnePlus 15", ip);

    private static PairAck Ack(byte[]? d)
    {
        Assert.NotNull(d);
        Assert.True(WireCodec.TryDecodePairAck(d!, out var a), "expected PAIR_ACK");
        return a;
    }

    private static PairDone Done(byte[]? d)
    {
        Assert.NotNull(d);
        Assert.True(WireCodec.TryDecodePairDone(d!, out var a), "expected PAIR_DONE");
        return a;
    }

    /// <summary>START and REVEAL; returns the phone after the host showed its prompt.</summary>
    private static FakePhone StartAndReveal(Harness h, FakePhone? phone = null)
    {
        phone ??= NewPhone();
        Assert.True(phone.OnPairHost(h.Send(phone, phone.PairStart())!));
        Assert.Equal(PairAckStatus.Ok, Ack(h.Send(phone, phone.PairReveal())).Status);
        return phone;
    }

    [Fact]
    public void ClosedWindowGivesClosed()
    {
        var h = NewHost(openWindow: false);
        var phone = NewPhone();
        var a = Ack(h.Send(phone, phone.PairStart()));
        Assert.Equal(PairAckStatus.Closed, a.Status);
        Assert.Equal(0u, a.AttemptId);
    }

    [Fact]
    public void FullFlowAllowThenConfirmStoresPairing()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        var shown = Assert.Single(h.Prompt.Shown);
        Assert.Equal(phone.Sas, shown.Sas);
        Assert.Equal("OnePlus 15", shown.Name);

        h.Engine.AnswerPair(shown.AttemptId, allow: true);
        Assert.Empty(h.Settings.PairedPhones); // Allow alone does not store

        Assert.True(phone.OnPairDone(h.Send(phone, phone.PairConfirm())!, out var status));
        Assert.Equal(PairDoneStatus.Accepted, status);
        var stored = Assert.Single(h.Settings.PairedPhones);
        Assert.Equal(phone.PhoneId, stored.PhoneId);
        Assert.Equal(phone.PairKey, stored.ProtectedKey); // plain protector in tests

        // The new pairing works for a session.
        var w = h.Send(phone, phone.Hello());
        Assert.True(phone.OnWelcome(w!));
    }

    [Fact]
    public void ConfirmBeforeAllowIsPendingThenAccepted()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        Assert.Equal(PairDoneStatus.Pending, Done(h.Send(phone, phone.PairConfirm())).Status);
        Assert.Equal(PairDoneStatus.Pending, Done(h.Send(phone, phone.PairConfirm())).Status);
        Assert.Empty(h.Settings.PairedPhones);

        h.Engine.AnswerPair(phone.AttemptId, allow: true);
        Assert.Single(h.Settings.PairedPhones); // valid confirmP already seen + Allow
        Assert.True(phone.OnPairDone(h.Send(phone, phone.PairConfirm())!, out _));
        // Retransmitted CONFIRM after completion: same cached DONE.
        Assert.True(phone.OnPairDone(h.Send(phone, phone.PairConfirm())!, out _));
    }

    [Fact]
    public void AllowWithBadConfirmDoesNotStore()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        h.Engine.AnswerPair(phone.AttemptId, allow: true);
        var d = Done(h.Send(phone, phone.PairConfirm(corrupt: true)));
        Assert.Equal(PairDoneStatus.BadConfirm, d.Status);
        Assert.All(d.ConfirmH, b => Assert.Equal(0, b));
        Assert.Empty(h.Settings.PairedPhones);
    }

    [Fact]
    public void DenyGivesDeniedAndCooldown()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        h.Engine.AnswerPair(phone.AttemptId, allow: false);
        Assert.Contains(phone.AttemptId, h.Prompt.Closed);
        Assert.Equal(PairDoneStatus.Denied, Done(h.Send(phone, phone.PairConfirm())).Status);
        Assert.Empty(h.Settings.PairedPhones);

        var again = NewPhone();
        Assert.Equal(PairAckStatus.Cooldown, Ack(h.Send(again, again.PairStart())).Status);
        h.Clock.AdvanceMs(Wire.PairCooldownMs + 1);
        Assert.True(again.OnPairHost(h.Send(again, again.PairStart())!));
    }

    [Fact]
    public void PromptTimesOut()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        h.Tick(Wire.PairUserTimeoutS * 1000L + 10);
        Assert.Equal(PairDoneStatus.Timeout, Done(h.Send(phone, phone.PairConfirm())).Status);
    }

    [Fact]
    public void SecondPhoneIsBusy()
    {
        var h = NewHost();
        var first = StartAndReveal(h);
        var second = NewPhone(0x70, "192.168.1.70");
        Assert.Equal(PairAckStatus.Busy, Ack(h.Send(second, second.PairStart())).Status);
    }

    [Fact]
    public void AnAttemptThatNeverRevealsDoesNotHoldThePcForTheWholeWindow()
    {
        var h = NewHost();
        var squatter = NewPhone(0x50, "192.168.1.50");
        Assert.True(squatter.OnPairHost(h.Send(squatter, squatter.PairStart())!));
        // Meanwhile another phone hears nothing (so it keeps resending) rather than a final BUSY.
        var phone = NewPhone();
        var start = phone.PairStart();
        Assert.Null(h.Send(phone, start));
        h.Clock.AdvanceMs(GyroPhone.Host.PairingManager.RevealTimeoutUs / 1000);
        Assert.True(phone.OnPairHost(h.Send(phone, start)!));
        Assert.Equal(PairAckStatus.Ok, Ack(h.Send(phone, phone.PairReveal())).Status);
        Assert.Equal(phone.AttemptId, Assert.Single(h.Prompt.Shown).AttemptId);
    }

    [Fact]
    public void RestartingUnrevealedAttemptsDoNotHoldThePcPastTheFirstDeadline()
    {
        var h = NewHost();
        var squatter = NewPhone(0x50, "192.168.1.50");
        Assert.True(squatter.OnPairHost(h.Send(squatter, squatter.PairStart())!));
        var phone = NewPhone();
        var start = phone.PairStart();
        // The squatter keeps starting over with new commits, never revealing.
        for (int i = 0; i < 3; i++)
        {
            h.Clock.AdvanceMs(GyroPhone.Host.PairingManager.RevealTimeoutUs / 1000 / 4);
            Assert.NotNull(h.Send(squatter, squatter.PairStart()));
            Assert.Null(h.Send(phone, start));
        }
        h.Clock.AdvanceMs(GyroPhone.Host.PairingManager.RevealTimeoutUs / 1000 / 4);
        Assert.True(phone.OnPairHost(h.Send(phone, start)!));
        Assert.Equal(PairAckStatus.Ok, Ack(h.Send(phone, phone.PairReveal())).Status);
    }

    [Fact]
    public void FloodedStartsGenerateFewKeys()
    {
        var h = NewHost();
        var phone = NewPhone();
        int replies = 0;
        for (int i = 0; i < 50; i++)
            if (h.Send(phone, phone.PairStart()) != null) replies++;
        Assert.Equal(2, replies); // the rest are dropped before any key generation
        h.Clock.AdvanceMs(1000);
        Assert.NotNull(h.Send(phone, phone.PairStart()));
    }

    [Fact]
    public void SamePhoneRestartReplacesItsOwnAttempt()
    {
        var h = NewHost();
        var phone = StartAndReveal(h);
        uint oldId = phone.AttemptId;
        var start = phone.PairStart(); // cancelled on the phone, started again: new key and commit
        Assert.True(phone.OnPairHost(h.Send(phone, start)!));
        Assert.NotEqual(oldId, phone.AttemptId);
        Assert.Contains(oldId, h.Prompt.Closed);
        Assert.Equal(PairAckStatus.Ok, Ack(h.Send(phone, phone.PairReveal())).Status);
        Assert.Equal(phone.AttemptId, h.Prompt.Shown[^1].AttemptId);

        // A repeat of the new START gets the same PAIR_HOST; another phone is still busy.
        var again = h.Send(phone, start)!;
        Assert.True(WireCodec.TryDecodePairHost(again, out var ph));
        Assert.Equal(phone.AttemptId, ph.AttemptId);
        var second = NewPhone(0x70, "192.168.1.70");
        Assert.Equal(PairAckStatus.Busy, Ack(h.Send(second, second.PairStart())).Status);

        h.Engine.AnswerPair(phone.AttemptId, allow: true);
        Assert.True(phone.OnPairDone(h.Send(phone, phone.PairConfirm())!, out _));
    }

    [Fact]
    public void BadCommitCountsAndThreeFailuresCloseWindow()
    {
        var h = NewHost();
        for (int i = 0; i < Wire.PairMaxFails; i++)
        {
            var phone = NewPhone((byte)(0x60 + i));
            Assert.True(phone.OnPairHost(h.Send(phone, phone.PairStart())!));
            var wrongNonce = Hex.Range(0x99, 16);
            Assert.Equal(PairAckStatus.BadCommit, Ack(h.Send(phone, phone.PairReveal(wrongNonce))).Status);
            Assert.Empty(h.Prompt.Shown);
            h.Clock.AdvanceMs(Wire.PairCooldownMs + 1);
        }
        var late = NewPhone(0x6F);
        Assert.Equal(PairAckStatus.Closed, Ack(h.Send(late, late.PairStart())).Status);
        Assert.Equal(0, h.Engine.Snapshot().PairingWindowS);
    }

    [Fact]
    public void RepliesAreIdempotent()
    {
        var h = NewHost();
        var phone = NewPhone();
        var start = phone.PairStart();
        var host1 = h.Send(phone, start);
        var host2 = h.Send(phone, start);
        Assert.Equal(host1, host2); // same attempt, same nH, same key
        Assert.True(phone.OnPairHost(host1!));
        var reveal = phone.PairReveal();
        Assert.Equal(h.Send(phone, reveal), h.Send(phone, reveal));
        Assert.Single(h.Prompt.Shown); // prompt shown once
    }

    [Fact]
    public async Task PromptDoesNotBlockOnDatagram()
    {
        var h = NewHost();
        var phone = NewPhone();
        Assert.True(phone.OnPairHost(h.Send(phone, phone.PairStart())!));
        // A UI that answers later on another thread: OnDatagram must return while the prompt is still open.
        uint shownId = 0;
        h.Prompt.OnShow = id => shownId = id;
        var reply = await Task.Run(() => h.Send(phone, phone.PairReveal())).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(PairAckStatus.Ok, Ack(reply).Status);
        Assert.Equal(phone.AttemptId, shownId);
        // Other traffic keeps flowing while the user decides.
        var other = NewPhone(0x70, "192.168.1.70");
        Assert.NotNull(h.Send(other, other.Discover()));
        Assert.Equal(PairDoneStatus.Pending, Done(h.Send(phone, phone.PairConfirm())).Status);
        // The UI answers from its own thread.
        await Task.Run(() => h.Engine.AnswerPair(shownId, true));
        Assert.True(phone.OnPairDone(h.Send(phone, phone.PairConfirm())!, out _));
    }

    [Fact]
    public void UnknownAttemptForStrayReveal()
    {
        var h = NewHost();
        var phone = NewPhone();
        Assert.True(phone.OnPairHost(h.Send(phone, phone.PairStart())!));
        var stranger = NewPhone(0x70, "192.168.1.70");
        var b = new byte[Wire.PairRevealSize];
        WireCodec.EncodePairReveal(new PairReveal(phone.AttemptId, stranger.PhoneId, Hex.Range(0, 16)), b);
        Assert.Equal(PairAckStatus.UnknownAttempt, Ack(h.Send(stranger, b)).Status);
    }

    [Fact]
    public void AnnounceShowsPairingOpen()
    {
        var h = NewHost();
        var phone = NewPhone();
        Assert.True(WireCodec.TryDecodeAnnounce(h.Send(phone, phone.Discover(42))!, out var a));
        Assert.Equal(42u, a.ReqId);
        Assert.NotEqual(0, a.Flags & AnnounceFlags.PairingOpen);
        Assert.NotEqual(0, a.Flags & AnnounceFlags.VigemOk);
        Assert.Equal(0, a.Flags & AnnounceFlags.PairedWithYou);
        Assert.Equal("DESKTOP-OLEX", a.Name);
    }
}
