using System.Security.Cryptography;

namespace GyroPhone.Protocol.Crypto;

/// <summary>
/// P-256 key pair: ephemeral for code pairing, static for key file pairing. Public keys travel as SEC1 uncompressed points
/// (04 || X || Y, big-endian, 65 bytes); the shared secret Z is the 32-byte X coordinate.
/// </summary>
public sealed class P256 : IDisposable
{
    private const int CoordSize = 32;
    private readonly ECDiffieHellman _key;

    private P256(ECDiffieHellman key)
    {
        _key = key;
        PublicKey = EncodePublic(key.ExportParameters(false));
    }

    /// <summary>65-byte SEC1 uncompressed public key.</summary>
    public byte[] PublicKey { get; }

    public static P256 Generate() => new(ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256));

    /// <summary>Deterministic key from a 32-byte big-endian private scalar. For tests and vectors.</summary>
    public static P256 FromPrivateScalar(ReadOnlySpan<byte> d)
    {
        if (d.Length != CoordSize) throw new ArgumentException("P-256 scalar must be 32 bytes");
        var ecdh = ECDiffieHellman.Create();
        ecdh.ImportParameters(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, D = d.ToArray() });
        return new P256(ecdh);
    }

    /// <summary>The 32-byte big-endian private scalar, for storing a static key (wrap it before it touches disk).</summary>
    public byte[] ExportPrivateScalar()
    {
        var d = _key.ExportParameters(true).D!;
        if (d.Length == CoordSize) return d;
        var o = new byte[CoordSize];
        d.CopyTo(o, CoordSize - d.Length);
        CryptographicOperations.ZeroMemory(d);
        return o;
    }

    /// <summary>Z = X coordinate of d * peer. Throws <see cref="CryptographicException"/> on an invalid point.</summary>
    public byte[] Ecdh(ReadOnlySpan<byte> peerPublic)
    {
        using var peer = DecodePublic(peerPublic);
        return _key.DeriveRawSecretAgreement(peer.PublicKey);
    }

    /// <summary>True when the bytes are a well-formed uncompressed point on P-256.</summary>
    public static bool IsValidPublic(ReadOnlySpan<byte> pub)
    {
        try
        {
            using var k = DecodePublic(pub);
            return true;
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    private static ECDiffieHellman DecodePublic(ReadOnlySpan<byte> pub)
    {
        if (pub.Length != Wire.PublicKeySize || pub[0] != 0x04)
            throw new CryptographicException("not an uncompressed P-256 point");
        var p = new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = pub.Slice(1, CoordSize).ToArray(), Y = pub.Slice(1 + CoordSize, CoordSize).ToArray() },
        };
        // ImportParameters validates that the point lies on the curve.
        var k = ECDiffieHellman.Create();
        try
        {
            k.ImportParameters(p);
        }
        catch
        {
            k.Dispose();
            throw;
        }
        return k;
    }

    private static byte[] EncodePublic(ECParameters p)
    {
        var o = new byte[Wire.PublicKeySize];
        o[0] = 0x04;
        p.Q.X!.CopyTo(o, 1 + CoordSize - p.Q.X!.Length);
        p.Q.Y!.CopyTo(o, 1 + 2 * CoordSize - p.Q.Y!.Length);
        return o;
    }

    public void Dispose() => _key.Dispose();
}
