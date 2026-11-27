using System.Numerics;
using GyroPhone.Protocol;

namespace GyroPhone.Host.Models3D;

/// <summary>
/// What moves on a controller model, and how: face buttons, D-pad, − + Home Capture sink as pressed; sticks tilt
/// round their ball and sink when clicked; bumpers sink into the top edge; triggers swing round their hinge as far as
/// they are pulled. Found by the parts' names (gp_… for GP's handles, 8b_… for the 8BitDo). Every move runs through a
/// spring (<see cref="RigState"/>), so a button bounces a little as it goes down and comes back, and a stick let go
/// swings past the middle and settles: what is shown follows the controller within a few frames and then holds still.
/// </summary>
public sealed class ControllerRig
{
    public enum Motion
    {
        /// <summary>Sinks along −z (into the face) by <see cref="Group.Travel"/> cm.</summary>
        Press,
        /// <summary>Sinks along −y (into the top edge).</summary>
        Bumper,
        /// <summary>Swings round a hinge along x at its top back edge, by the pull.</summary>
        Trigger,
        /// <summary>Tilts round its centre's back toward the held direction (a one-piece D-pad).</summary>
        Rock,
        /// <summary>Tilts round <see cref="Group.Pivot"/> by the stick, and sinks when clicked.</summary>
        Stick,
    }

    /// <summary>Parts that move together.</summary>
    public sealed class Group
    {
        public required Motion Motion { get; init; }
        /// <summary>The button that moves it (<see cref="OverlayFrame.PadSouth"/>..); for a stick, its click.</summary>
        public required uint Button { get; init; }
        public required int[] Parts { get; init; }
        public required Vector3 Pivot { get; init; }
        /// <summary>How far it moves: cm for a press, degrees for a tilt or swing.</summary>
        public required float Travel { get; init; }
        /// <summary>A stick's axis pair (0 left, 2 right) or a trigger's side (0 left, 1 right).</summary>
        public int Axis { get; init; }
        /// <summary>The size of its front, cm (for the glow round a pressed button).</summary>
        public float Size { get; init; }
        /// <summary>It faces the viewer from the controller's front (its glow is drawn round it when that faces you).</summary>
        public bool Front { get; init; }
    }

    public Model3D Model { get; }
    public IReadOnlyList<Group> Groups => _groups;
    private readonly List<Group> _groups = [];

    /// <summary>Face buttons by place (south, east, west, north) and the D-pad's directions.</summary>
    private static readonly uint[] DpadButtons = [OverlayFrame.PadUp, OverlayFrame.PadDown, OverlayFrame.PadLeft, OverlayFrame.PadRight];

