namespace GyroPhone.Host;

/// <summary>
/// The phone's short moves (up, down, left, right, toward and away) for the 3D phone overlay's
/// "Show movement", as the phone's <c>PhoneMove</c> (core) makes them for its 3D phone display.
/// A phone can't know where it is: it only feels pushes. Pushes added up twice give a position,
/// but their errors grow into meters within seconds, so this shows nudges only: the push less
/// gravity (followed by the gyro, so a turn isn't taken for a move) adds up into a speed that fades
/// on its own and a position that glides back to the middle once the phone is nearly still, and
/// goes back at once on a ratchet press (<see cref="Center"/>). Fed once per OVERLAY with the
/// frame's averaged accelerometer reading. Frames as <see cref="OverlayPose"/>: x right, y up,
/// z toward the player; the position is in the view (meters). Not thread safe.
/// </summary>
public sealed class PhoneMove
{
    /// <summary>Standard gravity, m/s².</summary>
    public const float G = 9.80665f;
    private const float DegToRad = MathF.PI / 180f;
    private const float MaxDt = 0.1f;
    private const float CalmG = 0.4f;
    private const float GFollowS = 0.5f;
    private const float GFollowMovingS = 8f;
    private const float Noise = 0.06f;
    private const float SpeedFadeS = 0.5f;
    private const float ShakeS = 0.08f;
    private const float StillForS = 0.1f;
    private const float MaxM = 0.5f;

    private float _gx, _gy, _gz;
    private bool _hasG;
    private float _vx, _vy, _vz;
    private float _shake, _still;

    public float X { get; private set; }
    public float Y { get; private set; }
    public float Z { get; private set; }

    /// <summary>Turned by the gyro (degrees on yaw, pitch, roll, as OVERLAY carries them): gravity turns with the phone.</summary>
    public void Turn(float yawDeg, float pitchDeg, float rollDeg)
    {
        if (!_hasG) return;
        float ax = pitchDeg * DegToRad, ay = -yawDeg * DegToRad, az = -rollDeg * DegToRad;
        // A vector fixed in the world, seen from a phone turning by a: g -= a × g.
        float nx = _gx - (ay * _gz - az * _gy);
        float ny = _gy - (az * _gx - ax * _gz);
        float nz = _gz - (ax * _gy - ay * _gx);
        _gx = nx; _gy = ny; _gz = nz;
    }

    /// <summary>
    /// An accelerometer reading in g (phone frame) over <paramref name="dt"/> seconds; <paramref name="view"/>
    /// the pose shown (row-major 3×3, phone into view). It glides back, 95% in <paramref name="returnSeconds"/>,
    /// once it shakes less than <paramref name="stillBelow"/> m/s².
    /// </summary>
    public void Push(float upX, float upY, float upZ, float dt, float[]? view, float stillBelow, float returnSeconds)
    {
        float x = upX * G, y = upY * G, z = upZ * G;
        if (!_hasG)
        {
            _gx = x; _gy = y; _gz = z;
            _hasG = true;
            return;
        }
        dt = Math.Clamp(dt, 0f, MaxDt);
        if (dt == 0f) return;
        float l = MathF.Sqrt(x * x + y * y + z * z);
        bool calm = MathF.Abs(l - G) < CalmG;
        float pull = Math.Min(dt / (calm ? GFollowS : GFollowMovingS), 1f);
        _gx += (x - _gx) * pull; _gy += (y - _gy) * pull; _gz += (z - _gz) * pull;
        float lx = x - _gx, ly = y - _gy, lz = z - _gz;
        float a = MathF.Sqrt(lx * lx + ly * ly + lz * lz);
        _shake += (a - _shake) * Math.Min(dt / ShakeS, 1f);
        if (a < Noise) lx = ly = lz = 0f;
        if (view != null)
        {
            float rx = view[0] * lx + view[1] * ly + view[2] * lz;
            float ry = view[3] * lx + view[4] * ly + view[5] * lz;
            float rz = view[6] * lx + view[7] * ly + view[8] * lz;
            lx = rx; ly = ry; lz = rz;
        }
        float fade = MathF.Exp(-dt / SpeedFadeS);
        _vx = (_vx + lx * dt) * fade;
        _vy = (_vy + ly * dt) * fade;
        _vz = (_vz + lz * dt) * fade;
        float px = X + _vx * dt, py = Y + _vy * dt, pz = Z + _vz * dt;
        _still = _shake < stillBelow ? _still + dt : 0f;
        if (_still >= StillForS)
        {
            float back = MathF.Exp(-3f * dt / Math.Max(returnSeconds, 0.05f));
            px *= back; py *= back; pz *= back;
            _vx *= back; _vy *= back; _vz *= back;
        }
        float d = MathF.Sqrt(px * px + py * py + pz * pz);
        if (d > MaxM)
        {
            float s = MaxM / d;
            px *= s; py *= s; pz *= s;
        }
        X = px; Y = py; Z = pz;
    }

    /// <summary>Back to the middle at once (a ratchet press), at rest.</summary>
    public void Center()
    {
        _vx = _vy = _vz = 0f;
        X = Y = Z = 0f;
        _still = 0f;
    }

    /// <summary>Starts again, with no gravity yet.</summary>
    public void Reset()
    {
        Center();
        _hasG = false;
        _shake = 0f;
    }
}
