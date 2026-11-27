using System.Diagnostics;
using System.Drawing;
using System.Numerics;
using GyroPhone.Host.Models3D;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The phone overlay's real look (<see cref="OverlaySettings.RealLook"/>): the OnePlus 15 model and the chosen
/// controller's model (<see cref="OverlaySettings.HandleModel"/>), drawn by the graphics card with real materials and a
/// small studio's light (<see cref="Gpu3D.Frame.Lit"/>). The models sit on the overlay's slab: the phone's screen is
/// stretched onto the layout's screen, its picture laid on it (<see cref="Gpu3D.Panel"/>) under the glass's reflections.
/// The controller's buttons, sticks and triggers move on springs (<see cref="RigState"/>); the pose itself is shown
/// exactly as it comes. All of it is drawn again only when something in it changes, as the plain look.
/// </summary>
internal static class RealLook
{
    /// <summary>One window's models on the slab and its controller's springs.</summary>
    internal sealed class State
    {
        public Model3D Phone = null!;
        public ControllerRig? Rig;
        public RigState? Springs;
        public OverlayHandleModel? Shown;
        // The models placed on the slab: for the slab size it was made for.
        public float Sw, Sh;
        public Matrix4x4 Place;
        public float ScreenZ, Reach, ScaleZ;
        public Gpu3D.PartPose[] PhoneParts = [], ControllerParts = [];
        public Matrix4x4[] Moves = [];
        public float[] Glows = [];
        public readonly Gpu3D.Material[] Mats = new Gpu3D.Material[Gpu3D.MaterialRoom];
        public int MatsFor;
    }

    private static readonly double TickSeconds = 1.0 / Stopwatch.Frequency;

    /// <summary>
    /// The window's state with the models for <paramref name="controller"/> (null: the phone alone), placed on a slab
    /// whose screen is <paramref name="sw"/> × <paramref name="sh"/>.
    /// </summary>
    public static State For(ref object? kept, OverlayHandleModel? controller, float sw, float sh)
    {
        var st = kept as State;
        if (st == null)
        {
            st = new State { Phone = Model3D.Shipped(Model3D.OnePlus15) };
            st.PhoneParts = new Gpu3D.PartPose[st.Phone.Parts.Length];
            kept = st;
        }
        if (st.Shown != controller || (controller != null && st.Rig == null))
        {
            st.Shown = controller;
            if (controller is { } c)
            {
                st.Rig = new ControllerRig(Model3D.Shipped(c == OverlayHandleModel.EightBitDo ? Model3D.EightBitDo : Model3D.GpHandles));
                st.Springs = new RigState(st.Rig);
                st.ControllerParts = new Gpu3D.PartPose[st.Rig.Model.Parts.Length];
                st.Moves = new Matrix4x4[st.Rig.Model.Parts.Length];
                st.Glows = new float[st.Rig.Model.Parts.Length];
            }
            else
            {
                st.Rig = null;
                st.Springs = null;
            }
            st.Sw = 0f;
        }
        if (st.Sw != sw || st.Sh != sh) PlaceOn(st, sw, sh);
        return st;
    }

    /// <summary>
    /// Fits the models to the slab: the phone's screen onto the layout's (sw × sh, centred, facing +z), turned upright
    /// for a portrait layout (camera end on top), its depth scaled as its width and height on average.
    /// </summary>
    private static void PlaceOn(State st, float sw, float sh)
    {
        st.Sw = sw;
        st.Sh = sh;
        int si = st.Phone.PartIndex("op15_screen");
        var screen = si >= 0 ? st.Phone.Parts[si] : new Model3D.Part("", st.Phone.Min, st.Phone.Max);
        float mw = screen.Max.X - screen.Min.X, mh = screen.Max.Y - screen.Min.Y;
        var centre = new Vector3(screen.Centre.X, screen.Centre.Y, 0f);
        Matrix4x4 place;
        float sx, sy;
        if (sw >= sh)
        {
            (sx, sy) = (sw / mw, sh / mh);
            place = Matrix4x4.CreateTranslation(-centre) * Matrix4x4.CreateScale(sx, sy, (sx + sy) / 2f);
        }
        else
        {
            // Upright: the camera end (−x) goes to the top.
            (sx, sy) = (sw / mh, sh / mw);
            place = Matrix4x4.CreateTranslation(-centre) * Matrix4x4.CreateRotationZ(-MathF.PI / 2f) * Matrix4x4.CreateScale(sx, sy, (sx + sy) / 2f);
        }
        st.Place = place;
        st.ScaleZ = (sx + sy) / 2f;
        st.ScreenZ = screen.Max.Z * st.ScaleZ;
        float reach = Reach(st.Phone, place);
        if (st.Rig != null) reach = MathF.Max(reach, Reach(st.Rig.Model, place) * 1.04f);
        st.Reach = reach;
        // The phone's parts don't move.
        for (int i = 0; i < st.PhoneParts.Length; i++) st.PhoneParts[i] = Pose(place, 0f);
    }