    public ControllerRig(Model3D model)
    {
        Model = model;
        string p = model.Parts.Any(x => x.Name.StartsWith("8b_", StringComparison.Ordinal)) ? "8b_" : "gp_";
        var used = new HashSet<int>();

        int Part(string name) => model.PartIndex(name);
        // A leader and the parts that move with it: those named after it ("8b_A_label"), less holes and lights in the
        // body round it (…_gap, …_led), and any named in <paramref name="also"/>.
        int[] With(string leader, params string[] also)
        {
            var list = new List<int>();
            int at = Part(leader);
            if (at >= 0) list.Add(at);
            for (int i = 0; i < model.Parts.Length; i++)
            {
                string n = model.Parts[i].Name;
                if (i != at && n.StartsWith(leader + "_", StringComparison.Ordinal) && !n.Contains("_gap") && !n.Contains("_led")) list.Add(i);
            }
            foreach (var a in also)
                if (Part(a) is >= 0 and var ai && !list.Contains(ai)) list.Add(ai);
            list.RemoveAll(used.Contains);
            return [.. list];
        }
        void Add(Motion motion, uint button, int[] parts, Vector3 pivot, float travel, int axis = 0, float size = 0f, bool front = false)
        {
            if (parts.Length == 0) return;
            foreach (var i in parts) used.Add(i);
            _groups.Add(new Group { Motion = motion, Button = button, Parts = parts, Pivot = pivot, Travel = travel, Axis = axis, Size = size, Front = front });
        }
        Vector3 Size(int part) => part < 0 ? Vector3.Zero : model.Parts[part].Max - model.Parts[part].Min;

        // Sticks first (their caps are named like nothing else), then the rest.
        for (int s = 0; s < 2; s++)
        {
            string side = s == 0 ? "l" : "r";
            int ball = Part(p + "stick_" + side + "_ball");
            int[] parts = [.. new[] { "cap", "stem", "ball" }.Select(x => Part(p + "stick_" + side + "_" + x)).Where(i => i >= 0)];
            var pivot = ball >= 0 ? model.Parts[ball].Centre : parts.Length > 0 ? model.Parts[parts[0]].Centre : Vector3.Zero;
            int cap = Part(p + "stick_" + side + "_cap");
            Add(Motion.Stick, s == 0 ? OverlayFrame.PadL3 : OverlayFrame.PadR3, parts, pivot, 20f, s * 2, Math.Max(Size(cap).X, Size(cap).Y), true);
        }

        // Face buttons by where they are: the highest north, the lowest south, then west and east.
        var face = new[] { "A", "B", "X", "Y" }.Select(x => p + x).Where(n => Part(n) >= 0).ToList();
        if (face.Count == 4)
        {
            var byY = face.OrderBy(n => model.Parts[Part(n)].Centre.Y).ToList();
            string south = byY[0], north = byY[3];
            var mid = byY.Skip(1).Take(2).OrderBy(n => model.Parts[Part(n)].Centre.X).ToList();
            foreach (var (name, button) in new[] { (south, OverlayFrame.PadSouth), (mid[1], OverlayFrame.PadEast), (mid[0], OverlayFrame.PadWest), (north, OverlayFrame.PadNorth) })
            {
                var sz = Size(Part(name));
                Add(Motion.Press, button, With(name), model.Parts[Part(name)].Centre, Math.Min(0.16f, sz.Z * 0.6f), size: Math.Max(sz.X, sz.Y), front: true);
            }
        }

        // D-pad: four buttons (GP), or one that rocks (8BitDo).
        string[] dirs = ["up", "down", "left", "right"];
        for (int d = 0; d < 4; d++)
            if (Part(p + dirs[d]) is >= 0 and var di)
            {
                var sz = Size(di);
                Add(Motion.Press, DpadButtons[d], With(p + dirs[d]), model.Parts[di].Centre, Math.Min(0.16f, sz.Z * 0.6f), size: Math.Max(sz.X, sz.Y), front: true);
            }
        if (Part(p + "dpad") is >= 0 and var dp)
        {
            var c = model.Parts[dp].Centre;
            var sz = Size(dp);
            Add(Motion.Rock, 0, With(p + "dpad"), new Vector3(c.X, c.Y, model.Parts[dp].Min.Z), 7f, size: Math.Max(sz.X, sz.Y), front: true);
        }

        // − + Home Capture.
        (string Name, uint Button, string[] Also)[] small = p == "8b_"
            ? [("8b_minus", OverlayFrame.PadMinus, []), ("8b_plus", OverlayFrame.PadPlus, []), ("8b_home", OverlayFrame.PadHome, []), ("8b_star", OverlayFrame.PadCapture, [])]
            : [("gp_pm_l", OverlayFrame.PadMinus, ["gp_pm_mark_l"]), ("gp_pm_r", OverlayFrame.PadPlus, ["gp_pm_mark_r", "gp_plus_mark"]),
               ("gp_home", OverlayFrame.PadHome, []), ("gp_capture", OverlayFrame.PadCapture, [])];
        foreach (var (name, button, also) in small)
            if (Part(name) is >= 0 and var si)
            {
                var sz = Size(si);
                Add(Motion.Press, button, With(name, also), model.Parts[si].Centre, Math.Min(0.07f, sz.Z * 0.7f), size: Math.Max(sz.X, sz.Y), front: true);
            }

        // Bumpers and triggers, with their labels.
        for (int s = 0; s < 2; s++)
        {
            string side = s == 0 ? "l" : "r";
            if (Part(p + "bumper_" + side) is >= 0 and var bi)
                Add(Motion.Bumper, s == 0 ? OverlayFrame.PadL1 : OverlayFrame.PadR1, With(p + "bumper_" + side, p + "bumper_label_" + side),
                    model.Parts[bi].Centre, 0.12f, s);
            if (Part(p + "trigger_" + side) is >= 0 and var ti)
            {
                var t = model.Parts[ti];
                Add(Motion.Trigger, s == 0 ? OverlayFrame.PadL2 : OverlayFrame.PadR2, With(p + "trigger_" + side, p + "trigger_label_" + side),
                    new Vector3(t.Centre.X, t.Max.Y, t.Max.Z), 16f, s);
            }
        }
    }

