using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The phone overlay's pose, made on the PC from the phone's motion (OVERLAY) with the overlay's
/// own settings, as the phone's gyro phone view makes its pose (GyroPose and GyroDisplayFeed in the
/// phone's core, ported): turned by the gyro, or held to gravity (and the compass), reset on a
/// ratchet press, eased back to straight. Frame: x right, y up, z toward the player. Not thread
/// safe: the engine feeds and reads it under its lock.
/// </summary>
public sealed class OverlayPose
{
    /// <summary>Below this much (screen sideways, now times at rest) the edges help find the heading.</summary>
    private const float EdgesBelow = 0.5f;
    private const float DegToRad = MathF.PI / 180f;
    private const float Kp = 1.5f;
    private const float KpSettle = 20f;
    private const float Kc = 1f;
    private const float SettleS = 0.3f;
    private const float GSlack = 0.15f;
    /// <summary>Slower than this (deg/s, all axes) counts as the phone being still.</summary>
    private const float StillDps = 5f;

    private float _w = 1, _x, _y, _z;
    private float _upX, _upY, _upZ;
    private bool _hasUp;
    private float _northX, _northY, _northZ;
    private bool _hasNorth;
    private float _northAt;
    private bool _aimPending = true;
    private float _settling = SettleS;

    private float _calm;
    private bool _wasGravity, _wasCompass, _wasTrackball, _started;
    private int _lastPresses = -1;
    private float[]? _rest;

    // A ratchet reset gliding back (Reset smoothly): what it turns back, how fast and for how long.
    private bool _glide, _glideHeading;
    private float _glideSpeed, _glideLeft;
    private long _lastFeedAt;

    public float W => _w;
    public float X => _x;
    public float Y => _y;
    public float Z => _z;

    /// <summary>Straight again; the next frame starts the ratchet count afresh.</summary>
    public void Reset()
    {
        ResetPose();
        _lastPresses = -1;
        _calm = 0;
    }

    private void ResetPose()
    {
        _glide = false;
        _w = 1; _x = _y = _z = 0;
        _hasUp = false;
        _settling = SettleS;
        _aimPending = true;
    }

    /// <summary>One OVERLAY: turns the pose by its motion with settings <paramref name="s"/>.</summary>
    public void Feed(OverlayFrame f, OverlaySettings s)
    {
        bool trackball = s.IsTrackball;
        bool gravity = s.Gravity && !trackball;
        bool compass = gravity && s.Compass;
        // Face-on is the Level grip; it is kept only with gravity (without, Level restarts the pose).
        _rest = gravity ? s.Rest : null;
        if (!_started || gravity != _wasGravity || trackball != _wasTrackball)
        {
            _started = true;
            _wasGravity = gravity;
            _wasTrackball = trackball;
            ResetPose();
        }
        if (trackball)
        {
            _lastPresses = f.RatchetPresses;
            Trackball(f, s);
            return;
        }
        if (compass && !_wasCompass) _aimPending = true;
        _wasCompass = compass;

        bool pressed = _lastPresses >= 0 && f.RatchetPresses != _lastPresses;
        _lastPresses = f.RatchetPresses;

        if (f.HasUp && gravity) SetUp(f.UpX, f.UpY, f.UpZ, 1f);
        if (f.HasNorth && compass) SetNorth(f.NorthX, f.NorthY, f.NorthZ);

        // Time since the last frame, kept apart from the turn's: the glide goes on while the gyro is paused.
        long now = System.Diagnostics.Stopwatch.GetTimestamp();
        float wall = _lastFeedAt == 0 ? 0f : Math.Min((now - _lastFeedAt) / (float)System.Diagnostics.Stopwatch.Frequency, 0.1f);
        _lastFeedAt = now;

        if (pressed && s.ResetOnRatchet)
        {
            // With gravity the tilt is the world's: only the heading can go back.
            bool headingOnly = gravity || s.ResetHeadingOnly;
            if (headingOnly && compass) _aimPending = true;
            else if (s.ResetSmoothly) StartGlide(headingOnly, s.ResetSeconds);
            else if (headingOnly) TurnVertical(HeadingFrom(_rest));
            else ResetPose();
        }
        if (_glide) Glide(wall);

        float dt = f.TurnSeconds;
        if (!f.PoseLive || dt <= 0f) return;
        float yaw = f.TurnYaw / dt, pitch = f.TurnPitch / dt, roll = f.TurnRoll / dt;
        if (gravity) Fuse(yaw, pitch, roll, dt, compass);
        else if (f.Axes != 0)
        {
            float k = DegToRad * dt;
            Turn(((f.Axes & 2) != 0 ? pitch : 0f) * k, -((f.Axes & 1) != 0 ? yaw : 0f) * k, -((f.Axes & 4) != 0 ? roll : 0f) * k);
        }
        if (!s.Ease || compass || _glide) return;
        bool busy = s.EaseAfterRatchet ? f.RatchetHeld : MathF.Sqrt(yaw * yaw + pitch * pitch + roll * roll) > StillDps;
        _calm = busy ? 0f : _calm + dt;
        if (busy || _calm < Math.Clamp(s.EaseDelayMs, 0, 3000) / 1000f) return;
        // By time: an exponential, 95% of the way back after EaseSeconds.
        float amount = s.EaseBySpeed
            ? Math.Clamp(s.EaseSpeed, 1f, 3600f) * DegToRad * dt
            : 3f * dt / Math.Max(s.EaseSeconds, 0.05f);
        if (gravity) EaseHeading(s.EaseBySpeed, amount); else EaseAll(s.EaseBySpeed, amount);
    }

