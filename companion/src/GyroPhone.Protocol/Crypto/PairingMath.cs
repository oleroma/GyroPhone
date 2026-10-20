using System.Security.Cryptography;

namespace GyroPhone.Protocol.Crypto;

/// <summary>Commit/reveal numeric-comparison pairing math (SPEC 1.5).</summary>
public static class PairingMath
{
    private static readonly byte[] CommitLabel = "GP-commit-v1"u8.ToArray();
    private static readonly byte[] PairLabel = "GP-pair-v1"u8.ToArray();
    private static readonly byte[] SasLabel = "GP-sas-v1"u8.ToArray();
    private static readonly byte[] PairKeyInfo = "GP-pair-key-v1"u8.ToArray();
    private static readonly byte[] ConfirmPLabel = "GP-confirm-P"u8.ToArray();
    private static readonly byte[] ConfirmHLabel = "GP-confirm-H"u8.ToArray();

    /// <summary>commit = H("GP-commit-v1" || pkP || nP)</summary>
    public static byte[] Commit(ReadOnlySpan<byte> pkP, ReadOnlySpan<byte> nP) => Sha(CommitLabel, pkP, nP);

    /// <summary>th = H("GP-pair-v1" || phoneId || hostId || pkP || pkH || nP || nH || u32le(attemptId))</summary>
    public static byte[] Th(ReadOnlySpan<byte> phoneId, ReadOnlySpan<byte> hostId, ReadOnlySpan<byte> pkP,
        ReadOnlySpan<byte> pkH, ReadOnlySpan<byte> nP, ReadOnlySpan<byte> nH, uint attemptId)
    {
        Span<byte> a = stackalloc byte[4];
        Le.PutU32(a, 0, attemptId);
        using var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        h.AppendData(PairLabel);
        h.AppendData(phoneId);
        h.AppendData(hostId);
        h.AppendData(pkP);
        h.AppendData(pkH);
        h.AppendData(nP);
        h.AppendData(nH);
        h.AppendData(a);
        return h.GetHashAndReset();
    }

    /// <summary>sas = u32le(H("GP-sas-v1" || th)[0..4]) mod 1_000_000</summary>
    public static uint Sas(ReadOnlySpan<byte> th) => Le.U32(Sha(SasLabel, th, default), 0) % 1_000_000;

    /// <summary>Six zero-padded digits, e.g. "400588".</summary>
    public static string SasDigits(uint sas) => sas.ToString("D6");

    /// <summary>Display form with a space after three digits, e.g. "400 588".</summary>
    public static string SasDisplay(uint sas)
    {
        var s = SasDigits(sas);
        return s[..3] + " " + s[3..];
    }

    /// <summary>pairKey = HKDF(ikm = Z, salt = th, info = "GP-pair-key-v1", L = 32)</summary>
    public static byte[] PairKey(ReadOnlySpan<byte> z, ReadOnlySpan<byte> th)
    {
        var o = new byte[32];
        HKDF.DeriveKey(HashAlgorithmName.SHA256, z, o, th, PairKeyInfo);
        return o;
    }

    public static byte[] ConfirmP(ReadOnlySpan<byte> pairKey, ReadOnlySpan<byte> th) => Mac(pairKey, ConfirmPLabel, th);

    public static byte[] ConfirmH(ReadOnlySpan<byte> pairKey, ReadOnlySpan<byte> th) => Mac(pairKey, ConfirmHLabel, th);

    private static byte[] Sha(ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c)
    {
        using var h = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        h.AppendData(a);
        h.AppendData(b);
        h.AppendData(c);
        return h.GetHashAndReset();
    }

    private static byte[] Mac(ReadOnlySpan<byte> key, ReadOnlySpan<byte> label, ReadOnlySpan<byte> th)
    {
        using var h = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, key);
        h.AppendData(label);
        h.AppendData(th);
        return h.GetHashAndReset();
    }

}
