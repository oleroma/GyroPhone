using System.Drawing;
using System.Runtime.InteropServices;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The phone overlay's controller handles: two rounded slabs on the phone's left and right sides
/// (Switch style, one layout for every controller), as thick as the phone, with the face buttons,
/// D-pad, sticks, − + Home on the front, L1 / R1 on the top edge and L2 / R2 on the back
/// near the top. The shoulder buttons barely show from the screen side, so each handle's front also
/// has two pills by its top edge: the bumper's lights while pressed, the trigger's fills as it's pulled.
/// </summary>
internal static class HandleDrawing
{
    /// <summary>Each handle's width beside the phone, in handle heights: just room for a stick or the face buttons.</summary>
    public const float Width = 0.38f;
    /// <summary>How far L1 / R1 stand above the top edge, and L2 / R2 out of the back, in handle heights.</summary>
    public const float ShoulderDepth = 0.035f;
    private const float OuterRadius = 0.12f;
    private const float InnerRadius = 0.015f;

    /// <summary>
    /// The drawing's point arrays, made once per thread (the pacer's, or the UI thread's) and filled in
    /// place every picture: no new arrays or lists per frame. Each is filled, then drawn, before it is
    /// filled again (a canvas may reuse a filled polygon's shape for an outline of the same array).
    /// </summary>
    private sealed class Scratch
    {
        public readonly PointF[] Round = new PointF[20], Well = new PointF[28], Box = new PointF[4], Quad = new PointF[4];
        public readonly PointF[] Pill = new PointF[18];
        // The handle's outline and the 4-point bumper and trigger take turns: each keeps its own pair.
        public PointF[]? PillPart, Front, Back;
        public readonly PointF[] Front4 = new PointF[4], Back4 = new PointF[4];
        public readonly (float X, float Y)[] BumperRect = new (float, float)[4], TriggerRect = new (float, float)[4];
        public readonly (float X, float Y)[] Shape4 = new (float, float)[4], Stadium = new (float, float)[18];
        public readonly List<(float X, float Y)> Cut = new(40), CutScratch = new(40);
        // Each side's outline in the phone frame, for the sizes it was made for.
        private readonly (float X, float Y)[]?[] _outline = new (float X, float Y)[]?[2];
        private readonly (float W, float H, float X0)[] _outlineFor = new (float, float, float)[2];

        public (float X, float Y)[] OutlineFor(int side, float x0, float w, float h)
        {
            int k = side < 0 ? 0 : 1;
            if (_outline[k] is { } kept && _outlineFor[k] == (w, h, x0)) return kept;
            var outline = Outline(w, h);
            for (int i = 0; i < outline.Length; i++) outline[i] = (side * (x0 + outline[i].X), outline[i].Y);
            if (side < 0) Array.Reverse(outline);
            _outlineFor[k] = (w, h, x0);
            return _outline[k] = outline;
        }
    }

    [ThreadStatic] private static Scratch? t_scratch;

    private static PointF[] Sized(ref PointF[]? a, int n) => a != null && a.Length == n ? a : a = new PointF[n];

    /// <summary>The projection the phone is drawn with: phone-frame point to window point, a face's visibility, its shading.</summary>
    internal readonly record struct View(
        Func<(float X, float Y, float Z), PointF> Project,
        Func<(float X, float Y, float Z), (float X, float Y, float Z), bool> Faces,
        Func<(float X, float Y, float Z), float> Shade);

    /// <summary>The handles' sizes: their height <paramref name="h"/> (the phone's short side held landscape) and length <paramref name="w"/>.</summary>
    public static (float W, float H) Size(float landscapeHeight) => (landscapeHeight * Width, landscapeHeight);

    /// <summary>
    /// Whether the handle on side <paramref name="side"/> (−1 left, 1 right) is nearer the viewer
    /// than the phone (then it's drawn after the phone): the plane where they meet separates them.
    /// </summary>
    public static bool InFront(View v, int side, float phoneWidth) => v.Faces((side * phoneWidth / 2, 0, 0), (side, 0, 0));

