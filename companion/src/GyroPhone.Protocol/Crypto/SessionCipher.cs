using System.Security.Cryptography;

namespace GyroPhone.Protocol.Crypto;

/// <summary>
/// AES-128-GCM for sealed packets (SPEC 1.7): packet = header(12) || ciphertext || tag(16),
/// AAD = header, nonce = u32le(dir) || u32le(sessionId) || u32le(seq). Allocation-free after
/// construction. Not thread-safe: use one instance per thread or guard it.
/// </summary>
public sealed class SessionCipher : IDisposable
{
    private readonly AesGcm _aes;
    private readonly uint _dir;

    public SessionCipher(ReadOnlySpan<byte> key, uint dir)
    {
        _aes = new AesGcm(key, Wire.GcmTag);
        _dir = dir;
    }

    /// <summary>Writes the header, then encrypts plain behind it. Returns the packet length.</summary>
    public int Seal(byte type, uint sessionId, uint seq, ReadOnlySpan<byte> plain, Span<byte> packet)
    {
        int len = Wire.Header + plain.Length + Wire.GcmTag;
        WireCodec.WriteHeader(packet, type, sessionId, seq);
        Span<byte> nonce = stackalloc byte[12];
        MakeNonce(nonce, sessionId, seq);
        _aes.Encrypt(nonce, plain, packet.Slice(Wire.Header, plain.Length),
            packet.Slice(Wire.Header + plain.Length, Wire.GcmTag), packet[..Wire.Header]);
        return len;
    }

    /// <summary>Verifies and decrypts a whole sealed packet into plain. False on any tag failure.</summary>
    public bool Open(ReadOnlySpan<byte> packet, Span<byte> plain)
    {
        int n = packet.Length - Wire.Header - Wire.GcmTag;
        if (n < 0 || plain.Length < n) return false;
        Span<byte> nonce = stackalloc byte[12];
        MakeNonce(nonce, Le.U32(packet, 4), Le.U32(packet, 8));
        try
        {
            _aes.Decrypt(nonce, packet.Slice(Wire.Header, n), packet.Slice(Wire.Header + n, Wire.GcmTag),
                plain[..n], packet[..Wire.Header]);
            return true;
        }
        catch (CryptographicException)
        {
            plain[..n].Clear();
            return false;
        }
    }

    private void MakeNonce(Span<byte> nonce, uint sessionId, uint seq)
    {
        Le.PutU32(nonce, 0, _dir);
        Le.PutU32(nonce, 4, sessionId);
        Le.PutU32(nonce, 8, seq);
    }

    public void Dispose() => _aes.Dispose();
}
