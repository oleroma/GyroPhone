using System.Net;
using System.Security.Cryptography;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Host;

/// <summary>
/// Pending and active sessions (SPEC 1.6). At most one pending session per phone, expiring after
/// PENDING_SESSION_MS; one active session, replaced only by promotion on the first valid STATE.
/// Holds no I/O; HostEngine performs the side effects.
/// </summary>
public sealed class SessionManager
{
    private readonly List<HostSession> _pending = new();
    private readonly Func<int, byte[]> _random;

    public SessionManager(Func<int, byte[]>? random = null) =>
        _random = random ?? RandomNumberGenerator.GetBytes;

    public HostSession? Active { get; private set; }

    public IReadOnlyList<HostSession> Pending => _pending;

    /// <summary>Active or pending session with this id.</summary>
    public HostSession? Find(uint sessionId)
    {
        if (Active != null && Active.SessionId == sessionId) return Active;
        foreach (var p in _pending)
            if (p.SessionId == sessionId) return p;
        return null;
    }

    /// <summary>The session a retransmitted HELLO (same phone, same nP) belongs to, if any.</summary>
    public HostSession? FindByHello(ReadOnlySpan<byte> phoneId, ReadOnlySpan<byte> nP)
    {
        foreach (var p in _pending)
            if (p.SamePhone(phoneId) && p.NP.AsSpan().SequenceEqual(nP)) return p;
        if (Active != null && Active.SamePhone(phoneId) && Active.NP.AsSpan().SequenceEqual(nP)) return Active;
        return null;
    }

    /// <summary>Creates the pending session for a valid HELLO (replacing that phone's previous pending one) and its WELCOME.</summary>
    public HostSession CreatePending(Hello hello, string phoneName, ReadOnlySpan<byte> pairKey, byte[] hostId,
        byte outputMode, byte hostFlags, SocketAddress from, long nowUs)
    {
        RemovePending(hello.PhoneId);
        uint sid;
        do sid = BitConverter.ToUInt32(_random(4)); while (sid == 0 || Find(sid) != null);
        var nH = _random(Wire.NonceSize);
        var welcome = new byte[Wire.WelcomeSize];
        WireCodec.EncodeWelcome(new Welcome(sid, hostId, hello.NP, nH, outputMode, hostFlags, Wire.KeepaliveMs), pairKey, welcome);
        var (kPH, kHP) = SessionKeys.Derive(pairKey, hello.NP, nH, sid);
        var s = new HostSession(sid, hello.PhoneId, phoneName, hello.NP, nH, kPH, kHP, welcome, from, nowUs);
        CryptographicOperations.ZeroMemory(kPH);
        CryptographicOperations.ZeroMemory(kHP);
        _pending.Add(s);
        return s;
    }

    /// <summary>Makes a pending session active. Returns the replaced session (not disposed), if any.</summary>
    public HostSession? Promote(HostSession pending)
    {
        _pending.Remove(pending);
        var old = Active;
        Active = pending;
        pending.Promoted = true;
        return old;
    }

    /// <summary>Drops the active session (disposing it).</summary>
    public void DropActive()
    {
        Active?.Dispose();
        Active = null;
    }

    public void Remove(HostSession s)
    {
        if (ReferenceEquals(s, Active)) DropActive();
        else if (_pending.Remove(s)) s.Dispose();
    }

    public void RemovePending(ReadOnlySpan<byte> phoneId)
    {
        for (int i = _pending.Count - 1; i >= 0; i--)
        {
            if (!_pending[i].SamePhone(phoneId)) continue;
            _pending[i].Dispose();
            _pending.RemoveAt(i);
        }
    }

    public int ExpirePending(long nowUs)
    {
        int n = 0;
        for (int i = _pending.Count - 1; i >= 0; i--)
        {
            if (nowUs - _pending[i].CreatedUs <= Wire.PendingSessionMs * 1000L) continue;
            _pending[i].Dispose();
            _pending.RemoveAt(i);
            n++;
        }
        return n;
    }
}
