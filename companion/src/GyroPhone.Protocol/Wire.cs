namespace GyroPhone.Protocol;

/// <summary>Wire protocol v1 constants (SPEC 1.9). Must stay identical to Wire.kt on the phone.</summary>
public static class Wire
{
    public const int Port = 47800;
    public const byte Version = 1;
    public const int Header = 12;
    public const int GcmTag = 16;
    public const int HelloMac = 16;
    public const int MaxDatagram = 256;

    public const byte Magic0 = 0x47; // 'G'
    public const byte Magic1 = 0x50; // 'P'

    public const int DiscoverSize = 64;
    public const int AnnounceSize = 64;
    public const int PairStartSize = 160;
    public const int PairHostSize = 145;
    public const int PairRevealSize = 48;
    public const int PairAckSize = 17;
    public const int PairConfirmSize = 64;
    public const int PairDoneSize = 49;
    public const int HelloSize = 68;
    public const int WelcomeSize = 80;
    public const int ErrorSize = 16;
    /// <summary>QR code pairing: the phone's key, MACed with the QR's one-time token.</summary>
    public const int KeyEnrollSize = 137;
    public const int KeyEnrollAckSize = 41;

    public const int StatePlain = 66;
    /// <summary>STATE_HIRES: STATE with the wheel total in 1/120 notch plus a horizontal wheel total.</summary>
    public const int StateHiresPlain = 70;
    public const int PingPlain = 8;
    public const int LinkInfoPlain = 22;
    public const int ByePlain = 4;
    public const int PongPlain = 24;
    public const int HostStatusPlain = 22;
    public const int RumblePlain = 4;
    public const int ForegroundPlain = 66;

    public static readonly int[] RatesHz = [250, 500, 1000];
    public const int DefaultRateHz = 1000;
    public const int KeepaliveMs = 20;
    public static readonly int[] RepeatOffsetsMs = [2, 6];

    public const int ReleaseTimeoutMs = 250;
    public const int SessionTimeoutMs = 10000;
    public const int PendingSessionMs = 2000;
    public const int WatchdogTickMs = 50;
    public const int PingIntervalMs = 1000;
    public const int StatusIntervalMs = 1000;
    public const int HostSilentMs = 3000;

    public static readonly int[] HelloRetryMs = [0, 100, 200, 400, 800];
    public const int HelloRetryThenMs = 1000;
    public const int DiscoveryFallbackMs = 500;
    public const int DiscoverIntervalMs = 1000;
    public const int HostExpiryMs = 3000;

    public const int PairingWindowS = 120;
    public const int PairRetxMs = 250;
    public const int PairConfirmRetxMs = 500;
    public const int PairCooldownMs = 5000;
    public const int PairMaxFails = 3;
    public const int PairUserTimeoutS = 60;
    /// <summary>How long a QR code shown by the PC stays valid.</summary>
    public const int QrPairingS = 300;
    public const int QrTokenSize = 16;

    public const int MaxMouseDelta = 16384;
    public const int MaxWheelDelta = 64;
    /// <summary><see cref="MaxWheelDelta"/> in STATE_HIRES units (1/120 notch).</summary>
    public const int MaxWheelDeltaHires = MaxWheelDelta * 120;
    public const uint RekeySeq = 0xF0000000;

    public const int ErrorRatePerIp = 10;   // per second
    public const int ErrorRateGlobal = 50;  // per second
    public const int DscpTrafficClass = 0xC0;

    public const int IdSize = 16;
    public const int NonceSize = 16;
    public const int PublicKeySize = 65;
    public const int HashSize = 32;
    /// <summary>KeyPairing.KeyId: the first 8 bytes of SHA-256 of a static public key.</summary>
    public const int KeyIdSize = 8;
    public const int AnnounceNameMax = 29;
    public const int PairNameMax = 31;
    public const int ForegroundNameMax = 64;