    /// <summary>Draws the handle on side <paramref name="side"/> (−1 left, 1 right) of a phone <paramref name="phoneWidth"/> wide.</summary>
    public static void Draw(ICanvas g, View v, int side, float phoneWidth, float w, float h, float thickness, OverlayFrame? f,
        Color body, Color lit, Color idle, float penWidth)
    {
        float x0 = phoneWidth / 2;
        float zf = thickness / 2, zb = -thickness / 2;
        float X(float a) => side * (x0 + a);
        var buf = t_scratch ??= new Scratch();

        // The outline in the phone frame, counter-clockwise (y up).
        var outline = buf.OutlineFor(side, x0, w, h);

        bool l1 = f?.PadHeld(side < 0 ? OverlayFrame.PadL1 : OverlayFrame.PadR1) == true;
        float pull = f == null ? 0f : side < 0 ? f.PadL2Pull : f.PadR2Pull;
        bool l2 = f?.PadHeld(side < 0 ? OverlayFrame.PadL2 : OverlayFrame.PadR2) == true;

        // Shoulder buttons: L1 / R1 on the top edge, L2 / R2 out of the back near the top.
        float d = ShoulderDepth * h;
        float ba = 0.03f * h, bb = 0.25f * h; // inside the outer corner's curve
        var bumper = Rect(buf.BumperRect, Math.Min(X(ba), X(bb)), h / 2, Math.Max(X(ba), X(bb)), h / 2 + d);
        var trigger = Rect(buf.TriggerRect, Math.Min(X(ba), X(bb)), h / 2 - 0.2f * h, Math.Max(X(ba), X(bb)), h / 2);
        var bumperColor = l1 ? lit : Mix(body, Color.White, 0.08f);
        var triggerColor = Mix(Mix(body, Color.White, 0.08f), lit, l2 ? Math.Max(pull, 0.6f) : pull);
        void Bumper() => Prism(g, v, buf, bumper, zb, zf, bumperColor);
        void Trigger() => Prism(g, v, buf, trigger, zb - d, zb, triggerColor);

        bool topSeen = v.Faces((X(0.4f * h), h / 2, 0), (0, 1, 0));
        bool backSeen = v.Faces((X(0.4f * h), 0, zb), (0, 0, -1));
        // Each sits on one of the handle's faces: behind that face's plane it goes first, else after.
        // Between them (the plane y = h/2 parts them): the one nearer the camera last.
        // Top seen: trigger then bumper, the trigger after the handle only with the back seen and the
        // bumper always after; else bumper (before the handle) then trigger (after it with the back seen).
        bool firstAfter = topSeen && backSeen, secondAfter = topSeen || backSeen;
        void First() { if (topSeen) Trigger(); else Bumper(); }
        void Second() { if (topSeen) Bumper(); else Trigger(); }
        if (!firstAfter) First();
        if (!secondAfter) Second();
        bool front = Prism(g, v, buf, outline, zb, zf, body);
        if (firstAfter) First();
        if (secondAfter) Second();
        if (!front) return;

        // The front: everything a hair above it, by (a, b): a from the phone's edge outward, b down from the top.
        float zs = zf + 0.001f;
        PointF P(float x, float y) => v.Project((x, y, zs));
        Func<float, float, PointF> project = P; // one delegate for every circle
        float Y(float b) => h / 2 - b * h;
        var idleFill = Tint(body, 0.62f);

        void Round(float cx, float cy, float r, bool on, Color? ring = null)
        {
            var pts = buf.Round;
            CircleInto(pts, cx, cy, r, project);
            g.FillPolygon(on ? lit : idleFill, pts);
            g.DrawPolygon(ring ?? idle, penWidth, pts);
        }
        void Box(float x0b, float y0b, float x1b, float y1b, bool on)
        {
            var pts = buf.Box;
            (pts[0], pts[1], pts[2], pts[3]) = (P(x0b, y0b), P(x1b, y0b), P(x1b, y1b), P(x0b, y1b));
            g.FillPolygon(on ? lit : idleFill, pts);
            g.DrawPolygon(idle, penWidth * 0.75f, pts);
        }
        bool Held(uint b) => f?.PadHeld(b) == true;

        // Pills by the top edge, under the shoulder buttons: bumper (inner), trigger (outer, filled by the pull).
        Pill(g, v, buf, zs, X(0.04f * h), X(0.18f * h), Y(0.04f), Y(0.085f), l1 ? 1f : 0f, lit, idleFill, idle, penWidth);
        Pill(g, v, buf, zs, X(0.20f * h), X(0.34f * h), Y(0.04f), Y(0.085f), pull, lit, idleFill, idle, penWidth);

        // − / + and Home near the phone.
        float mid = X(Width / 2 * h); // every button and stick on the handle's middle line
        float near = X(0.07f * h); // −, + and Home by the phone's edge
        float sx = near, sy = Y(0.14f);
        float bl = 0.035f * h, bt = 0.011f * h;
        bool menu = Held(side < 0 ? OverlayFrame.PadMinus : OverlayFrame.PadPlus);
        Box(sx - bl, sy - bt, sx + bl, sy + bt, menu);
        if (side > 0) Box(sx - bt, sy - bl, sx + bt, sy + bl, menu);
        float hx = near, hy = Y(0.92f);
        if (side > 0) Round(hx, hy, 0.03f * h, Held(OverlayFrame.PadHome)); // no Capture: Android has no key for it

        // Left: stick above the D-pad. Right: the face buttons above the stick.
        float stickB = side < 0 ? 0.33f : 0.68f, padB = side < 0 ? 0.68f : 0.33f;
        Stick(mid, Y(stickB), side < 0 ? 0 : 2, side < 0 ? OverlayFrame.PadL3 : OverlayFrame.PadR3);
        float px = mid, py = Y(padB), o = 0.095f * h, r = 0.048f * h;
        if (side < 0)
        {
            Round(px, py + o, r, Held(OverlayFrame.PadUp));
            Round(px, py - o, r, Held(OverlayFrame.PadDown));
            Round(px - o, py, r, Held(OverlayFrame.PadLeft));
            Round(px + o, py, r, Held(OverlayFrame.PadRight));
        }
        else
        {
            Round(px, py + o, r, Held(OverlayFrame.PadNorth));
            Round(px, py - o, r, Held(OverlayFrame.PadSouth));
            Round(px - o, py, r, Held(OverlayFrame.PadWest));
            Round(px + o, py, r, Held(OverlayFrame.PadEast));
        }

        void Stick(float cx, float cy, int axis, uint click)
        {
            var well = buf.Well;
            CircleInto(well, cx, cy, 0.13f * h, project);
            g.FillPolygon(Tint(body, 0.45f), well);
            g.DrawPolygon(idle, penWidth, well);
            float sxv = f?.PadSticks[axis] ?? 0f, syv = f?.PadSticks[axis + 1] ?? 0f;
            float travel = 0.05f * h;
            float kx = cx + sxv * travel, ky = cy - syv * travel;
            bool clicked = Held(click);
            bool moved = MathF.Abs(sxv) > 0.15f || MathF.Abs(syv) > 0.15f;
            Round(kx, ky, 0.085f * h, clicked, moved ? lit : (Color?)null);
        }
    }

