using System.Net;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

internal static class Hex
{
    public static byte[] B(string hex) => Convert.FromHexString(hex);

    public static string S(ReadOnlySpan<byte> b) => Convert.ToHexString(b).ToLowerInvariant();

    public static byte[] Range(int from, int count) => Enumerable.Range(from, count).Select(i => (byte)i).ToArray();
}

/// <summary>The shared golden vectors (core/src/test/resources/protocol/v1/wire-v1.json, linked into the output).</summary>
internal static class Vectors
{
    private static readonly Lazy<JsonDocument> Doc = new(() =>
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "vectors", "wire-v1.json"))));

    public static JsonElement Root => Doc.Value.RootElement;
    public static JsonElement Pairing => Root.GetProperty("pairing");
    public static JsonElement Session => Root.GetProperty("session");

    public static JsonElement Packet(string name) =>
        Root.GetProperty("packets").EnumerateArray().First(p => p.GetProperty("name").GetString() == name);

    public static byte[] Bytes(this JsonElement e, string prop) => Hex.B(e.GetProperty(prop).GetString()!);

    public static long Long(this JsonElement e, string prop) => e.GetProperty(prop).GetInt64();

    public static string Str(this JsonElement e, string prop) => e.GetProperty(prop).GetString()!;
}

internal sealed class FakeClock : IClock
{
    public long NowUs { get; set; } = 1_000_000_000;

    public void AdvanceMs(long ms) => NowUs += ms * 1000;
}

internal sealed class FakeSender : IDatagramSender
{
    public readonly List<(byte[] Data, SocketAddress To)> Sent = new();

    public void Send(ReadOnlySpan<byte> datagram, SocketAddress to) => Sent.Add((datagram.ToArray(), HostSession.Copy(to)));

    public List<byte[]> To(SocketAddress a) => Sent.Where(s => s.To.Equals(a)).Select(s => s.Data).ToList();

    public byte[]? Last => Sent.Count == 0 ? null : Sent[^1].Data;

    public void Clear() => Sent.Clear();
}

internal sealed class FakePad : IPadSink
{
    public bool DriverOk { get; set; } = true;
    public OutputMode Plugged { get; private set; }
    public readonly List<X360Frame> X360 = new();
    public readonly List<Ds4Frame> Ds4 = new();
    public int Plugs, Unplugs;

    public bool Plug(OutputMode mode)
    {
        Plugs++;
        Plugged = mode;
        return true;
    }

    public void Unplug()
    {
        if (Plugged != OutputMode.None) Unplugs++;
        Plugged = OutputMode.None;
    }

    public void Submit(in X360Frame frame) => X360.Add(frame);

    public void Submit(in Ds4Frame frame) => Ds4.Add(frame);

    public event Action<byte, byte>? Rumble;

    public void RaiseRumble(byte l, byte s) => Rumble?.Invoke(l, s);
}

/// <summary>Records injections and tracks what is held, like the OS would.</summary>
internal sealed class FakeKeyMouse : IKeyMouseSink
{
    public readonly List<InjectEvent[]> Calls = new();
    public readonly HashSet<ushort> HeldKeys = new();
    public readonly HashSet<string> HeldButtons = new();
    public long MouseX, MouseY, Wheel;

    public IEnumerable<InjectEvent> All => Calls.SelectMany(c => c);

    public void Inject(ReadOnlySpan<InjectEvent> events)
    {
        Calls.Add(events.ToArray());
        foreach (var e in events)
        {
            if (e.Kind == InjectKind.Key)
            {
                ushort id = e.Vk != 0 ? (ushort)(0x8000 | e.Vk) : (ushort)(e.Scan | ((e.Flags & InjectEvent.KeyExtended) != 0 ? 0x100 : 0));
                if (e.IsKeyUp) HeldKeys.Remove(id); else HeldKeys.Add(id);
                continue;
            }
            if ((e.Flags & InjectEvent.MouseMove) != 0) { MouseX += e.Dx; MouseY += e.Dy; }
            if ((e.Flags & InjectEvent.MouseWheel) != 0) Wheel += e.MouseData;
            Button(e, InjectEvent.MouseLeftDown, InjectEvent.MouseLeftUp, "L");
            Button(e, InjectEvent.MouseRightDown, InjectEvent.MouseRightUp, "R");
            Button(e, InjectEvent.MouseMiddleDown, InjectEvent.MouseMiddleUp, "M");
            Button(e, InjectEvent.MouseXDown, InjectEvent.MouseXUp, "X" + e.MouseData);
        }
    }

    private void Button(in InjectEvent e, uint down, uint up, string name)
    {
        if ((e.Flags & down) != 0) HeldButtons.Add(name);
        if ((e.Flags & up) != 0) HeldButtons.Remove(name);
    }

    public bool NothingHeld => HeldKeys.Count == 0 && HeldButtons.Count == 0;
}

internal sealed class FakePrompt : IPairPrompt
{
    public readonly List<(uint AttemptId, string Name, string Sas)> Shown = new();
    public readonly List<uint> Closed = new();
    public Action<uint>? OnShow { get; set; }

