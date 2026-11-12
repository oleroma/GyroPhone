using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// "Press a key" on the phone: while its screen is open (CAPTURE on, resent every second), what is held on this PC's
/// keyboard, mouse and controller goes back as CAPTURED, on change and every <see cref="ResendUs"/>. Only read:
/// nothing is held back from Windows or the game. A press stays in the list for <see cref="HoldOverUs"/> after it
/// was last seen, so a quick tap between two ticks, or one lost CAPTURED, still reaches the phone.
/// </summary>
public sealed class PressCapture
{
    public const long HoldOverUs = 150_000;
    public const long ResendUs = 200_000;
    private const float AxisPressed = 0.5f;

    // OverlayFrame Pad places (IPcPad.Read) to XInput button bits; the triggers (places 6, 7) go as axes, capture (11) has none.
    private static readonly ushort[] PlaceXInput =
        [0x1000, 0x2000, 0x4000, 0x8000, 0x0100, 0x0200, 0, 0, 0x0020, 0x0010, 0x0400, 0, 0x0040, 0x0080, 0x0001, 0x0002, 0x0004, 0x0008];

    private readonly List<(PcPress Press, long SeenUs)> _seen = new();
    private readonly PcPress[] _read = new PcPress[64];
    private readonly PcPress[] _now = new PcPress[Wire.CapturedMax];
    private readonly PcPress[] _sent = new PcPress[Wire.CapturedMax];
    private int _sentCount = -1;
    private long _sentUs = long.MinValue;
    private long _untilUs = long.MinValue;
    private readonly uint[] _presses = new uint[PlaceXInput.Length];
    private readonly uint[] _lastPresses = new uint[PlaceXInput.Length];
    private bool _pressesKnown;
    private readonly float[] _axes = new float[6];

    /// <summary>The phone listens.</summary>
    public bool On => _untilUs != long.MinValue;

    /// <summary>CAPTURE arrived with <paramref name="flags"/>; true when listening started or stopped.</summary>
    public bool OnCapture(byte flags, long nowUs)
    {
        bool was = On;
        if ((flags & CaptureFlags.On) != 0) _untilUs = nowUs + Wire.CaptureTimeoutMs * 1000L;
        else Stop();
        return was != On;
    }

    /// <summary>No CAPTURE for <see cref="Wire.CaptureTimeoutMs"/>: the phone closed its screen or went away.</summary>
    public bool Expired(long nowUs) => On && nowUs >= _untilUs;

    public void Stop()
    {
        _untilUs = long.MinValue;
        _seen.Clear();
        _sentCount = -1;
        _sentUs = long.MinValue;
        _pressesKnown = false;
    }

    /// <summary>
    /// Reads <paramref name="keys"/> and <paramref name="pad"/>; writes a CAPTURED plaintext into <paramref name="b"/>
    /// and returns its length when it is due (changed, or <see cref="ResendUs"/> since the last), else 0.
    /// </summary>
    public int Tick(IKeyMouseListener keys, IPcPad pad, long nowUs, Span<byte> b)
    {
        if (!On) return 0;
        int n = keys.Read(_read);
        for (int i = 0; i < n; i++) Saw(_read[i], nowUs);
        if (pad.Read(_presses, out uint held))
        {
            for (int p = 0; p < PlaceXInput.Length; p++)
            {
                if (PlaceXInput[p] == 0) continue;
                bool tapped = _pressesKnown && _presses[p] != _lastPresses[p];
                if ((held & (1u << p)) != 0 || tapped) Saw(new PcPress(PcPress.PadButton, PlaceXInput[p]), nowUs);
            }
            Array.Copy(_presses, _lastPresses, _presses.Length);
            _pressesKnown = true;
            if (pad.ReadAxes(_axes))
            {
                for (int a = 0; a < 6; a++)
                {
                    float v = _axes[a];
                    if (Math.Abs(v) > AxisPressed) Saw(new PcPress(PcPress.PadAxis, (ushort)a, (sbyte)(v > 0 ? 1 : -1)), nowUs);
                }
            }
        }
        else _pressesKnown = false;

        _seen.RemoveAll(s => nowUs - s.SeenUs > HoldOverUs);
        int count = Math.Min(_seen.Count, Wire.CapturedMax);
        for (int i = 0; i < count; i++) _now[i] = _seen[i].Press;
        bool changed = count != _sentCount || !_now.AsSpan(0, count).SequenceEqual(_sent.AsSpan(0, count));
        if (!changed && nowUs - _sentUs < ResendUs) return 0;
        Array.Copy(_now, _sent, count);
        _sentCount = count;
        _sentUs = nowUs;
        return WireCodec.EncodeCaptured(_now.AsSpan(0, count), b);
    }

    private void Saw(PcPress p, long nowUs)
    {
        for (int i = 0; i < _seen.Count; i++)
        {
            if (_seen[i].Press != p) continue;
            _seen[i] = (p, nowUs);
            return;
        }
        _seen.Add((p, nowUs));
    }
}
