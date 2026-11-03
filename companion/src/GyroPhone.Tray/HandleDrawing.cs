using System.Drawing;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The phone overlay's controller handles: two rounded slabs on the phone's left and right sides
/// (Switch style, one layout for every controller), as thick as the phone, with the face buttons,
/// D-pad, sticks, − + Home Capture on the front, L1 / R1 on the top edge and L2 / R2 on the back
/// near the top. The shoulder buttons barely show from the screen side, so each handle's front also
/// has two pills by its top edge: the bumper's lights while pressed, the trigger's fills as it's pulled.
/// </summary>
internal static class HandleDrawing
{
    /// <summary>Each handle's length beside the phone, as a fraction of the phone's long side.</summary>
    public const float WidthOfPhone = 0.35f;
    /// <summary>How far L1 / R1 stand above the top edge, and L2 / R2 out of the back, in handle heights.</summary>
    public const float ShoulderDepth = 0.035f;
    private const float OuterRadius = 0.22f;
    private const float InnerRadius = 0.015f;

    /// <summary>The projection the phone is drawn with: phone-frame point to window point, a face's visibility, its shading.</summary>
    internal readonly record struct View(
        Func<(float X, float Y, float Z), PointF> Project,
        Func<(float X, float Y, float Z), (float X, float Y, float Z), bool> Faces,
        Func<(float X, float Y, float Z), float> Shade);

    /// <summary>The handles' sizes: their height <paramref name="h"/> (the phone's short side held landscape) and length <paramref name="w"/>.</summary>
    public static (float W, float H) Size(float longSide, float landscapeHeight) => (longSide * WidthOfPhone, landscapeHeight);

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

        // The outline in the phone frame, counter-clockwise (y up).
        var outline = Outline(w, h).Select(p => (side * (x0 + p.X), p.Y)).ToArray();
        if (side < 0) Array.Reverse(outline);

        bool l1 = f?.PadHeld(side < 0 ? OverlayFrame.PadL1 : OverlayFrame.PadR1) == true;
        float pull = f == null ? 0f : side < 0 ? f.PadL2Pull : f.PadR2Pull;
        bool l2 = f?.PadHeld(side < 0 ? OverlayFrame.PadL2 : OverlayFrame.PadR2) == true;

        // Shoulder buttons: L1 / R1 on the top edge, L2 / R2 out of the back near the top.
        float d = ShoulderDepth * h;
        float ba = 0.12f * h, bb = 0.5f * h; // inside the outer corner's curve
        var bumper = Rect(Math.Min(X(ba), X(bb)), h / 2, Math.Max(X(ba), X(bb)), h / 2 + d);
        var trigger = Rect(Math.Min(X(ba), X(bb)), h / 2 - 0.2f * h, Math.Max(X(ba), X(bb)), h / 2);
        var bumperColor = l1 ? lit : Mix(body, Color.White, 0.08f);
        var triggerColor = Mix(Mix(body, Color.White, 0.08f), lit, l2 ? Math.Max(pull, 0.6f) : pull);
        void Bumper() => Prism(g, v, bumper, zb, zf, bumperColor);
        void Trigger() => Prism(g, v, trigger, zb - d, zb, triggerColor);

        bool topSeen = v.Faces((X(0.4f * h), h / 2, 0), (0, 1, 0));
        bool backSeen = v.Faces((X(0.4f * h), 0, zb), (0, 0, -1));
        // Each sits on one of the handle's faces: behind that face's plane it goes first, else after.
        // Between them (the plane y = h/2 parts them): the one nearer the camera last.
        var order = topSeen ? new Action[] { Trigger, Bumper } : new Action[] { Bumper, Trigger };
        var after = topSeen ? new[] { backSeen, true } : new[] { false, backSeen };
        for (int i = 0; i < 2; i++) if (!after[i]) order[i]();
        bool front = Prism(g, v, outline, zb, zf, body);
        for (int i = 0; i < 2; i++) if (after[i]) order[i]();
        if (!front) return;

        // The front: everything a hair above it, by (a, b): a from the phone's edge outward, b down from the top.
        float zs = zf + 0.001f;
        PointF P(float x, float y) => v.Project((x, y, zs));
        float Y(float b) => h / 2 - b * h;
        var idleFill = Tint(body, 0.62f);

        void Round(float cx, float cy, float r, bool on, Color? ring = null)
        {
            var pts = Circle(cx, cy, r, 20).Select(p => P(p.X, p.Y)).ToArray();
            g.FillPolygon(on ? lit : idleFill, pts);
            g.DrawPolygon(ring ?? idle, penWidth, pts);
        }
        void Box(float x0b, float y0b, float x1b, float y1b, bool on)
        {
            var pts = Rect(x0b, y0b, x1b, y1b).Select(p => P(p.X, p.Y)).ToArray();
            g.FillPolygon(on ? lit : idleFill, pts);
            g.DrawPolygon(idle, penWidth * 0.75f, pts);
        }
        bool Held(uint b) => f?.PadHeld(b) == true;

