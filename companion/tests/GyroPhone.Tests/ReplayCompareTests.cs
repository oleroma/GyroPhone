using System.Net;
using System.Text;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

/// <summary>
/// Replay files: the two input processes (this C# engine and the Rust one in companion/input) are given the
/// same packets, ticks and calls, and must do exactly the same with them: the same key and mouse events, pad
/// reports, packets back to the phone and log lines, in the same order.
///
/// Each file in companion/tests/replay/v1 is one made-up play session written by this engine: every input
/// line, and under it (lines starting with "= ") what the engine did because of it. This test makes each
/// session again and compares it with its file, so a change to the C# engine that changes what it does shows
/// here; the Rust test (companion/input/tests/replay.rs) feeds the file's input lines to the Rust engine and
/// compares what comes out. After a wanted change, write the files again with GP_REPLAY_WRITE=1 and port the
/// change to Rust until its test passes too.
///
/// Input lines:
///   mode n | paired idHex name keyHex | start us        (the header: settings, then the engine starts)
///   (the engine's random bytes and its pairing keys are fixed streams: see Random and PairKey)
///   rx us addr hex | flush us | tick us
///   call us name args..
///   pcpad connected held presses(18, comma) axes(6 float bit patterns, comma)
///   rumble large small
/// </summary>
public class ReplayCompareTests
{
    private static string Dir()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
        {
            string p = Path.Combine(d.FullName, "companion", "tests", "replay", "v1");
            if (Directory.Exists(p)) return p;
        }
        throw new DirectoryNotFoundException("companion/tests/replay/v1");
    }

    [Theory]
    [InlineData("keys-mouse")]
    [InlineData("xbox")]
    [InlineData("ds4-motion")]
    [InlineData("pc-gyro")]
    [InlineData("rough-link")]
    [InlineData("pairing")]
    public void TheSessionPlaysAsItsFileSays(string name)
    {
        string made = Make(name);
        string path = Path.Combine(Dir(), name + ".txt");
        if (Environment.GetEnvironmentVariable("GP_REPLAY_WRITE") == "1")
        {
            File.WriteAllText(path, made);
            return;
        }
        string[] want = File.ReadAllText(path).Replace("\r", "").Split('\n');
        string[] got = made.Split('\n');
        for (int i = 0; i < Math.Min(want.Length, got.Length); i++)
            Assert.True(want[i] == got[i], $"{name}.txt line {i + 1}:\n  file: {want[i]}\n  now:  {got[i]}");
        Assert.Equal(want.Length, got.Length);
    }

    [Fact]
    public void MakingASessionTwiceGivesTheSameLines()
    {
        Assert.Equal(Make("rough-link"), Make("rough-link"));
    }

    private static string Make(string name) => name switch
    {
        "keys-mouse" => new Session(1, OutputMode.None).Run(2500),
        "xbox" => new Session(2, OutputMode.Xbox360).Run(2500),
        "ds4-motion" => new Session(3, OutputMode.DualShock4) { Motion = true }.Run(2500),
        "pc-gyro" => new Session(4, OutputMode.Xbox360) { Gyro = true }.Run(3000),
        "rough-link" => new Session(5, OutputMode.DualShock4) { Motion = true, Gyro = true, Rough = true }.Run(4000),
        "pairing" => new Session(6, OutputMode.Xbox360, paired: false).RunPairing(60),
        _ => throw new ArgumentException(name),
    };

    /// <summary>Everything the engine does, as lines in the order it did it.</summary>
    private sealed class Lines : IPadSink, IKeyMouseSink, IDatagramSender, IPairPrompt, IHostEvents
    {
        public readonly StringBuilder Text = new();
        public readonly List<(byte[] Data, SocketAddress To)> Sent = new();
        public bool DriverOk => true;
        public bool DualSenseOk => false;
        public OutputMode Plugged { get; private set; }

        public void In(string line) => Text.Append(line).Append('\n');

        private void Out(string line) => Text.Append("= ").Append(line).Append('\n');

        public bool Plug(OutputMode mode)
        {
            Plugged = mode;
            Out($"plug {(int)mode}");
            return true;
        }

        public void Unplug()
        {
            Out($"unplug {(int)Plugged}");
            Plugged = OutputMode.None;
        }

        public void Submit(in X360Frame f) => Out($"x360 {f.Buttons},{f.Lx},{f.Ly},{f.Rx},{f.Ry},{f.Lt},{f.Rt}");

        public void Submit(in Ds4Frame f, in Ds4Motion m) =>
            Out($"ds4 {f.Buttons},{f.Special},{f.Hat},{f.Lx},{f.Ly},{f.Rx},{f.Ry},{f.Lt},{f.Rt} {m.Clock},{m.Gx},{m.Gy},{m.Gz},{m.Ax},{m.Ay},{m.Az}");

        public event Action<byte, byte>? Rumble;

        public void RaiseRumble(byte l, byte s) => Rumble?.Invoke(l, s);

        public bool Inject(ReadOnlySpan<InjectEvent> events)
        {
            var sb = new StringBuilder("inject");
            foreach (var e in events)
                sb.Append(' ').Append($"{(int)e.Kind},{e.Usage},{e.Scan},{e.Vk},{e.Flags},{e.Dx},{e.Dy},{e.MouseData}");
            Out(sb.ToString());
            return true;
        }

        public void Send(ReadOnlySpan<byte> datagram, SocketAddress to)
        {
            Sent.Add((datagram.ToArray(), HostSession.Copy(to)));
            Out($"tx {Addr(to)} {Hex.S(datagram)}");
        }

        public int Shows;

        public void Show(uint attemptId, string phoneName, string sasDisplay)
        {
            Shows++;
            Out($"show {attemptId} {phoneName} {sasDisplay}");
        }

        public void Close(uint attemptId) => Out($"close {attemptId}");

        public void StateChanged() { }

        public void Log(string message)
        {
            lock (Text) Out("log " + message.Replace("\n", "\\n"));
        }

        public static string Addr(SocketAddress a)
        {
            var ep = (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(a);
            return $"{ep.Address}:{ep.Port}";
        }
    }

    private sealed class Session
    {
        private readonly Lines _l = new();
        private readonly FakeClock _clock = new();
        private readonly FakePcPad _pcPad = new();
        private readonly HostEngine _engine;
        private readonly OutputMode _mode;
        private ulong _s;
        private uint _draws;
        private long _t = 1_000_000_000;
        private FakePhone _phone;
        private FakePhone _other;
        private bool _connected;
        private readonly List<byte[]> _old = new();
        private bool _paused, _locked;
        // The phone's gyro as it sends it with STATE_GYRO.
        private ushort _gyroSeq;
        private uint _gyroTime;
        private readonly long[] _totals = new long[3];
        private readonly List<(ushort Dt, short Y, short P, short R, byte Axes)> _samples = new();
        private readonly GyroMath.Config _gyroConfig = new();

        public bool Motion, Gyro, Rough;

        private static readonly byte[] OtherId = Hex.Range(0x80, 16);
        private static readonly byte[] OtherKey = Hex.Range(0x90, 32);

        public Session(ulong seed, OutputMode mode, bool paired = true)
        {
            _s = seed * 0x9E3779B97F4A7C15UL + 1;
            _mode = mode;
            var settings = new HostSettings { HostId = Hex.Range(0x10, 16), HostName = "DESKTOP-OLEX", OutputMode = mode };
            _l.In($"mode {(int)mode}");
            if (paired)
            {
                settings.PairedPhones.Add(new PairedPhone { PhoneId = Harness.Phone1Id, Name = "OnePlus 15", ProtectedKey = Harness.Phone1Key });
                settings.PairedPhones.Add(new PairedPhone { PhoneId = OtherId, Name = "Pixel", ProtectedKey = OtherKey });
                _l.In($"paired {Hex.S(Harness.Phone1Id)} OnePlus_15 {Hex.S(Harness.Phone1Key)}");
                _l.In($"paired {Hex.S(OtherId)} Pixel {Hex.S(OtherKey)}");
            }
            _engine = new HostEngine(settings, _l, _l, _l, new PlainProtector(), _clock, _l, Random, PairKey)
            {
                PadWorker = job => job(),
                EnrollWorker = job => job(),
            };
            _engine.AttachSender(_l);
            _engine.SetPcPad(_pcPad);
            _phone = FakePhone.Paired(Harness.Phone1Id, "OnePlus 15", Harness.Phone1Key);
            _other = FakePhone.Paired(OtherId, "Pixel", OtherKey, "192.168.1.77", 41000);
            _gyroConfig.Routes.Add(new GyroMath.Route(0, GyroMath.Route.MouseX, 1f));
            _gyroConfig.Routes.Add(new GyroMath.Route(1, GyroMath.Route.MouseY, -1f));
        }

        /// <summary>The engine's random bytes: a fixed stream, the same in the Rust test.</summary>
        private byte[] Random(int n)
        {
            var b = new byte[n];
            uint k = _draws++;
            for (int i = 0; i < n; i++) b[i] = (byte)(0xA5 ^ (k * 17 + (uint)i * 3));
            return b;
        }

        /// <summary>The engine's pairing keys: private key number n is the byte n + 1, then 31 bytes of 0x5A; the same in the Rust test.</summary>
        private P256 PairKey()
        {
            var d = new byte[32];
            Array.Fill(d, (byte)0x5A);
            d[0] = (byte)(++_pairKeys);
            return P256.FromPrivateScalar(d);
        }

        private int _pairKeys;

        private ulong Next()
        {
            _s ^= _s << 13;
            _s ^= _s >> 7;
            _s ^= _s << 17;
            return _s;
        }

        private int R(int below) => (int)(Next() % (ulong)below);

        private int R(int from, int to) => from + R(to - from + 1);

        private bool Chance(int percent) => R(100) < percent;

        private byte[] Bytes(int n)
        {
            var b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte)Next();
            return b;
        }

        private float F(float from, float to) => from + (to - from) * (R(10001) / 10000f);

        // ---- the input lines ----

        private void Rx(FakePhone from, byte[] d, bool flush = true)
        {
            _clock.NowUs = _t;
            _l.In($"rx {_t} {Lines.Addr(from.Addr)} {Hex.S(d)}");
            _engine.OnDatagram(d, from.Addr, _t);
            if (flush) Flush();
        }

        private void Flush()
        {
            _clock.NowUs = _t;
            _l.In($"flush {_t}");
            _engine.Flush(_t);
        }

        private void Tick()
        {
            _clock.NowUs = _t;
            _l.In($"tick {_t}");
            _engine.OnTick(_t);
        }

        private void Call(string name, string args, Action call)
        {
            _clock.NowUs = _t;
            _l.In($"call {_t} {name} {args}".TrimEnd());
            call();
        }

        private void PcPad()
        {
            _pcPad.Connected = !Chance(5);
            _pcPad.Held = Chance(30) ? 0 : (uint)Next() & 0x3FFFF;
            for (int i = 0; i < _pcPad.Presses.Length; i++)
                if (Chance(20)) _pcPad.Presses[i] += (uint)R(1, 3);
            for (int i = 0; i < _pcPad.Axes.Length; i++) _pcPad.Axes[i] = Chance(40) ? 0f : F(-1f, 1f);
            _l.In($"pcpad {(_pcPad.Connected ? 1 : 0)} {_pcPad.Held} {string.Join(',', _pcPad.Presses)} " +
                  string.Join(',', _pcPad.Axes.Select(a => BitConverter.SingleToUInt32Bits(a))));
        }

        // ---- the phone ----

        private void Hello(FakePhone phone)
        {
            int before = _l.Sent.Count;
            Rx(phone, phone.Hello(Bytes(16)));
            bool welcomed = false;
            for (int i = before; i < _l.Sent.Count; i++)
                if (_l.Sent[i].To.Equals(phone.Addr) && phone.OnWelcome(_l.Sent[i].Data)) welcomed = true;
            if (ReferenceEquals(phone, _phone)) _connected = welcomed;
        }

        private void ChangeFrame(StateFrame f)
        {
            f.PhoneTimeUs = (uint)(_t - 999_000_000 + R(-300, 300));
            f.Hires = Chance(70);
            if (Chance(3)) f.Flags = (ushort)R(16);
            else if (Chance(30)) f.Flags = (ushort)(f.Flags & ~StateFlags.LinkTest ^ (Chance(50) ? StateFlags.GyroActive : 0));
            if (Chance(25)) f.Buttons ^= (ushort)(1 << R(16));
            if (Chance(8)) f.Hat = (byte)R(10);
            if (Chance(10)) f.MouseButtons ^= (byte)(1 << R(6));
            if (Chance(40)) { f.Lx = (short)R(-32768, 32767); f.Ly = (short)R(-32768, 32767); }
            if (Chance(40)) { f.Rx = (short)R(-32768, 32767); f.Ry = (short)R(-32768, 32767); }
            if (Chance(20)) f.Lt = (ushort)R(0, 1100);
            if (Chance(20)) f.Rt = (ushort)R(0, 1100);
            int keys = Chance(25) ? R(1, 3) : 0;
            for (int i = 0; i < keys; i++)
            {
                // Mostly the keys a game uses, sometimes any bit of the bitmap.
                int usage = Chance(85) ? R(4, 0x65) : Chance(50) ? R(0xE0, 0xE7) : R(0, f.Keys.Length * 8 - 1);
                f.Keys[usage >> 3] ^= (byte)(1 << (usage & 7));
            }
            if (Chance(2)) Array.Clear(f.Keys);
            if (Chance(60)) { f.MouseTotalX += R(-40, 40); f.MouseTotalY += R(-40, 40); }
            if (Chance(1)) { f.MouseTotalX += R(-200000, 200000); f.MouseTotalY = unchecked(f.MouseTotalY * 7 + int.MaxValue / 3); }
            if (Chance(5)) f.WheelTotal += R(-3, 3) * (Chance(50) ? 120 : R(1, 40));
            if (Chance(3)) f.HWheelTotal += R(-240, 240);
        }

        private void AddGyroSamples()
        {
            int n = R(0, 3);
            for (int i = 0; i < n; i++)
            {
                ushort dt = (ushort)(Chance(90) ? R(1900, 2600) : R(0, 60000));
                short y = (short)(Chance(15) ? 0 : R(-6000, 6000)), p = (short)(Chance(15) ? 0 : R(-6000, 6000)), r = (short)R(-800, 800);
                if (Chance(1)) y = short.MaxValue;
                _gyroSeq++;
                _gyroTime += dt;
                _totals[0] = unchecked(_totals[0] + (long)y * dt);
                _totals[1] = unchecked(_totals[1] + (long)p * dt);
                _totals[2] = unchecked(_totals[2] + (long)r * dt);
                _samples.Add((dt, y, p, r, (byte)(Chance(80) ? 7 : R(8))));
            }
            if (Chance(2)) _gyroSeq += (ushort)R(1, 40); // samples the phone never sent
        }

        private byte[] GyroState()
        {
            ChangeFrame(_phone.Frame);
            AddGyroSamples();
            var f = _phone.Frame;
            var b = new byte[Wire.StateGyroPlain];
            bool hires = f.Hires;
            f.Hires = true;
            f.Encode(b);
            f.Hires = hires;
            var t = new PcGyroTail
            {
                Flags = (byte)((Chance(92) ? PcGyroTail.FlagMayMove : 0) | (Chance(85) ? PcGyroTail.FlagClock : 0)),
                NewestSeq = _gyroSeq,
                NewestTimeUs = _gyroTime,
                Tightening = Chance(50) ? 0f : F(0f, 8f),
                Smoothing = Chance(50) ? 0f : F(0f, 40f),
            };
            int n = Math.Min(PcGyroTail.MaxSamples, _samples.Count);
            t.Count = n;
            for (int i = 0; i < n; i++)
            {
                var s = _samples[_samples.Count - n + i];
                t.DtUs[i] = s.Dt;
                t.Rate[i * 3] = s.Y; t.Rate[i * 3 + 1] = s.P; t.Rate[i * 3 + 2] = s.R;
                t.Axes[i] = s.Axes;
                t.Up[i * 3] = (short)R(-16384, 16384); t.Up[i * 3 + 1] = (short)R(-16384, 16384); t.Up[i * 3 + 2] = (short)R(-16384, 16384);
            }
            _totals.CopyTo(t.Totals, 0);
            if (Chance(4)) ChangeGyroConfig(_gyroConfig);
            t.Config.CopyFrom(_gyroConfig);
            t.Encode(b.AsSpan(Wire.StateHiresPlain));
            return _phone.Sealed(PacketType.StateGyro, b);
        }

        private void ChangeGyroConfig(GyroMath.Config c)
        {
            for (int a = 0; a < 3; a++)
            {
                c.Sensitivity[a] = Chance(10) ? 0f : F(0.2f, 30f);
                c.GlideMs[a] = F(0f, 400f);
                c.Inertia[a] = Chance(30);
                c.Linear[a] = Chance(30);
                c.Space[a] = R(4);
                if (Chance(50)) c.SetCurve(a, [], false);
                else
                {
                    int points = R(2, GyroMath.MaxCurvePoints);
                    var flat = new float[points * 2];
                    float x = 0;
                    for (int j = 0; j < points; j++)
                    {
                        x += F(0.5f, 120f);
                        flat[2 * j] = x;
                        flat[2 * j + 1] = F(0f, 6f);
                    }
                    c.SetCurve(a, flat, Chance(50), Chance(50));
                }
            }
            c.Routes.Clear();
            int routes = R(0, PcGyroTail.MaxRoutes);
            for (int i = 0; i < routes; i++)
                c.Routes.Add(new GyroMath.Route(R(3), R(0, GyroMath.Route.Pad + 5), Chance(10) ? 0f : F(-3f, 3f)));
        }

        private byte[] PadGyroSet()
        {
            var c = new GyroMath.Config();
            ChangeGyroConfig(c);
            var b = new byte[Wire.PadGyroSetPlain];
            b[0] = (byte)R(0, 20);
            b[1] = (byte)R(0, 3);
            PcGyroTail.EncodeConfig(b.AsSpan(2), c, F(0f, 8f), F(0f, 40f));
            return _phone.Sealed(PacketType.PadGyroSet, b);
        }

        private byte[] State() => Gyro && Chance(70) ? GyroState() : _phone.State(ChangeFrame);

        private void Remember(byte[] d)
        {
            _old.Add(d);
            if (_old.Count > 64) _old.RemoveAt(0);
        }

        private void SendState()
        {
            var d = State();
            Remember(d);
            Rx(_phone, d);
        }

        /// <summary>A sealed packet of one of the other kinds, its body mostly random: whatever an authentic phone could send.</summary>
        private void Other()
        {
            byte[] kinds =
            [
                PacketType.LinkInfo, PacketType.PressTime, PacketType.PhoneStats, PacketType.Capture, PacketType.PadGyro,
                PacketType.Overlay, PacketType.OverlayLayout, PacketType.OverlaySamples, PacketType.OverlayFrames, PacketType.Motion,
                PacketType.Ping,
            ];
            byte kind = kinds[R(kinds.Length)];
            if (kind == PacketType.Motion && !Motion) kind = PacketType.LinkInfo;
            var plain = Bytes(PacketType.PlainSize(kind));
            if (Chance(30)) Array.Clear(plain, R(plain.Length), 0);
            if (Chance(20)) Array.Clear(plain);
            var d = _phone.Sealed(kind, plain);
            Remember(d);
            Rx(_phone, d);
        }

        private void Silence()
        {
            int ticks = R(3, 16);
            for (int i = 0; i < ticks; i++)
            {
                _t += R(200_000, 900_000);
                Tick();
            }
            _connected = false; // maybe not timed out yet: the HELLO that follows starts over either way
        }

        public string Run(int steps)
        {
            _clock.NowUs = _t;
            _l.In($"start {_t}");
            _engine.Start(_t);
            Hello(_phone);
            for (int step = 0; step < steps; step++)
            {
                _t += Chance(90) ? R(150, 4500) : R(4500, 60_000);
                if (!_connected)
                {
                    if (Chance(60)) Hello(_phone); else Tick();
                    continue;
                }
                if ((_paused || _locked) && Chance(8))
                {
                    if (_paused) Call("set_paused", "0", () => _engine.SetPaused(false));
                    if (_locked) Call("set_locked", "0", () => _engine.SetLocked(false));
                    _paused = _locked = false;
                }
                int r = R(1000);
                if (r < 560) SendState();
                else if (r < 640)
                {
                    int n = R(2, 5);
                    for (int i = 0; i < n; i++)
                    {
                        var d = State();
                        Remember(d);
                        Rx(_phone, d, flush: false);
                        _t += R(0, 120);
                    }
                    Flush();
                }
                else if (r < 770) Tick();
                else if (r < 800) Rx(_phone, _phone.Ping((ulong)Next()));
                else if (r < 850) Other();
                else if (r < 865 && Gyro) Rx(_phone, PadGyroSet());
                else if (r < 880 && Motion)
                {
                    // Motion as a phone sends it: a few packets in a row, flushed together.
                    int n = R(1, 3);
                    for (int i = 0; i < n; i++) Rx(_phone, _phone.Sealed(PacketType.Motion, Bytes(Wire.MotionPlain)), flush: false);
                    Flush();
                }
                else if (r < 895) { _l.In($"rumble {(byte)_s} {(byte)(_s >> 8)}"); _l.RaiseRumble((byte)_s, (byte)(_s >> 8)); }
                else if (r < 905) PcPad();
                else if (r < 915) Calls();
                else if (r < 920) Rx(_phone, _phone.Discover((uint)Next()));
                else if (!Rough) SendState();
                else if (r < 935 && _old.Count > 0) Rx(_phone, _old[R(_old.Count)]); // an old packet again
                else if (r < 945)
                {
                    var d = State();
                    d[R(d.Length)] ^= (byte)(1 << R(8)); // damaged on the way
                    Rx(_phone, d);
                }
                else if (r < 955) Rx(_phone, Bytes(R(0, 600))); // not ours at all
                else if (r < 962)
                {
                    var d = State();
                    Rx(_phone, d[..R(0, d.Length - 1)]); // cut short
                }
                else if (r < 970)
                {
                    _phone.Seq += (uint)R(1, 400); // a run of lost packets
                    SendState();
                }
                else if (r < 978)
                {
                    // The phone's address changed (Wi-Fi to hotspot): the same session from somewhere else.
                    _phone.Addr = new IPEndPoint(IPAddress.Parse($"192.168.{R(1, 3)}.{R(2, 250)}"), R(1024, 65000)).Serialize();
                    SendState();
                }
                else if (r < 985) Silence();
                else if (r < 991)
                {
                    Rx(_phone, _phone.Bye());
                    _connected = false;
                }
                else if (r < 996)
                {
                    Hello(_other); // another paired phone asks while this one plays
                    if (Chance(50)) Rx(_other, _other.State());
                    if (Chance(50)) SendState(); // the first phone, which may have lost its place
                    _connected = false;
                }
                else Hello(_phone); // the app started again
            }
            _t += 1000;
            Call("shutdown", "", _engine.Shutdown);
            return _l.Text.ToString();
        }

        /// <summary>Phones pairing with the code on both screens: done right, refused, cheated at and left half way.</summary>
        public string RunPairing(int phones)
        {
            _clock.NowUs = _t;
            _l.In($"start {_t}");
            _engine.Start(_t); // nothing is paired: the pairing window opens by itself
            for (int n = 0; n < phones; n++)
            {
                _t += R(1000, 400_000);
                var id = Bytes(16);
                var phone = new FakePhone(id, "Phone" + n, $"192.168.1.{R(2, 250)}", R(1024, 65000));
                if (Chance(15)) Call("close_pairing_window", "", _engine.ClosePairingWindow);
                if (Chance(70)) Call("open_pairing_window", "", _engine.OpenPairingWindow);
                if (Chance(40)) Rx(phone, phone.Discover((uint)Next()));
                var scalar = Bytes(32);
                scalar[0] = 1;
                using var key = P256.FromPrivateScalar(scalar);
                int before = _l.Sent.Count;
                Rx(phone, phone.PairStart(key, Bytes(16)));
                if (Chance(10)) Rx(phone, Bytes(R(0, 200)));
                var host = Reply(phone, before, PacketType.PairHost);
                if (host == null || !phone.OnPairHost(host))
                {
                    Wait();
                    continue;
                }
                if (Chance(8)) { Wait(); continue; } // the phone went away
                _t += R(500, 30_000);
                int shown = _l.Shows;
                Rx(phone, phone.PairReveal(Chance(8) ? Bytes(16) : null)); // sometimes not the nonce it committed to
                if (Chance(20)) Rx(phone, phone.PairReveal());
                _t += R(500, 3_000_000);
                if (Chance(15)) Tick();
                bool allow = !Chance(15);
                if (_l.Shows > shown && Chance(92)) Call("answer_pair", $"{phone.AttemptId} {(allow ? 1 : 0)}", () => _engine.AnswerPair(phone.AttemptId, allow));
                if (Chance(5)) Call("answer_pair", $"{(uint)Next()} 1", () => _engine.AnswerPair((uint)_s, true)); // an answer to nothing
                _t += R(500, 30_000);
                before = _l.Sent.Count;
                Rx(phone, phone.PairConfirm(corrupt: Chance(8)));
                if (Chance(20)) Rx(phone, phone.PairConfirm());
                var done = Reply(phone, before, PacketType.PairDone);
                if (done == null || !phone.OnPairDone(done, out _))
                {
                    Wait();
                    continue;
                }
                // Paired: it connects and plays a little.
                _phone = phone;
                _connected = false;
                _t += R(1000, 50_000);
                Hello(phone);
                for (int i = 0; _connected && i < 30; i++)
                {
                    _t += R(150, 4500);
                    if (Chance(85)) SendState(); else Tick();
                }
                if (_connected && Chance(50)) Rx(phone, phone.Bye());
                if (Chance(30)) Call("forget", Hex.S(id), () => _engine.Forget(id));
                Wait();
            }
            _t += 1000;
            Call("shutdown", "", _engine.Shutdown);
            return _l.Text.ToString();
        }

        private byte[]? Reply(FakePhone phone, int from, byte type)
        {
            for (int i = from; i < _l.Sent.Count; i++)
                if (_l.Sent[i].To.Equals(phone.Addr) && _l.Sent[i].Data.Length > 3 && _l.Sent[i].Data[3] == type) return _l.Sent[i].Data;
            return null;
        }

        /// <summary>Time passes: half-done pairings and quiet sessions run out.</summary>
        private void Wait()
        {
            int ticks = R(0, 8);
            for (int i = 0; i < ticks; i++)
            {
                _t += R(100_000, 9_000_000);
                Tick();
            }
        }

        private void Calls()
        {
            switch (R(8))
            {
                case 0:
                    _paused = !_paused;
                    Call("set_paused", _paused ? "1" : "0", () => _engine.SetPaused(_paused));
                    break;
                case 1:
                    _locked = !_locked;
                    Call("set_locked", _locked ? "1" : "0", () => _engine.SetLocked(_locked));
                    break;
                case 2:
                    Call("release_all", "", _engine.ReleaseAll);
                    break;
                case 3:
                    int ms = R(0, 12);
                    Call("set_catch_up_ms", ms.ToString(), () => _engine.SetCatchUpMs(ms));
                    break;
                case 4:
                    var mode = (OutputMode)R(0, 2);
                    Call("set_output_mode", ((int)mode).ToString(), () => _engine.SetOutputMode(mode));
                    break;
                case 5:
                    bool a = Chance(50), b = Chance(50);
                    Call("set_environment", $"{(a ? 1 : 0)} {(b ? 1 : 0)}", () => _engine.SetEnvironment(a, b));
                    break;
                case 6:
                    bool on = Chance(50), c = Chance(50), s = Chance(50), k = Chance(50);
                    Call("set_overlay", $"{(on ? 1 : 0)} {(c ? 1 : 0)} {(s ? 1 : 0)} {(k ? 1 : 0)}", () => _engine.SetOverlay(on, c, s, k));
                    break;
                default:
                    byte bits = (byte)R(256);
                    Call("set_pc_setup", bits.ToString(), () => _engine.SetPcSetup(bits));
                    break;
            }
        }
    }
}