    /// <summary>A pill from x <paramref name="xa"/> to <paramref name="xb"/>, filled by <paramref name="fill"/> (0..1) from the phone's side.</summary>
    private static void Pill(ICanvas g, View v, Scratch buf, float z, float xa, float xb, float yTop, float yBottom, float fill,
        Color lit, Color idleFill, Color idle, float penWidth)
    {
        float x0 = Math.Min(xa, xb), x1 = Math.Max(xa, xb);
        float rr = (yTop - yBottom) / 2;
        var shape = Stadium(buf.Stadium, x0, yBottom, x1, yTop, rr);
        var pts = buf.Pill;
        for (int i = 0; i < shape.Length; i++) pts[i] = v.Project((shape[i].X, shape[i].Y, z));
        g.FillPolygon(idleFill, pts);
        if (fill > 0f)
        {
            // Filled from the phone's end (xa) toward the handle's outer end.
            float cut = xa + (xb - xa) * Math.Clamp(fill, 0f, 1f);
            var keep = Rect(buf.Shape4, Math.Min(xa, cut), yBottom - 1, Math.Max(xa, cut), yTop + 1);
            OverlayRenderer.ClipConvex(shape, keep, buf.Cut, buf.CutScratch);
            if (buf.Cut.Count >= 3)
            {
                var part = Sized(ref buf.PillPart, buf.Cut.Count);
                var kept = CollectionsMarshal.AsSpan(buf.Cut);
                for (int i = 0; i < kept.Length; i++) part[i] = v.Project((kept[i].X, kept[i].Y, z));
                g.FillPolygon(lit, part);
            }
        }
        g.DrawPolygon(idle, penWidth * 0.75f, pts);
    }

