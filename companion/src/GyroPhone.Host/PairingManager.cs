using System.Security.Cryptography;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Host;

/// <summary>
/// Host side of commit/reveal pairing with an explicit Allow on the PC (SPEC 1.5). One attempt at a
/// time, only while the pairing window is open. Every message is idempotent per attemptId: a
/// retransmit gets the cached reply. Replies are written into the caller's buffer; the return value
/// is the reply length (0 = stay silent). Not thread-safe: HostEngine calls it under its lock.
/// </summary>
public sealed class PairingManager
{
    private const long WindowUs = Wire.PairingWindowS * 1_000_000L;
    private const long CooldownUs = Wire.PairCooldownMs * 1000L;
    private const long UserTimeoutUs = Wire.PairUserTimeoutS * 1_000_000L;
    private const long AttemptTimeoutUs = Wire.PairingWindowS * 1_000_000L;

    private readonly Func<int, byte[]> _random;
    private readonly Func<P256> _newKey;
    private readonly IPairPrompt _prompt;
    private readonly Func<(byte[] HostId, string HostName)> _host;
    private readonly Action<byte[], string, byte[]> _onPaired;

    private long _windowUntilUs = long.MinValue;
    private long _cooldownUntilUs = long.MinValue;
    private int _failures;
    private Attempt? _attempt;
    private Finished? _finished;

    public PairingManager(IPairPrompt prompt, Func<(byte[] HostId, string HostName)> host,
        Action<byte[], string, byte[]> onPaired, Func<int, byte[]>? random = null, Func<P256>? newKey = null)
    {
        _prompt = prompt;
        _host = host;
        _onPaired = onPaired;
        _random = random ?? RandomNumberGenerator.GetBytes;
        _newKey = newKey ?? P256.Generate;
    }

    public int Failures => _failures;

    public bool IsOpen(long nowUs) => nowUs < _windowUntilUs;

    /// <summary>Seconds left in the pairing window (0 when closed).</summary>
    public int WindowRemainingS(long nowUs) => IsOpen(nowUs) ? (int)((_windowUntilUs - nowUs + 999_999) / 1_000_000) : 0;

    /// <summary>The attempt currently waiting for the user, if any: (attemptId, phone name, SAS display).</summary>
    public (uint AttemptId, string PhoneName, string Sas)? PromptInfo =>
        _attempt is { Revealed: true, UserAnswer: null } a ? (a.AttemptId, a.PhoneName, a.SasDisplay!) : null;

    /// <summary>"Pair new phone…": opens the window for PAIRING_WINDOW_S and resets the failure count.</summary>
    public void Open(long nowUs)
    {
        _windowUntilUs = nowUs + WindowUs;
        _failures = 0;
        _cooldownUntilUs = long.MinValue;
    }

    public void Close()
    {
        _windowUntilUs = long.MinValue;
        if (_attempt != null) End(nowUs: 0, cooldown: false);
    }

    // ---- PAIR_START ----

    public int OnStart(PairStart m, long nowUs, Span<byte> reply)
    {
        if (_attempt != null)
        {
            if (_attempt.SameStart(m)) return Copy(_attempt.HostReply, reply);
            return Ack(0, PairAckStatus.Busy, reply);
        }
        if (!IsOpen(nowUs)) return Ack(0, PairAckStatus.Closed, reply);
        if (nowUs < _cooldownUntilUs) return Ack(0, PairAckStatus.Cooldown, reply);
        if (!P256.IsValidPublic(m.PkP)) return 0;

        uint id;
        do id = BitConverter.ToUInt32(_random(4)); while (id == 0 || id == _finished?.AttemptId);
        var key = _newKey();
        var (hostId, hostName) = _host();
        var a = new Attempt
        {
            AttemptId = id,
            PhoneId = m.PhoneId,
            PhoneName = m.Name,
            PkP = m.PkP,
            Commit = m.Commit,
            HostId = hostId,
            Key = key,
            PkH = key.PublicKey,
            NH = _random(Wire.NonceSize),
            StartedUs = nowUs,
        };
        a.HostReply = new byte[Wire.PairHostSize];
        WireCodec.EncodePairHost(new PairHost(id, hostId, a.PkH, a.NH, hostName), a.HostReply);
        _attempt = a;
        _finished = null;
        return Copy(a.HostReply, reply);
    }