    /// <summary>Direction values in the GCM nonce.</summary>
    public const uint DirPhoneToHost = 1;
    public const uint DirHostToPhone = 2;
}

/// <summary>DISCOVER flags.</summary>
public static class DiscoverFlags
{
    public const ushort WantPairing = 1 << 0;
}

/// <summary>ANNOUNCE flags.</summary>
public static class AnnounceFlags
{
    public const ushort PairingOpen = 1 << 0;
    public const ushort VigemOk = 1 << 1;
    public const ushort PairedWithYou = 1 << 2;
    public const ushort Busy = 1 << 3;
    /// <summary>DISCOVER's hostKeyId is this host's key (key file pairing).</summary>
    public const ushort KeyMatch = 1 << 4;
    /// <summary>DISCOVER's phoneKeyId is in authorized_phones.</summary>
    public const ushort KnowsYourKey = 1 << 5;
}

/// <summary>PAIR_ACK status.</summary>
public static class PairAckStatus
{
    public const byte Ok = 0;
    public const byte BadCommit = 1;
    public const byte Closed = 2;
    public const byte Busy = 3;
    public const byte Cooldown = 4;
    public const byte UnknownAttempt = 5;
}

/// <summary>PAIR_DONE status.</summary>
public static class PairDoneStatus
{
    public const byte Accepted = 0;
    public const byte Denied = 1;
    public const byte Timeout = 2;
    public const byte BadConfirm = 3;
    public const byte Pending = 4;
}

/// <summary>KEY_ENROLL_ACK status.</summary>
public static class EnrollStatus
{
    public const byte Ok = 0;
    /// <summary>No open QR code has that token. Not MACed: the host does not know the token.</summary>
    public const byte Expired = 1;
    /// <summary>Another phone already used this QR code.</summary>
    public const byte Used = 2;
    /// <summary>The host has no key of its own. Not MACed.</summary>
    public const byte NoKey = 3;
}

/// <summary>HELLO caps.</summary>
public static class HelloCaps
{
    public const uint Rumble = 1 << 0;
    public const uint Foreground = 1 << 1;
}

/// <summary>WELCOME hostFlags.</summary>
public static class WelcomeFlags
{
    public const byte VigemOk = 1 << 0;
    public const byte ForegroundSupported = 1 << 1;
    /// <summary>The host takes STATE_HIRES (smooth vertical and horizontal wheel).</summary>
    public const byte HiresWheel = 1 << 2;
}

/// <summary>Output device the host exposes. Values are the wire outputMode byte.</summary>
public enum OutputMode : byte
{
    None = 0,
    Xbox360 = 1,
    DualShock4 = 2,
}

/// <summary>STATE flags.</summary>
public static class StateFlags
{
    public const ushort GyroActive = 1 << 0;
    public const ushort Repeat = 1 << 1;
    public const ushort Keepalive = 1 << 2;
}

/// <summary>LINK_INFO flags.</summary>
public static class LinkInfoFlags
{
    public const byte LowLatencyLockHeld = 1 << 0;
}

/// <summary>HOST_STATUS flags.</summary>
public static class HostStatusFlags
{
    public const byte VigemOk = 1 << 0;
    public const byte PadPlugged = 1 << 1;
    public const byte InputPaused = 1 << 2;
    public const byte ElevatedForeground = 1 << 3;
    public const byte NetworkPublic = 1 << 4;
}

/// <summary>BYE reason (phone to host).</summary>
public static class ByeReason
{
    public const byte User = 0;
    public const byte TransportSwitch = 1;
    public const byte Shutdown = 2;
    public const byte Rekey = 3;
}

/// <summary>HOST_BYE reason.</summary>
public static class HostByeReason
{
    public const byte Exit = 0;
    public const byte Forgotten = 1;
    public const byte Replaced = 2;
}

/// <summary>ERROR code.</summary>
public static class ErrorCode
{
    public const ushort Version = 1;
    public const ushort NotPaired = 2;
    public const ushort SessionUnknown = 3;
}