    /// <summary>
    /// Each part's move (model space, cm) and glow (0..1) for <paramref name="state"/>: parts that don't move keep the
    /// identity and 0. <paramref name="moves"/> and <paramref name="glows"/> are as long as the model's parts.
    /// </summary>
    public void Pose(RigState state, Matrix4x4[] moves, float[] glows)
    {
        Array.Fill(moves, Matrix4x4.Identity);
        Array.Clear(glows);
        for (int g = 0; g < _groups.Count; g++)
        {
            var grp = _groups[g];
            var (a, b) = state.At(g);
            float glow = state.Glow(g);
            Matrix4x4 m;
            switch (grp.Motion)
            {
                case Motion.Press:
                    m = Matrix4x4.CreateTranslation(0f, 0f, -grp.Travel * a);
                    break;
                case Motion.Bumper:
                    m = Matrix4x4.CreateTranslation(0f, -grp.Travel * a, 0f);
                    break;
                case Motion.Trigger:
                    // The lower end swings toward the grip (+z) as it is pulled.
                    m = About(grp.Pivot, Matrix4x4.CreateRotationX(-a * grp.Travel * MathF.PI / 180f));
                    break;
                case Motion.Rock:
                case Motion.Stick:
                {
                    // Toward (a, b) (y down, as the controller reports): the top leans that way.
                    var dir = new Vector2(a, -b);
                    float len = MathF.Min(dir.Length(), 1.2f);
                    m = Matrix4x4.Identity;
                    if (len > 1e-4f)
                    {
                        var axis = Vector3.Normalize(new Vector3(-dir.Y, dir.X, 0f));
                        m = About(grp.Pivot, Matrix4x4.CreateFromAxisAngle(axis, len * grp.Travel * MathF.PI / 180f));
                    }
                    if (grp.Motion == Motion.Stick)
                        m *= Matrix4x4.CreateTranslation(0f, 0f, -0.12f * state.Click(g));
                    break;
                }
                default:
                    m = Matrix4x4.Identity;
                    break;
            }
            foreach (var i in grp.Parts)
            {
                moves[i] = m;
                glows[i] = glow;
            }
        }
    }

    private static Matrix4x4 About(Vector3 pivot, Matrix4x4 turn) =>
        Matrix4x4.CreateTranslation(-pivot) * turn * Matrix4x4.CreateTranslation(pivot);
}

/// <summary>
/// One overlay window's springs for a <see cref="ControllerRig"/>: what each group shows, a step behind the controller.
/// Springs are slightly under-damped (a small bounce) and stop dead once settled, so a still controller draws nothing new.
/// </summary>
public sealed class RigState
{
    /// <summary>A damped spring toward a target (semi-implicit, in steps of at most 2 ms).</summary>
    public struct Spring
    {
        public float X, V;

        /// <summary>Moves toward <paramref name="target"/> over <paramref name="dt"/> seconds; false once settled (it then rests on the target).</summary>
        public bool Step(float target, float dt, float hz, float damping)
        {
            if (X == target && V == 0f) return false;
            float w = 2f * MathF.PI * hz;
            float k = w * w, c = 2f * damping * w;
            while (dt > 0f)
            {
                float h = MathF.Min(dt, 0.002f);
                V += (k * (target - X) - c * V) * h;
                X += V * h;
                dt -= h;
            }
            if (MathF.Abs(target - X) < 0.002f && MathF.Abs(V) < 0.03f)
            {
                X = target;
                V = 0f;
                return false;
            }
            return true;
        }
    }

    /// <summary>Press and pull springs: quick with a small bounce. Sticks: a little slower, swinging past the middle when let go.</summary>
    public const float PressHz = 11f, PressDamping = 0.42f, StickHz = 8f, StickDamping = 0.5f, TriggerHz = 12f, TriggerDamping = 0.6f;
    /// <summary>How long a released button's glow takes to fade (s, to a third).</summary>
    public const float GlowFade = 0.16f;

    private readonly ControllerRig _rig;
    private readonly Spring[] _a, _b, _click;
    private readonly float[] _glow;
    private double _last = -1;