    /// <summary>How far the model reaches from the slab's middle once placed (its box's farthest corner).</summary>
    private static float Reach(Model3D m, Matrix4x4 place)
    {
        float r = 0f;
        for (int i = 0; i < 8; i++)
        {
            var c = new Vector3((i & 1) == 0 ? m.Min.X : m.Max.X, (i & 2) == 0 ? m.Min.Y : m.Max.Y, (i & 4) == 0 ? m.Min.Z : m.Max.Z);
            r = MathF.Max(r, Vector3.Transform(c, place).Length());
        }
        return r;
    }

    /// <summary>A part moved by <paramref name="full"/> (model space to the slab) as the card reads it.</summary>
    private static Gpu3D.PartPose Pose(Matrix4x4 full, float glow)
    {
        Matrix4x4.Invert(full, out var inv);
        return new Gpu3D.PartPose
        {
            R0 = new Vector4(full.M11, full.M21, full.M31, full.M41),
            R1 = new Vector4(full.M12, full.M22, full.M32, full.M42),
            R2 = new Vector4(full.M13, full.M23, full.M33, full.M43),
            N0 = new Vector4(inv.M11, inv.M12, inv.M13, 0f),
            N1 = new Vector4(inv.M21, inv.M22, inv.M23, 0f),
            N2 = new Vector4(inv.M31, inv.M32, inv.M33, 0f),
            X = new Vector4(glow, 0f, 0f, 0f),
        };
    }

    /// <summary>
    /// Steps the controller's springs toward <paramref name="f"/> (null: nothing held); true while they still move
    /// (the window keeps drawing until they settle).
    /// </summary>
    public static bool Step(State st, OverlayFrame? f)
    {
        if (st.Rig == null || st.Springs == null) return false;
        bool moving = st.Springs.Step(f, Stopwatch.GetTimestamp() * TickSeconds);
        st.Rig.Pose(st.Springs, st.Moves, st.Glows);
        for (int i = 0; i < st.ControllerParts.Length; i++) st.ControllerParts[i] = Pose(st.Moves[i] * st.Place, st.Glows[i]);
        return moving;
    }

    /// <summary>What the springs show now, for the picture's key (0 without a controller).</summary>
    public static int Stamp(State st) => st.Springs?.Stamp() ?? 0;

    /// <summary>
    /// The materials for the colours <paramref name="body"/> (phone), <paramref name="handles"/> (controller):
    /// <see cref="Materials.Slots"/> in order.
    /// </summary>
    private static void FillMaterials(Gpu3D.Material[] mats, Color body, Color handles)
    {
        static Vector3 Lin(Color c) => new(Gpu3D.Linear(c.R), Gpu3D.Linear(c.G), Gpu3D.Linear(c.B));
        static Gpu3D.Material M(Vector3 baseColour, float metal, float rough, float coat = 0f, float kind = 0f, float sparkle = 0f, Vector3 emit = default) =>
            new() { Base = new Vector4(baseColour, metal), Surf = new Vector4(rough, coat, kind, sparkle), Emit = new Vector4(emit, 0f) };
        var b = Lin(body);
        var h = Lin(handles);
        var chrome = new Vector3(0.9f, 0.9f, 0.92f);
        Vector3 Grey(float v) => new(v, v, v);
        for (int i = 0; i < mats.Length; i++) mats[i] = M(h, 0f, 0.5f, kind: 6f);
        void Set(string name, Gpu3D.Material m) => mats[Materials.SlotOf(name)] = m;
        // Controller: soft-touch plastic in the handles' colour.
        Set("plastic", M(h, 0f, 0.52f, kind: 6f));
        // Phone: a metal frame tinted by the body, black glass round the screen, frosted glass back with a sparkle under a clear coat.
        Set("frame", M(b * 0.55f + Grey(0.32f), 1f, 0.3f, kind: 1f));
        Set("frontglass", M(Grey(0.004f), 0f, 0.05f));
        Set("backglass", M(b, 0f, 0.55f, coat: 0.85f, kind: 2f, sparkle: 0.55f));
        Set("plateau", M(b * 0.75f, 0f, 0.28f, coat: 0.6f));
        Set("lensglass", M(Grey(0.006f), 0f, 0.03f, coat: 1f));
        Set("lensring", M(new Vector3(0.72f, 0.72f, 0.74f), 1f, 0.2f));
        Set("lens", M(Grey(0.004f), 0f, 0.06f, kind: 4f));
        Set("flash", M(new Vector3(0.85f, 0.8f, 0.62f), 0f, 0.35f));
        Set("port", M(Grey(0.008f), 0f, 0.9f));
        Set("screen", M(Grey(0.01f), 0f, 0.03f, kind: 5f));
        // Controller details.
        Set("well", M(h * 0.4f, 0f, 0.7f, kind: 6f));
        Set("stem", M(chrome, 1f, 0.1f));
        Set("stickcap", M(Grey(0.02f), 0f, 0.72f, kind: 6f));
        Set("stickknurl", M(Grey(0.02f), 0f, 0.82f, kind: 3f));
        Set("stickmetal", M(chrome, 1f, 0.16f));
        Set("button", M(h * 0.55f + Grey(0.004f), 0f, 0.22f, coat: 0.35f));
        Set("backpad", M(Grey(0.015f), 0f, 0.9f, kind: 6f));
        Set("letter_yellow", M(new Vector3(0.95f, 0.72f, 0.08f), 0f, 0.3f, emit: new Vector3(0.12f, 0.09f, 0.01f)));
        Set("letter_red", M(new Vector3(0.88f, 0.1f, 0.08f), 0f, 0.3f, emit: new Vector3(0.11f, 0.012f, 0.01f)));
        Set("letter_green", M(new Vector3(0.12f, 0.72f, 0.22f), 0f, 0.3f, emit: new Vector3(0.015f, 0.09f, 0.03f)));
        Set("letter_blue", M(new Vector3(0.12f, 0.38f, 0.95f), 0f, 0.3f, emit: new Vector3(0.015f, 0.05f, 0.12f)));
    }