    /// <summary>
    /// A trackball: while the gyro moves the mouse it holds still against the world (turns the other
    /// way); every other mouse move turns it as the gyro turn that would send it.
    /// </summary>
    private void Trackball(OverlayFrame f, OverlaySettings s)
    {
        float Gain(float rws) => s.TrackballRws ? (rws > 0f ? rws : 1f) : Math.Clamp(s.TrackballSpin, 0.05f, 20f);
        float gy = Gain(f.RwsYaw), gp = Gain(f.RwsPitch), gr = Gain(f.RwsRoll);
        float yaw = f.CameraYaw * gy, pitch = f.CameraPitch * gp, roll = f.CameraRoll * gr;
        if (f.PoseLive)
        {
            if ((f.Axes & 1) != 0) yaw += f.TurnYaw * gy;
            if ((f.Axes & 2) != 0) pitch += f.TurnPitch * gp;
            if ((f.Axes & 4) != 0) roll += f.TurnRoll * gr;
        }
        CounterRotate(yaw, pitch, roll);
    }

    /// <summary>The world as the phone sees it while it turns by these degrees: the opposite turn, about the view's axes (q = b * q).</summary>
    private void CounterRotate(float yaw, float pitch, float roll)
    {
        float ax = -pitch * DegToRad, ay = yaw * DegToRad, az = roll * DegToRad;
        float angle = MathF.Sqrt(ax * ax + ay * ay + az * az);
        if (angle == 0f) return;
        float k = MathF.Sin(angle / 2) / angle;
        float bw = MathF.Cos(angle / 2), bx = ax * k, by = ay * k, bz = az * k;
        float nw = bw * _w - bx * _x - by * _y - bz * _z;
        float nx = bw * _x + bx * _w + by * _z - bz * _y;
        float ny = bw * _y - bx * _z + by * _w + bz * _x;
        float nz = bw * _z + bx * _y - by * _x + bz * _w;
        _w = nw; _x = nx; _y = ny; _z = nz;
        Normalize();
    }

    private void SetUp(float ax, float ay, float az, float g)
    {
        float l = MathF.Sqrt(ax * ax + ay * ay + az * az);
        if (l == 0f || l < g * (1 - GSlack) || l > g * (1 + GSlack)) return;
        _upX = ax / l; _upY = ay / l; _upZ = az / l;
        _hasUp = true;
    }

    private void SetNorth(float mx, float my, float mz)
    {
        float l = MathF.Sqrt(mx * mx + my * my + mz * mz);
        if (l == 0f) return;
        _northX = mx / l; _northY = my / l; _northZ = mz / l;
        _hasNorth = true;
    }