    public void Show(uint attemptId, string phoneName, string sasDisplay)
    {
        Shown.Add((attemptId, phoneName, sasDisplay));
        OnShow?.Invoke(attemptId);
    }

    public void Close(uint attemptId) => Closed.Add(attemptId);
}

internal sealed class PlainProtector : IKeyProtector
{
    public byte[] Protect(byte[] plain) => (byte[])plain.Clone();

    public byte[] Unprotect(byte[] blob) => (byte[])blob.Clone();
}

internal sealed class RecordingEvents : IHostEvents
{
    public readonly List<string> Logs = new();
    public int Changes;

    public void StateChanged() => Changes++;

    public void Log(string message)
    {
        lock (Logs) Logs.Add(message);
    }
}

/// <summary>A phone in a test: builds requests with the Protocol library and checks replies like the real app.</summary>
internal sealed class FakePhone : IDisposable
{
    private SessionCipher? _tx, _rx;
    private byte[]? _pkH, _nHpair, _hostId, _th;
    private P256? _key;
    private byte[] _nPpair = [];

    public FakePhone(byte[] phoneId, string name, string ip = "192.168.1.50", int port = 40000)
    {
        PhoneId = phoneId;
        Name = name;
        Addr = new IPEndPoint(IPAddress.Parse(ip), port).Serialize();
    }

    public byte[] PhoneId { get; }
    public string Name { get; }
    public SocketAddress Addr { get; set; }
    public byte[]? PairKey { get; set; }
    public uint AttemptId { get; private set; }
    public string? Sas { get; private set; }

    public uint SessionId { get; private set; }
    public byte[] HelloNonce { get; private set; } = [];
    public uint Seq { get; set; }
    public uint RxHighest { get; private set; }
    public readonly StateFrame Frame = new();

    public static FakePhone Paired(byte[] phoneId, string name, byte[] pairKey, string ip = "192.168.1.50", int port = 40000) =>
        new(phoneId, name, ip, port) { PairKey = pairKey };

    public byte[] Discover(uint reqId = 7)
    {
        var b = new byte[Wire.DiscoverSize];
        WireCodec.EncodeDiscover(new Discover(reqId, PhoneId, 0), b);
        return b;
    }

    public byte[] PairStart(P256? key = null, byte[]? nP = null)
    {
        _key = key ?? P256.Generate();
        _nPpair = nP ?? System.Security.Cryptography.RandomNumberGenerator.GetBytes(16);
        var b = new byte[Wire.PairStartSize];
        WireCodec.EncodePairStart(new PairStart(PhoneId, _key.PublicKey, PairingMath.Commit(_key.PublicKey, _nPpair), Name), b);
        return b;
    }

    public bool OnPairHost(byte[] d)
    {
        if (!WireCodec.TryDecodePairHost(d, out var h)) return false;
        AttemptId = h.AttemptId;
        _pkH = h.PkH;
        _nHpair = h.NH;
        _hostId = h.HostId;
        _th = PairingMath.Th(PhoneId, _hostId, _key!.PublicKey, _pkH, _nPpair, _nHpair, AttemptId);
        Sas = PairingMath.SasDisplay(PairingMath.Sas(_th));
        return true;
    }

    public byte[] PairReveal(byte[]? nP = null)
    {
        var b = new byte[Wire.PairRevealSize];
        WireCodec.EncodePairReveal(new PairReveal(AttemptId, PhoneId, nP ?? _nPpair), b);
        return b;
    }

    public byte[] PairConfirm(bool corrupt = false)
    {
        var z = _key!.Ecdh(_pkH!);
        var pk = PairingMath.PairKey(z, _th!);
        var c = PairingMath.ConfirmP(pk, _th!);
        if (corrupt) c[0] ^= 1;
        var b = new byte[Wire.PairConfirmSize];
        WireCodec.EncodePairConfirm(new PairConfirm(AttemptId, PhoneId, c), b);
        return b;
    }

    /// <summary>Verifies an ACCEPTED PAIR_DONE's confirmH and stores the pairKey.</summary>
    public bool OnPairDone(byte[] d, out byte status)
    {
        status = 0xFF;
        if (!WireCodec.TryDecodePairDone(d, out var done)) return false;
        status = done.Status;
        if (done.Status != PairDoneStatus.Accepted) return false;
        var pk = PairingMath.PairKey(_key!.Ecdh(_pkH!), _th!);
        if (!PairingMath.ConfirmH(pk, _th!).AsSpan().SequenceEqual(done.ConfirmH)) return false;
        PairKey = pk;
        return true;
    }

    public byte[] Hello(byte[]? nP = null)
    {
        HelloNonce = nP ?? System.Security.Cryptography.RandomNumberGenerator.GetBytes(16);
        var b = new byte[Wire.HelloSize];
        WireCodec.EncodeHello(new Hello(PhoneId, HelloNonce, HelloCaps.Rumble, 1000), PairKey!, b);
        return b;
    }