    /// <summary>
    /// Draws the real look into <paramref name="fr"/>: turned by <paramref name="m"/>, at centre <paramref name="c"/>,
    /// scaled <paramref name="k"/>, from camera <paramref name="camera"/>; the screen's picture <paramref name="panel"/>
    /// on the screen; pressed parts glowing in <paramref name="lit"/>.
    /// </summary>
    public static void Draw(Gpu3D.Frame fr, State st, float[] m, PointF c, float k, float camera, Gpu3D.Panel panel,
        Color body, Color handles, Color lit)
    {
        int matsFor = HashCode.Combine(body.ToArgb(), handles.ToArgb());
        if (st.MatsFor != matsFor || st.Mats[0].Base == Vector4.Zero)
        {
            FillMaterials(st.Mats, body, handles);
            st.MatsFor = matsFor;
        }
        var phone = fr.Model("model:" + Model3D.OnePlus15, st.Phone);
        fr.Lit(phone, st.PhoneParts, st.Mats, m, c, k, camera, st.Reach, 0, lit);
        if (st.Rig != null)
        {
            var name = st.Rig.Model.Name;
            var ctrl = fr.Model("model:" + name, st.Rig.Model);
            fr.Lit(ctrl, st.ControllerParts, st.Mats, m, c, k, camera, st.Reach, 0, lit);
        }
        // The screen's picture, a hair in front of the screen, hidden where the phone is in front of it.
        float z = st.ScreenZ + 0.002f;
        float hw = st.Sw / 2f, hh = st.Sh / 2f;
        fr.Panel(panel, new(-hw, hh, z), new(hw, hh, z), new(hw, -hh, z), new(-hw, -hh, z), m, c, k, camera, st.Reach);
        // The screen's glass over it.
        fr.Lit(phone, st.PhoneParts, st.Mats, m, c, k, camera, st.Reach, 1, lit);
        // A soft glow round each front button pressed, while the controller's front faces you.
        if (st.Rig is { } rig && st.Springs is { } sp && m[8] > 0.15f)
        {
            for (int g = 0; g < rig.Groups.Count; g++)
            {
                var grp = rig.Groups[g];
                float glow = sp.Glow(g);
                if (!grp.Front || glow < 0.02f) continue;
                var p = Vector3.Transform(grp.Pivot, st.Place);
                float vx = m[0] * p.X + m[1] * p.Y + m[2] * p.Z, vy = m[3] * p.X + m[4] * p.Y + m[5] * p.Z, vz = m[6] * p.X + m[7] * p.Y + m[8] * p.Z;
                float s = k * camera / (camera - vz);
                float r = MathF.Max(grp.Size * st.ScaleZ * s * 1.1f, 3f);
                fr.Glow(new PointF(c.X + vx * s, c.Y - vy * s), r, lit, glow * 0.45f * Math.Clamp(m[8], 0f, 1f));
            }
        }
    }

    /// <summary>The ball the models fill once placed (its radius), for fitting them to the window.</summary>
    public static float Radius(State st) => st.Reach;
}
