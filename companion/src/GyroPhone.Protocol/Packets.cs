namespace GyroPhone.Protocol;

// Decoded forms of every v1 packet. Ids, keys and nonces are fresh arrays; these types are for the
// control path only. STATE uses the mutable StateFrame instead so the hot path allocates nothing.

/// <summary>The 12-byte header carried by every packet.</summary>
public readonly record struct PacketHeader(byte Type, uint SessionId, uint Seq);

/// <summary>
/// HostKeyId names the host key the phone trusts and PhoneKeyId the phone's own key (KeyPairing.KeyId,
/// all zero when absent; null encodes as zero).
/// </summary>
public sealed record Discover(uint ReqId, byte[] PhoneId, ushort Flags, byte[]? HostKeyId = null, byte[]? PhoneKeyId = null);

public sealed record Announce(uint ReqId, byte[] HostId, ushort Flags, string Name);

public sealed record PairStart(byte[] PhoneId, byte[] PkP, byte[] Commit, string Name);

public sealed record PairHost(uint AttemptId, byte[] HostId, byte[] PkH, byte[] NH, string Name);

public sealed record PairReveal(uint AttemptId, byte[] PhoneId, byte[] NP);

public sealed record PairAck(uint AttemptId, byte Status);

public sealed record PairConfirm(uint AttemptId, byte[] PhoneId, byte[] ConfirmP);

public sealed record PairDone(uint AttemptId, byte Status, byte[] ConfirmH);

/// <summary>HELLO without its mac; the mac is computed on encode and verified separately.</summary>
public sealed record Hello(byte[] PhoneId, byte[] NP, uint Caps, ushort MaxRateHz);

/// <summary>WELCOME without its mac.</summary>
public sealed record Welcome(uint SessionId, byte[] HostId, byte[] NP, byte[] NH, byte OutputMode, byte HostFlags, ushort KeepaliveMs);

/// <summary>KEY_ENROLL; <see cref="Mac"/> is QrPairing.EnrollMac under the QR code's token.</summary>
public sealed record KeyEnroll(uint ReqId, byte[] TokenId, byte[] PkP, string Name, byte[] Mac);

public sealed record ErrorPacket(ushort Code, byte MaxVersion);

// Sealed plaintexts.

public readonly record struct Ping(ulong T1);

public readonly record struct Pong(ulong T1, ulong T2, ulong T3);

public readonly record struct LinkInfo(
    long ClockOffsetUs, uint RttMinUs, ushort SendRateHz, sbyte RssiDbm, byte Band,
    ushort LinkMbps, ushort FreqMhz, byte Flags);

public readonly record struct HostStatus(
    uint LastSeq, ushort RxPerSec, ushort LossPermille, uint LatP50Us, uint LatP99Us,
    uint JitterUs, byte OutputMode, byte Flags)
{
    public const uint Unknown = 0xFFFFFFFF;
}

public readonly record struct Rumble(byte Large, byte Small);