    public RigState(ControllerRig rig)
    {
        _rig = rig;
        int n = rig.Groups.Count;
        _a = new Spring[n];
        _b = new Spring[n];
        _click = new Spring[n];
        _glow = new float[n];
    }

    public ControllerRig Rig => _rig;

    internal (float A, float B) At(int group) => (_a[group].X, _b[group].X);

    internal float Click(int group) => _click[group].X;

    /// <summary>Group <paramref name="group"/>'s glow, 0..1.</summary>
    public float Glow(int group) => _glow[group];

    /// <summary>Group <paramref name="group"/>'s press now (a stick's click), 0..1 and a little past while it bounces.</summary>
    public float Press(int group) => _rig.Groups[group].Motion == ControllerRig.Motion.Stick ? _click[group].X : _a[group].X;

    /// <summary>
    /// Steps every spring toward what <paramref name="f"/> holds (null: nothing held) at <paramref name="now"/> (seconds); true
    /// while any still moves (keep drawing). After a gap of more than a fifth of a second (the first picture, or the
    /// window idle) everything jumps to where it should be.
    /// </summary>
    public bool Step(OverlayFrame? f, double now)
    {
        float dt = _last < 0 ? 1f : (float)Math.Max(0.0, now - _last);
        _last = now;
        bool jump = dt > 0.2f;
        bool moving = false;
        var groups = _rig.Groups;
        for (int g = 0; g < groups.Count; g++)
        {
            var grp = groups[g];
            float ta = 0f, tb = 0f, tc = 0f, lit;
            bool held = f != null && grp.Button != 0 && f.PadHeld(grp.Button);
            float hz = PressHz, damping = PressDamping;
            switch (grp.Motion)
            {
                case ControllerRig.Motion.Stick:
                    if (f != null)
                    {
                        ta = Math.Clamp(f.PadSticks[grp.Axis], -1f, 1f);
                        tb = Math.Clamp(f.PadSticks[grp.Axis + 1], -1f, 1f);
                    }
                    tc = held ? 1f : 0f;
                    (hz, damping) = (StickHz, StickDamping);
                    lit = tc;
                    break;
                case ControllerRig.Motion.Rock:
                    if (f != null)
                    {
                        ta = (f.PadHeld(OverlayFrame.PadRight) ? 1f : 0f) - (f.PadHeld(OverlayFrame.PadLeft) ? 1f : 0f);
                        tb = (f.PadHeld(OverlayFrame.PadDown) ? 1f : 0f) - (f.PadHeld(OverlayFrame.PadUp) ? 1f : 0f);
                    }
                    lit = ta != 0f || tb != 0f ? 1f : 0f;
                    break;
                case ControllerRig.Motion.Trigger:
                    ta = f == null ? 0f : Math.Clamp(grp.Axis == 0 ? f.PadL2Pull : f.PadR2Pull, 0f, 1f);
                    if (held) ta = Math.Max(ta, 1f);
                    (hz, damping) = (TriggerHz, TriggerDamping);
                    lit = ta;
                    break;
                default:
                    ta = held ? 1f : 0f;
                    lit = ta;
                    break;
            }
            if (jump)
            {
                _a[g] = new Spring { X = ta };
                _b[g] = new Spring { X = tb };
                _click[g] = new Spring { X = tc };
                _glow[g] = lit;
                continue;
            }
            moving |= _a[g].Step(ta, dt, hz, damping);
            moving |= _b[g].Step(tb, dt, hz, damping);
            moving |= _click[g].Step(tc, dt, PressHz, PressDamping);
            // Glow: on at once, fading out after.
            float glow = _glow[g];
            if (lit >= glow) glow = lit;
            else
            {
                glow = lit + (glow - lit) * MathF.Exp(-dt / GlowFade);
                if (glow - lit < 0.01f) glow = lit;
            }
            if (glow != _glow[g]) moving = true;
            _glow[g] = glow;
        }
        return moving;
    }

    /// <summary>A number that changes whenever what the springs show does (for the picture's key).</summary>
    public int Stamp()
    {
        var h = new HashCode();
        for (int g = 0; g < _a.Length; g++)
        {
            h.Add(_a[g].X); h.Add(_b[g].X); h.Add(_click[g].X); h.Add(_glow[g]);
        }
        return h.ToHashCode();
    }
}