    private void Fuse(float yaw, float pitch, float roll, float dt, bool compass)
    {
        float ax = pitch * DegToRad, ay = -yaw * DegToRad, az = -roll * DegToRad;
        float w = _w, x = _x, y = _y, z = _z;
        if (_hasUp)
        {
            float px = 2 * (x * y + w * z);
            float py = 1 - 2 * (x * x + z * z);
            float pz = 2 * (y * z - w * x);
            float k = _settling > 0f ? KpSettle : Kp;
            ax += k * (_upY * pz - _upZ * py);
            ay += k * (_upZ * px - _upX * pz);
            az += k * (_upX * py - _upY * px);
            _settling -= dt;
        }
        bool settled = _settling <= 0f;
        Turn(ax * dt, ay * dt, az * dt);
        if (compass && _hasNorth)
        {
            w = _w; x = _x; y = _y; z = _z;
            float nx = (1 - 2 * (y * y + z * z)) * _northX + 2 * (x * y - w * z) * _northY + 2 * (x * z + w * y) * _northZ;
            float nz = 2 * (x * z - w * y) * _northX + 2 * (y * z + w * x) * _northY + (1 - 2 * (x * x + y * y)) * _northZ;
            if (nx * nx + nz * nz > 0.01f)
            {
                float a = MathF.Atan2(nx, -nz);
                if (_aimPending)
                {
                    if (settled)
                    {
                        float h = HeadingFrom(_rest);
                        _northAt = a - h;
                        TurnVertical(h);
                        _aimPending = false;
                    }
                }
                else TurnVertical(Wrap(a - _northAt) * Math.Min(dt * Kc, 1f));
            }
        }
    }

    /// <summary>
    /// The pose with its turn round the vertical taken out (w, x, y, z): only how it is tilted against
    /// gravity. That turn is the twist of the whole pose about the vertical, so tipping forward or back
    /// never counts, however far (past flat the screen faces the other way, which the heading by the
    /// screen would read as a half turn).
    /// </summary>
    public float[] TiltOnly()
    {
        float w = _w, x = _x, y = _y, z = _z;
        if (y * y + w * w > 1e-8f) TurnVertical(-2f * MathF.Atan2(y, w));
        float[] tilt = [_w, _x, _y, _z];
        _w = w; _x = x; _y = y; _z = z;
        return tilt;
    }

    /// <summary>
    /// The heading in radians (right +): how far the pose is turned round the vertical from straight;
    /// <see cref="TurnVertical"/> by it turns it straight again. See <see cref="HeadingFrom"/>.
    /// </summary>
    public float Heading() => HeadingFrom(null);

    /// <summary>
    /// The turn round the vertical that brings the screen back to face the way it faces in the resting
    /// grip <paramref name="rest"/> (w, x, y, z; null = straight). Measured by where the screen faces,
    /// so a turn about the screen's own axis is left alone. When the screen (now or at rest) faces
    /// nearly up or down that says little, so the phone's edges take over, smoothly: no jumps or flips.
    /// </summary>
    private float HeadingFrom(float[]? rest)
    {
        float rw = 1f, rx = 0f, ry = 0f, rz = 0f;
        if (rest is { Length: 4 } r) { rw = r[0]; rx = r[1]; ry = r[2]; rz = r[3]; }
        float w = _w, x = _x, y = _y, z = _z;
        // The phone's axes in the world, now (a) and at rest (b): screen normal, top edge, right edge.
        float anx = 2 * (x * z + w * y), anz = 1 - 2 * (x * x + y * y);
        float bnx = 2 * (rx * rz + rw * ry), bnz = 1 - 2 * (rx * rx + ry * ry);
        float sn = bnx * anz - bnz * anx, cn = bnx * anx + bnz * anz;
        float m = MathF.Sqrt(sn * sn + cn * cn);
        float k = Math.Max(0f, 1f - m / EdgesBelow);
        if (k > 0f)
        {
            float aux = 2 * (x * y - w * z), auz = 2 * (y * z + w * x);
            float bux = 2 * (rx * ry - rw * rz), buz = 2 * (ry * rz + rw * rx);
            float arx = 1 - 2 * (y * y + z * z), arz = 2 * (x * z - w * y);
            float brx = 1 - 2 * (ry * ry + rz * rz), brz = 2 * (rx * rz - rw * ry);
            sn += k * (bux * auz - buz * aux + brx * arz - brz * arx);
            cn += k * (bux * aux + buz * auz + brx * arx + brz * arz);
        }
        if (sn * sn + cn * cn < 1e-8f) return 0f;
        return MathF.Atan2(sn, cn);
    }