    // ---- PAIR_REVEAL ----

    public int OnReveal(PairReveal m, long nowUs, Span<byte> reply)
    {
        var a = _attempt;
        if (a == null || a.AttemptId != m.AttemptId || !a.PhoneId.AsSpan().SequenceEqual(m.PhoneId))
        {
            if (_finished is { } f && f.AttemptId == m.AttemptId && f.Ack != null) return Copy(f.Ack, reply);
            return Ack(m.AttemptId == 0 ? 0 : m.AttemptId, PairAckStatus.UnknownAttempt, reply);
        }
        if (a.Revealed) return Ack(a.AttemptId, PairAckStatus.Ok, reply);

        var expect = PairingMath.Commit(a.PkP, m.NP);
        if (!CryptographicOperations.FixedTimeEquals(expect, a.Commit))
        {
            var ack = new byte[Wire.PairAckSize];
            WireCodec.EncodePairAck(new PairAck(a.AttemptId, PairAckStatus.BadCommit), ack);
            Fail(nowUs);
            End(nowUs, cooldown: false, ack: ack);
            return Copy(ack, reply);
        }

        byte[] z;
        try
        {
            z = a.Key!.Ecdh(a.PkP);
        }
        catch (CryptographicException)
        {
            End(nowUs, cooldown: true);
            return 0;
        }
        a.Key!.Dispose();
        a.Key = null;
        a.NP = m.NP;
        a.Th = PairingMath.Th(a.PhoneId, a.HostId, a.PkP, a.PkH, a.NP, a.NH, a.AttemptId);
        a.SasDisplay = PairingMath.SasDisplay(PairingMath.Sas(a.Th));
        a.PairKey = PairingMath.PairKey(z, a.Th);
        CryptographicOperations.ZeroMemory(z);
        a.Revealed = true;
        a.PromptDeadlineUs = nowUs + UserTimeoutUs;
        a.PromptOpen = true;
        // Show only queues UI work; it never blocks the rx thread.
        _prompt.Show(a.AttemptId, a.PhoneName, a.SasDisplay);
        return Ack(a.AttemptId, PairAckStatus.Ok, reply);
    }

    // ---- PAIR_CONFIRM ----

    public int OnConfirm(PairConfirm m, long nowUs, Span<byte> reply)
    {
        var a = _attempt;
        if (a == null || a.AttemptId != m.AttemptId || !a.PhoneId.AsSpan().SequenceEqual(m.PhoneId) || !a.Revealed)
        {
            if (_finished is { } f && f.AttemptId == m.AttemptId && f.Done != null && f.PhoneId.AsSpan().SequenceEqual(m.PhoneId))
                return Copy(f.Done, reply);
            return Ack(m.AttemptId, PairAckStatus.UnknownAttempt, reply);
        }

        var expect = PairingMath.ConfirmP(a.PairKey!, a.Th!);
        if (!CryptographicOperations.FixedTimeEquals(expect, m.ConfirmP))
        {
            Fail(nowUs);
            return Copy(Finish(a, PairDoneStatus.BadConfirm, nowUs, cooldown: false), reply);
        }
        a.ConfirmVerified = true;
        return a.UserAnswer switch
        {
            null => Done(a.AttemptId, PairDoneStatus.Pending, null, reply),
            true => Copy(Accept(a, nowUs), reply),
            false => Copy(Finish(a, PairDoneStatus.Denied, nowUs, cooldown: true), reply),
        };
    }

    // ---- user answer and timers ----

    /// <summary>The PC user's Allow/Deny. Ignored for a stale attemptId. Returns true when the pairing was stored.</summary>
    public bool Answer(uint attemptId, bool allow, long nowUs)
    {
        var a = _attempt;
        if (a == null || a.AttemptId != attemptId || !a.Revealed || a.UserAnswer != null) return false;
        a.UserAnswer = allow;
        a.PromptOpen = false;
        if (!allow)
        {
            Finish(a, PairDoneStatus.Denied, nowUs, cooldown: true);
            return false;
        }
        // Allow before CONFIRM: wait for a valid confirmP. Allow after it: store now.
        if (a.ConfirmVerified)
        {
            Accept(a, nowUs);
            return true;
        }
        return false;
    }

