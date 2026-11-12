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
    ushort LinkMbps, ushort FreqMhz, byte Flags, byte Path = LinkPath.Unknown);

/// <summary>LINK_INFO's path: the way the session's packets go from the phone to the PC.</summary>
public static class LinkPath
{
    public const byte Unknown = 0;
    /// <summary>The phone's Wi-Fi client (a router, or the PC's own hotspot).</summary>
    public const byte Wifi = 1;
    /// <summary>The phone's own hotspot, with the PC joined to it.</summary>
    public const byte Hotspot = 2;
    public const byte UsbTether = 3;
    public const byte UsbDirect = 4;
    public const byte Ethernet = 5;
}

public readonly record struct HostStatus(
    uint LastSeq, ushort RxPerSec, ushort LossPermille, uint LatP50Us, uint LatP99Us,
    uint JitterUs, byte OutputMode, byte Flags)
{
    public const uint Unknown = 0xFFFFFFFF;
}

/// <summary>When the phone saw the input behind a press or release, and the time of the STATE that first carries it (both the low 32 bits of the phone's µs clock, as STATE's time).</summary>
public readonly record struct PressTime(uint StateTimeUs, uint EventTimeUs);

/// <summary>
/// One input held on the PC while the phone listens (CAPTURED): a key by HID usage, a mouse button (0 left, 1 right,
/// 2 middle, 3 back, 4 forward), a wheel notch (code 0, <see cref="Arg"/> +1 up / -1 down), a controller button by its
/// XInput bit, or a controller axis pushed past half way (code 0..5: left x, y, right x, y, left, right trigger; y down;
/// <see cref="Arg"/> the way, +1 or -1).
/// </summary>
public readonly record struct PcPress(byte Kind, ushort Code, sbyte Arg = 0)
{
    public const byte Key = 1, Mouse = 2, Wheel = 3, PadButton = 4, PadAxis = 5;
}

/// <summary>
/// Host → phone vibration. <see cref="Pattern"/> <see cref="RumblePattern.Motors"/>: the game's rumble,
/// motor levels held until the next RUMBLE. <see cref="RumblePattern.Buzz"/>: a fixed notice buzz.
/// </summary>
public readonly record struct Rumble(byte Large, byte Small, byte Pattern = RumblePattern.Motors);

public static class RumblePattern
{
    public const byte Motors = 0;
    public const byte Buzz = 1;
}

/// <summary>
/// KEY_LABELS: what each key of <see cref="Usages"/> types on the PC's current keyboard layout, one
/// UTF-16 character each (0 = none). The phone sends key positions; it names its keys with these.
/// </summary>
public sealed record KeyLabels(uint Layout, ushort[] Chars)
{
    /// <summary>The character keys, in wire order: letters, digits, punctuation (no Non-US #, which Windows types as \), Non-US \.</summary>
    public static readonly byte[] Usages = BuildUsages();

    private static byte[] BuildUsages()
    {
        var u = new List<byte>();
        for (int i = 0x04; i <= 0x27; i++) u.Add((byte)i);
        for (int i = 0x2D; i <= 0x31; i++) u.Add((byte)i);
        for (int i = 0x33; i <= 0x38; i++) u.Add((byte)i);
        u.Add(0x64);
        return u.ToArray();
    }

    public bool SameAs(KeyLabels? o) => o != null && o.Layout == Layout && o.Chars.AsSpan().SequenceEqual(Chars);
}
