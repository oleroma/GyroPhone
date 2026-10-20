using System.Net;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Host;

/// <summary>
/// One pending or active session: keys, seq counters and the phone's endpoint. Rx state is touched
/// only under the engine lock; every H->P send goes through <see cref="SendSealed"/> under TxLock,
/// which covers seq++, seal and SendTo so packets leave in seq order.
/// </summary>
public sealed class HostSession : IDisposable
{
    private readonly SessionCipher _rx;
    private readonly SessionCipher _tx;
    private readonly object _txLock = new();
    private readonly byte[] _txBuf = new byte[Wire.MaxDatagram];
    private SocketAddress _endpoint;
    private uint _txSeq;
    private bool _disposed;

    public HostSession(uint sessionId, byte[] phoneId, string phoneName, byte[] nP, byte[] nH,
        ReadOnlySpan<byte> kPH, ReadOnlySpan<byte> kHP, byte[] welcome, SocketAddress endpoint, long createdUs)
    {
        SessionId = sessionId;
        PhoneId = phoneId;
        PhoneName = phoneName;
        NP = nP;
        NH = nH;
        Welcome = welcome;
        _rx = new SessionCipher(kPH, Wire.DirPhoneToHost);
        _tx = new SessionCipher(kHP, Wire.DirHostToPhone);
        _endpoint = Copy(endpoint);
        CreatedUs = createdUs;
        LastRxUs = createdUs;
    }

    public uint SessionId { get; }
    public byte[] PhoneId { get; }
    public string PhoneName { get; }
    public byte[] NP { get; }
    public byte[] NH { get; }
    /// <summary>Cached WELCOME, resent for a retransmitted HELLO with the same nP.</summary>
    public byte[] Welcome { get; }
    public long CreatedUs { get; }
    public long LastRxUs { get; set; }
    public uint HighestRxSeq { get; private set; }
    public bool Promoted { get; set; }

    public SocketAddress Endpoint
    {
        get { lock (_txLock) return _endpoint; }
    }

    /// <summary>Follows the source of the latest authenticated packet.</summary>
    public void UpdateEndpoint(SocketAddress from)
    {
        lock (_txLock)
        {
            if (!_endpoint.Equals(from)) _endpoint = Copy(from);
        }
    }

    /// <summary>Validation steps 6 and 7: seq > highest (cheap, first), then GCM open; highest is committed only on success.</summary>
    public bool TryOpen(ReadOnlySpan<byte> packet, Span<byte> plain)
    {
        uint seq = Le.U32(packet, 8);
        if (seq <= HighestRxSeq) return false;
        if (!_rx.Open(packet, plain)) return false;
        HighestRxSeq = seq;
        return true;
    }

    public void SendSealed(byte type, ReadOnlySpan<byte> plain, IDatagramSender? sender)
    {
        if (sender == null) return;
        lock (_txLock)
        {
            if (_disposed) return;
            _txSeq++;
            int n = _tx.Seal(type, SessionId, _txSeq, plain, _txBuf);
            sender.Send(_txBuf.AsSpan(0, n), _endpoint);
        }
    }

    public void SendRaw(ReadOnlySpan<byte> datagram, IDatagramSender? sender)
    {
        if (sender == null) return;
        lock (_txLock)
        {
            if (!_disposed) sender.Send(datagram, _endpoint);
        }
    }

    public bool SamePhone(ReadOnlySpan<byte> phoneId) => PhoneId.AsSpan().SequenceEqual(phoneId);

    public static SocketAddress Copy(SocketAddress a)
    {
        var c = new SocketAddress(a.Family, a.Size);
        a.Buffer.Span[..a.Size].CopyTo(c.Buffer.Span);
        return c;
    }

    public void Dispose()
    {
        lock (_txLock)
        {
            if (_disposed) return;
            _disposed = true;
            _rx.Dispose();
            _tx.Dispose();
        }
    }
}