        // Pills by the top edge, under the shoulder buttons: bumper (inner), trigger (outer, filled by the pull).
        Pill(g, v, zs, X(0.19f * h), X(0.40f * h), Y(0.06f), Y(0.11f), l1 ? 1f : 0f, lit, idleFill, idle, penWidth);
        Pill(g, v, zs, X(0.44f * h), X(0.62f * h), Y(0.06f), Y(0.11f), pull, lit, idleFill, idle, penWidth);

        // − / +, Capture / Home near the phone.
        float sx = X(0.09f * h), sy = Y(0.09f);
        float bl = 0.035f * h, bt = 0.011f * h;
        bool menu = Held(side < 0 ? OverlayFrame.PadMinus : OverlayFrame.PadPlus);
        Box(sx - bl, sy - bt, sx + bl, sy + bt, menu);
        if (side > 0) Box(sx - bt, sy - bl, sx + bt, sy + bl, menu);
        float hx = X(0.10f * h), hy = Y(0.90f);
        if (side < 0) Box(hx - 0.025f * h, hy - 0.025f * h, hx + 0.025f * h, hy + 0.025f * h, Held(OverlayFrame.PadCapture));
        else Round(hx, hy, 0.03f * h, Held(OverlayFrame.PadHome));

        // Left: stick above the D-pad. Right: the face buttons above the stick.
        float stickB = side < 0 ? 0.33f : 0.68f, padB = side < 0 ? 0.68f : 0.33f;
        Stick(X(0.39f * h), Y(stickB), side < 0 ? 0 : 2, side < 0 ? OverlayFrame.PadL3 : OverlayFrame.PadR3);
        float px = X(0.37f * h), py = Y(padB), o = 0.095f * h, r = 0.048f * h;
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
            var well = Circle(cx, cy, 0.13f * h, 28).Select(p => P(p.X, p.Y)).ToArray();
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
    private static void Pill(ICanvas g, View v, float z, float xa, float xb, float yTop, float yBottom, float fill,
        Color lit, Color idleFill, Color idle, float penWidth)
    {
        float x0 = Math.Min(xa, xb), x1 = Math.Max(xa, xb);
        float rr = (yTop - yBottom) / 2;
        var shape = Stadium(x0, yBottom, x1, yTop, rr);
        var pts = shape.Select(p => v.Project((p.X, p.Y, z))).ToArray();
        g.FillPolygon(idleFill, pts);
        if (fill > 0f)
        {
            // Filled from the phone's end (xa) toward the handle's outer end.
            float cut = xa + (xb - xa) * Math.Clamp(fill, 0f, 1f);
            var keep = Rect(Math.Min(xa, cut), yBottom - 1, Math.Max(xa, cut), yTop + 1);
            var part = OverlayRenderer.ClipConvex(shape, keep);
            if (part.Length >= 3) g.FillPolygon(lit, part.Select(p => v.Project((p.X, p.Y, z))).ToArray());
        }
        g.DrawPolygon(idle, penWidth * 0.75f, pts);
    }

    /// <summary>
    /// A prism: <paramref name="outline"/> (counter-clockwise, y up) from z <paramref name="zb"/> to
    /// <paramref name="zf"/>, its sides and whichever end faces the viewer. True when the front end does.
    /// </summary>
    private static bool Prism(ICanvas g, View v, (float X, float Y)[] outline, float zb, float zf, Color color)
    {
        var front = outline.Select(p => v.Project((p.X, p.Y, zf))).ToArray();
        var back = outline.Select(p => v.Project((p.X, p.Y, zb))).ToArray();
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
            var quad = new[] { front[i], front[j], back[j], back[i] };
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

    private static (float X, float Y)[] Rect(float x0, float y0, float x1, float y1) => [(x0, y0), (x1, y0), (x1, y1), (x0, y1)];

    private static (float X, float Y)[] Circle(float cx, float cy, float r, int n)
    {
        var pts = new (float, float)[n];
        for (int i = 0; i < n; i++)
        {
            float a = i * 2 * MathF.PI / n;
            pts[i] = (cx + r * MathF.Cos(a), cy + r * MathF.Sin(a));
        }
        return pts;
    }

    /// <summary>A rounded bar, counter-clockwise.</summary>
    private static (float X, float Y)[] Stadium(float x0, float y0, float x1, float y1, float r)
    {
        var pts = new List<(float, float)>();
        float cy = (y0 + y1) / 2;
        for (int i = 0; i <= 8; i++) { float a = -MathF.PI / 2 + i * MathF.PI / 8; pts.Add((x1 - r + r * MathF.Cos(a), cy + r * MathF.Sin(a))); }
        for (int i = 0; i <= 8; i++) { float a = MathF.PI / 2 + i * MathF.PI / 8; pts.Add((x0 + r + r * MathF.Cos(a), cy + r * MathF.Sin(a))); }
        return pts.ToArray();
    }

    private static Color Tint(Color c, float k) =>
        Color.FromArgb(c.A, Math.Clamp((int)(c.R * k), 0, 255), Math.Clamp((int)(c.G * k), 0, 255), Math.Clamp((int)(c.B * k), 0, 255));

    private static Color Mix(Color a, Color b, float t)
    {
        t = Math.Clamp(t, 0f, 1f);
        return Color.FromArgb(255, (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
    }
}