    /// <summary>Prompt and attempt timeouts. Returns true when something changed.</summary>
    public bool Tick(long nowUs)
    {
        var a = _attempt;
        if (a == null) return false;
        if (a.Revealed && a.UserAnswer == null && nowUs >= a.PromptDeadlineUs)
        {
            Finish(a, PairDoneStatus.Timeout, nowUs, cooldown: true);
            return true;
        }
        if (nowUs - a.StartedUs >= AttemptTimeoutUs)
        {
            End(nowUs, cooldown: false);
            return true;
        }
        return false;
    }

    private byte[] Accept(Attempt a, long nowUs)
    {
        var confirmH = PairingMath.ConfirmH(a.PairKey!, a.Th!);
        var key = (byte[])a.PairKey!.Clone();
        _onPaired(a.PhoneId, a.PhoneName, key);
        var done = Finish(a, PairDoneStatus.Accepted, nowUs, cooldown: false, confirmH);
        // Success: close the window and start fresh next time.
        _windowUntilUs = long.MinValue;
        _failures = 0;
        return done;
    }

    private byte[] Finish(Attempt a, byte status, long nowUs, bool cooldown, byte[]? confirmH = null)
    {
        var done = new byte[Wire.PairDoneSize];
        WireCodec.EncodePairDone(new PairDone(a.AttemptId, status, confirmH ?? new byte[Wire.HashSize]), done);
        End(nowUs, cooldown, done: done);
        return done;
    }

    private void Fail(long nowUs)
    {
        _failures++;
        _cooldownUntilUs = nowUs + CooldownUs;
        if (_failures >= Wire.PairMaxFails) _windowUntilUs = long.MinValue;
    }

    private void End(long nowUs, bool cooldown, byte[]? ack = null, byte[]? done = null)
    {
        var a = _attempt;
        if (a == null) return;
        _attempt = null;
        if (cooldown) _cooldownUntilUs = Math.Max(_cooldownUntilUs, nowUs + CooldownUs);
        _finished = new Finished(a.AttemptId, a.PhoneId, ack, done);
        a.Key?.Dispose();
        a.Key = null;
        if (a.PairKey != null) CryptographicOperations.ZeroMemory(a.PairKey);
        if (a.Revealed) _prompt.Close(a.AttemptId);
    }

    private static int Ack(uint attemptId, byte status, Span<byte> reply) =>
        WireCodec.EncodePairAck(new PairAck(attemptId, status), reply);

    private static int Done(uint attemptId, byte status, byte[]? confirmH, Span<byte> reply) =>
        WireCodec.EncodePairDone(new PairDone(attemptId, status, confirmH ?? new byte[Wire.HashSize]), reply);

    private static int Copy(byte[] src, Span<byte> reply)
    {
        src.CopyTo(reply);
        return src.Length;
    }

    private sealed class Attempt
    {
        public uint AttemptId;
        public byte[] PhoneId = [];
        public string PhoneName = "";
        public byte[] PkP = [];
        public byte[] Commit = [];
        public byte[] HostId = [];
        public P256? Key;
        public byte[] PkH = [];
        public byte[] NH = [];
        public byte[] HostReply = [];
        public long StartedUs;
        public bool Revealed;
        public byte[]? NP;
        public byte[]? Th;
        public byte[]? PairKey;
        public string? SasDisplay;
        public bool PromptOpen;
        public long PromptDeadlineUs;
        public bool? UserAnswer;
        public bool ConfirmVerified;

        public bool SameStart(PairStart m) =>
            PhoneId.AsSpan().SequenceEqual(m.PhoneId) && Commit.AsSpan().SequenceEqual(m.Commit);
    }

    private sealed record Finished(uint AttemptId, byte[] PhoneId, byte[]? Ack, byte[]? Done);
}
