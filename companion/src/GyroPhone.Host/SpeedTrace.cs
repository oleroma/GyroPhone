namespace GyroPhone.Host;

/// <summary>
/// Recent yaw, pitch and roll speeds for the Speed graph overlay, as the phone's GyroTrace keeps them:
/// the phone's gyro turns averaged into buckets of 1 / <see cref="RateHz"/> s, the last
/// <see cref="MaxSeconds"/> kept, each bucket marked when a ratchet was held during it. Written by
/// the engine as OVERLAY (and its samples) come in, read by the overlay windows' threads.
/// </summary>
public sealed class SpeedTrace
{
    public const int RateHz = 120;
    public const int MaxSeconds = 8;
    public const int Capacity = RateHz * MaxSeconds;
    private const float BucketS = 1f / RateHz;

    private readonly float[] _yaw = new float[Capacity];
    private readonly float[] _pitch = new float[Capacity];
    private readonly float[] _roll = new float[Capacity];
    private readonly bool[] _held = new bool[Capacity];
    private readonly object _lock = new();
    private int _head, _count;
    private float _accT, _accYaw, _accPitch, _accRoll;
    private bool _accHeld;
    private long _version;

    /// <summary>Bumped on every new bucket.</summary>
    public long Version => Interlocked.Read(ref _version);

    /// <summary>One turn: <paramref name="yaw"/>, <paramref name="pitch"/>, <paramref name="roll"/> degrees over <paramref name="seconds"/>, <paramref name="ratchet"/> held during it.</summary>
    public void Add(float yaw, float pitch, float roll, float seconds, bool ratchet)
    {
        if (!(seconds > 0f) || seconds > 1f) return;
        lock (_lock)
        {
            _accT += seconds;
            _accYaw += yaw;
            _accPitch += pitch;
            _accRoll += roll;
            _accHeld |= ratchet;
            if (_accT < BucketS) return;
            _yaw[_head] = _accYaw / _accT;
            _pitch[_head] = _accPitch / _accT;
            _roll[_head] = _accRoll / _accT;
            _held[_head] = _accHeld;
            _head = (_head + 1) % Capacity;
            if (_count < Capacity) _count++;
            _accT = _accYaw = _accPitch = _accRoll = 0f;
            _accHeld = false;
            Interlocked.Increment(ref _version);
        }
    }

    public void Clear()
    {
        lock (_lock)
        {
            _head = _count = 0;
            _accT = _accYaw = _accPitch = _accRoll = 0f;
            _accHeld = false;
            Interlocked.Increment(ref _version);
        }
    }

    /// <summary>Copies the newest buckets, up to <paramref name="n"/> (and the arrays' size), oldest first; returns how many.</summary>
    public int Read(int n, float[] yaw, float[] pitch, float[] roll, bool[] held)
    {
        lock (_lock)
        {
            int k = Math.Min(Math.Min(n, _count), yaw.Length);
            int i = (_head - k + Capacity) % Capacity;
            for (int j = 0; j < k; j++)
            {
                yaw[j] = _yaw[i]; pitch[j] = _pitch[i]; roll[j] = _roll[i]; held[j] = _held[i];
                i = (i + 1) % Capacity;
            }
            return k;
        }
    }
}