    /// <summary>
    /// A prism: <paramref name="outline"/> (counter-clockwise, y up) from z <paramref name="zb"/> to
    /// <paramref name="zf"/>, its sides and whichever end faces the viewer. True when the front end does.
    /// </summary>
    private static bool Prism(ICanvas g, View v, Scratch buf, (float X, float Y)[] outline, float zb, float zf, Color color)
    {
        var front = outline.Length == 4 ? buf.Front4 : Sized(ref buf.Front, outline.Length);
        var back = outline.Length == 4 ? buf.Back4 : Sized(ref buf.Back, outline.Length);
        for (int i = 0; i < outline.Length; i++)
        {
            front[i] = v.Project((outline[i].X, outline[i].Y, zf));
            back[i] = v.Project((outline[i].X, outline[i].Y, zb));
        }
        var quad = buf.Quad;
        float zm = (zb + zf) / 2;
        for (int i = 0; i < outline.Length; i++)
        {
            int j = (i + 1) % outline.Length;
            var a = outline[i];
            var b = outline[j];
            float nx = b.Y - a.Y, ny = a.X - b.X;
            float l = MathF.Sqrt(nx * nx + ny * ny);
            if (l == 0) continue;
            var n = (nx / l, ny / l, 0f);
            if (!v.Faces(((a.X + b.X) / 2, (a.Y + b.Y) / 2, zm), n)) continue;
            var side = Tint(color, v.Shade(n) * 0.85f);
            (quad[0], quad[1], quad[2], quad[3]) = (front[i], front[j], back[j], back[i]);
            g.FillPolygon(side, quad);
            g.DrawPolygon(side, 1f, quad);
        }
        bool faces = v.Faces((outline[0].X, outline[0].Y, zf), (0, 0, 1));
        g.FillPolygon(Tint(color, v.Shade(faces ? (0, 0, 1) : (0, 0, -1))), faces ? front : back);
        return faces;
    }

    /// <summary>A right handle's outline from its inner edge (x = 0) out to <paramref name="w"/>: big outer corners, small inner ones.</summary>
    internal static (float X, float Y)[] Outline(float w, float h)
    {
        float ro = Math.Min(OuterRadius * h, Math.Min(w, h) / 2), ri = InnerRadius * h;
        var pts = new List<(float, float)>();
        (float cx, float cy, float r, float start)[] corners =
        [
            (w - ro, -h / 2 + ro, ro, -MathF.PI / 2),
            (w - ro, h / 2 - ro, ro, 0),
            (ri, h / 2 - ri, ri, MathF.PI / 2),
            (ri, -h / 2 + ri, ri, MathF.PI),
        ];
        foreach (var (cx, cy, r, start) in corners)
            for (int i = 0; i <= 6; i++)
            {
                float a = start + i * (MathF.PI / 2) / 6;
                pts.Add((cx + r * MathF.Cos(a), cy + r * MathF.Sin(a)));
            }
        return pts.ToArray();
    }

    /// <summary>A rectangle, filled into <paramref name="pts"/> (4 long).</summary>
    private static (float X, float Y)[] Rect((float X, float Y)[] pts, float x0, float y0, float x1, float y1)
    {
        (pts[0], pts[1], pts[2], pts[3]) = ((x0, y0), (x1, y0), (x1, y1), (x0, y1));
        return pts;
    }

    /// <summary>A circle of as many points as <paramref name="pts"/> holds, projected by <paramref name="project"/>.</summary>
    private static void CircleInto(PointF[] pts, float cx, float cy, float r, Func<float, float, PointF> project)
    {
        int n = pts.Length;
        for (int i = 0; i < n; i++)
        {
            float a = i * 2 * MathF.PI / n;
            pts[i] = project(cx + r * MathF.Cos(a), cy + r * MathF.Sin(a));
        }
    }

    /// <summary>A rounded bar, counter-clockwise, filled into <paramref name="pts"/> (18 long).</summary>
    private static (float X, float Y)[] Stadium((float X, float Y)[] pts, float x0, float y0, float x1, float y1, float r)
    {
        float cy = (y0 + y1) / 2;
        for (int i = 0; i <= 8; i++) { float a = -MathF.PI / 2 + i * MathF.PI / 8; pts[i] = (x1 - r + r * MathF.Cos(a), cy + r * MathF.Sin(a)); }
        for (int i = 0; i <= 8; i++) { float a = MathF.PI / 2 + i * MathF.PI / 8; pts[9 + i] = (x0 + r + r * MathF.Cos(a), cy + r * MathF.Sin(a)); }
        return pts;
    }

    private static Color Tint(Color c, float k) =>
        Color.FromArgb(c.A, Math.Clamp((int)(c.R * k), 0, 255), Math.Clamp((int)(c.G * k), 0, 255), Math.Clamp((int)(c.B * k), 0, 255));

    private static Color Mix(Color a, Color b, float t)
    {
        t = Math.Clamp(t, 0f, 1f);
        return Color.FromArgb(255, (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
    }
}