    /// <summary>Starts gliding back to straight (the heading, or the whole pose) over <paramref name="seconds"/>.</summary>
    private void StartGlide(bool headingOnly, float seconds)
    {
        seconds = Math.Clamp(seconds, 0.05f, 3f);
        float a = headingOnly ? MathF.Abs(HeadingFrom(_rest)) : 2 * MathF.Acos(Math.Min(MathF.Abs(_w), 1f));
        _glide = true;
        _glideHeading = headingOnly;
        _glideSpeed = a / seconds;
        _glideLeft = seconds;
    }

    /// <summary>One step of the glide, at a steady speed; when its time is up whatever is left goes at once.</summary>
    private void Glide(float dt)
    {
        _glideLeft -= dt;
        if (_glideLeft <= 0f)
        {
            _glide = false;
            if (_glideHeading) TurnVertical(HeadingFrom(_rest)); else ResetPose();
            return;
        }
        if (_glideHeading) EaseHeading(true, _glideSpeed * dt); else EaseAll(true, _glideSpeed * dt);
    }

    private void TurnHeading(float a)
    {
        if (a != 0f) TurnVertical(-a);
    }

    private void EaseHeading(bool bySpeed, float amount)
    {
        float h = HeadingFrom(_rest);
        if (h == 0f) return;
        TurnHeading(Eased(MathF.Abs(h), bySpeed, amount) * MathF.Sign(h) - h);
    }

    private void EaseAll(bool bySpeed, float amount)
    {
        if (_w < 0f) { _w = -_w; _x = -_x; _y = -_y; _z = -_z; }
        float a = 2 * MathF.Acos(Math.Min(_w, 1f));
        if (a < 1e-5f) return;
        float n = Eased(a, bySpeed, amount);
        float k = MathF.Sin(n / 2) / MathF.Sin(a / 2);
        _x *= k; _y *= k; _z *= k;
        _w = MathF.Cos(n / 2);
        Normalize();
    }

    private static float Wrap(float a)
    {
        float r = a % (2 * MathF.PI);
        if (r > MathF.PI) r -= 2 * MathF.PI; else if (r < -MathF.PI) r += 2 * MathF.PI;
        return r;
    }

    private static float Eased(float a, bool bySpeed, float amount) =>
        bySpeed ? Math.Max(a - amount, 0f) : a * (1f - Math.Clamp(amount, 0f, 1f));

    /// <summary>Turns by <paramref name="a"/> radians about the world's vertical: q = b * q.</summary>
    private void TurnVertical(float a)
    {
        float bw = MathF.Cos(a / 2), by = MathF.Sin(a / 2);
        float nw = bw * _w - by * _y;
        float nx = bw * _x + by * _z;
        float ny = bw * _y + by * _w;
        float nz = bw * _z - by * _x;
        _w = nw; _x = nx; _y = ny; _z = nz;
        Normalize();
    }

    /// <summary>Turns by a rotation vector (radians) about the phone's own axes: q = q * b.</summary>
    private void Turn(float ax, float ay, float az)
    {
        float angle = MathF.Sqrt(ax * ax + ay * ay + az * az);
        if (angle == 0f) return;
        float s = MathF.Sin(angle / 2) / angle;
        float bw = MathF.Cos(angle / 2), bx = ax * s, by = ay * s, bz = az * s;
        float nw = _w * bw - _x * bx - _y * by - _z * bz;
        float nx = _w * bx + _x * bw + _y * bz - _z * by;
        float ny = _w * by - _x * bz + _y * bw + _z * bx;
        float nz = _w * bz + _x * by - _y * bx + _z * bw;
        _w = nw; _x = nx; _y = ny; _z = nz;
        Normalize();
    }

    private void Normalize()
    {
        float l = MathF.Sqrt(_w * _w + _x * _x + _y * _y + _z * _z);
        if (!(l > 0f) || !float.IsFinite(l)) { _w = 1; _x = _y = _z = 0; return; }
        float n = 1f / l;
        _w *= n; _x *= n; _y *= n; _z *= n;
    }
}