    public bool OnWelcome(byte[] d)
    {
        if (!WireCodec.TryDecodeWelcome(d, out var w) || !WireCodec.VerifyMac(PairKey!, d)) return false;
        if (!w.NP.AsSpan().SequenceEqual(HelloNonce)) return false;
        SessionId = w.SessionId;
        var (kPH, kHP) = SessionKeys.Derive(PairKey!, HelloNonce, w.NH, w.SessionId);
        _tx?.Dispose();
        _rx?.Dispose();
        _tx = new SessionCipher(kPH, Wire.DirPhoneToHost);
        _rx = new SessionCipher(kHP, Wire.DirHostToPhone);
        Seq = 0;
        RxHighest = 0;
        return true;
    }

    public byte[] Sealed(byte type, ReadOnlySpan<byte> plain)
    {
        var b = new byte[Wire.Header + plain.Length + Wire.GcmTag];
        _tx!.Seal(type, SessionId, ++Seq, plain, b);
        return b;
    }

    public byte[] State(Action<StateFrame>? change = null)
    {
        change?.Invoke(Frame);
        var p = new byte[Wire.StatePlain];
        Frame.Encode(p);
        return Sealed(PacketType.State, p);
    }

    public byte[] Ping(ulong t1)
    {
        var p = new byte[Wire.PingPlain];
        WireCodec.EncodePing(new Ping(t1), p);
        return Sealed(PacketType.Ping, p);
    }

    public byte[] Bye(byte reason = ByeReason.User)
    {
        var p = new byte[Wire.ByePlain];
        WireCodec.EncodeReason(reason, p);
        return Sealed(PacketType.Bye, p);
    }

    /// <summary>Opens a sealed H->P packet; returns its type or 0.</summary>
    public byte TryOpen(byte[] d, out byte[] plain)
    {
        plain = [];
        if (_rx == null || d.Length < Wire.Header + Wire.GcmTag || !PacketType.IsSealed(d[3])) return 0;
        if (Le.U32(d, 4) != SessionId) return 0;
        var p = new byte[d.Length - Wire.Header - Wire.GcmTag];
        if (!_rx.Open(d, p)) return 0;
        RxHighest = Math.Max(RxHighest, Le.U32(d, 8));
        plain = p;
        return d[3];
    }

    public void Dispose()
    {
        _tx?.Dispose();
        _rx?.Dispose();
        _key?.Dispose();
    }
}

/// <summary>A HostEngine wired to fakes, with one phone already paired.</summary>
internal sealed class Harness : IDisposable
{
    public readonly FakeClock Clock = new();
    public readonly FakeSender Sender = new();
    public readonly FakePad Pad = new();
    public readonly FakeKeyMouse KeyMouse = new();
    public readonly FakePrompt Prompt = new();
    public readonly RecordingEvents Events = new();
    public readonly HostSettings Settings;
    public readonly HostEngine Engine;

    public Harness(Func<int, byte[]>? random = null, Func<P256>? newKey = null, bool pairDefault = true,
        OutputMode mode = OutputMode.Xbox360)
    {
        Settings = new HostSettings { HostId = Hex.Range(0x10, 16), HostName = "DESKTOP-OLEX", OutputMode = mode };
        if (pairDefault) AddPaired(Phone1Id, "OnePlus 15", Phone1Key);
        Engine = new HostEngine(Settings, Pad, KeyMouse, Prompt, new PlainProtector(), Clock, Events, random, newKey);
        Engine.AttachSender(Sender);
    }

    public static readonly byte[] Phone1Id = Hex.Range(0x00, 16);
    public static readonly byte[] Phone1Key = Hex.Range(0x40, 32);

    public void AddPaired(byte[] id, string name, byte[] key) =>
        Settings.PairedPhones.Add(new PairedPhone { PhoneId = id, Name = name, ProtectedKey = key });

    public FakePhone NewPhone(string ip = "192.168.1.50", int port = 40000) =>
        FakePhone.Paired(Phone1Id, "OnePlus 15", Phone1Key, ip, port);

    /// <summary>Delivers one datagram from the phone and flushes, like one rx loop iteration.</summary>
    public byte[]? Send(FakePhone phone, byte[] d, bool flush = true)
    {
        int before = Sender.Sent.Count;
        Engine.OnDatagram(d, phone.Addr, Clock.NowUs);
        if (flush) Engine.Flush(Clock.NowUs);
        return Sender.Sent.Count > before ? Sender.Sent[before].Data : null;
    }

    /// <summary>HELLO, WELCOME and a first STATE: an active session.</summary>
    public void Connect(FakePhone phone)
    {
        var w = Send(phone, phone.Hello());
        Assert.NotNull(w);
        Assert.True(phone.OnWelcome(w!));
        Send(phone, phone.State());
    }

    public void Tick(long advanceMs)
    {
        Clock.AdvanceMs(advanceMs);
        Engine.OnTick(Clock.NowUs);
    }

    public void Dispose() { }
}
