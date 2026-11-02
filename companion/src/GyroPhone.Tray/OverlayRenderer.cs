using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>What one overlay picture shows.</summary>
internal sealed class OverlayScene
{
    public readonly OverlayFrame Frame = new();
    /// <summary>How long ago <see cref="Frame"/> came (us), or -1 when none came.</summary>
    public long FrameAgeUs = -1;
    public OverlayLayout? Layout;
    public bool Connected;
    /// <summary>Moving and resizing (click-through off): a frame shows the window's edges.</summary>
    public bool Arranging;
    /// <summary>The fingers' trails (phone, with Finger trails on), and the time to fade them by (ms).</summary>
    public FingerTrails? Trails;
    public long NowTicks;
}

/// <summary>
/// Draws an overlay window: its background (and its edges while arranging), then what it shows. The
/// phone: a rounded slab in perspective, turned by its pose, seen from the player's side, with the
/// layout on its screen: each control's outline and label, lit while pressed or latched, stick
/// knobs where the thumb holds them, finger dots and trails. The other kinds: <see cref="ShapeDrawing"/>,
/// <see cref="DialDrawing"/>. Plain GDI+ (every point projected), no 3D library.
/// </summary>
internal static class OverlayRenderer
{
    private static readonly Color Idle = Color.FromArgb(0x66, 255, 255, 255);

    /// <summary>Camera distance, in slab units (the slab's long side is about 2.2).</summary>
    private const float CameraZ = 5.5f;
    private const float Thickness = 0.09f;
    private const float Bezel = 0.035f;
    /// <summary>Label size as a fraction of the screen's short side (about 14 sp on the phone).</summary>
    private const float LabelSize = 0.04f;

    // Light from the upper left, toward the screen.
    private static readonly (float X, float Y, float Z) Light = Normalize(-0.35f, 0.55f, 1f);

    public static void Draw(Graphics g, Size size, OverlayScene scene, OverlaySettings settings)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAlias;
        g.CompositingQuality = CompositingQuality.HighQuality;
        switch (settings.Background)
        {
            case OverlayBackground.Green: g.Clear(Color.FromArgb(0, 255, 0)); break;
            case OverlayBackground.Dark: g.Clear(Color.FromArgb(0x18, 0x1A, 0x1E)); break;
            default: g.Clear(Color.Transparent); break;
        }
        if (scene.Arranging)
        {
            using var shade = new SolidBrush(Color.FromArgb(70, 20, 22, 26));
            g.FillRectangle(shade, 0, 0, size.Width, size.Height);
            using var pen = new Pen(Color.FromArgb(200, 124, 196, 255), 2) { DashStyle = DashStyle.Dash };
            g.DrawRectangle(pen, 1, 1, size.Width - 3, size.Height - 3);
            using var grip = new SolidBrush(Color.FromArgb(220, 124, 196, 255));
            g.FillPolygon(grip, new PointF[] { new(size.Width - 2, size.Height - 18), new(size.Width - 2, size.Height - 2), new(size.Width - 18, size.Height - 2) });
            DrawCaption(g, size, "Drag to move · drag the corner to resize");
        }

        bool live = scene.Connected && scene.FrameAgeUs is >= 0 and < 1_500_000;
        var raw = live ? Shown(scene.Frame, settings) : (1f, 0f, 0f, 0f);
        var m = Matrix3(raw);
        int top = scene.Arranging ? 18 : 0;
        var area = new RectangleF(0, 0, size.Width, size.Height - top);
        string? note = !scene.Connected ? "Waiting for the phone" : !live ? "Waiting for the phone's data" : null;
        switch (settings.Kind)
        {
            case OverlayKind.Phone:
                DrawPhone(g, area, scene, settings, live, m);
                return;
            case OverlayKind.Shape:
                ShapeDrawing.Draw(g, area, settings, m, live ? scene.Frame.Axes : 0);
                break;
            case OverlayKind.Horizon:
                DialDrawing.DrawHorizon(g, area, settings, m, m);
                break;
            default:
                DialDrawing.DrawDisk(g, area, settings, m, m);
                break;
        }
        if (note != null) DrawNote(g, area, note);
    }

    /// <summary>A short line under the drawing (no phone or no data).</summary>
    private static void DrawNote(Graphics g, RectangleF area, string text)
    {
        using var font = new Font(FontFamily.GenericSansSerif, Math.Max(9f, area.Height * 0.045f), FontStyle.Bold, GraphicsUnit.Pixel);
        var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Far };
        var r = new RectangleF(area.X, area.Y, area.Width, area.Height - 4);
        using var shadow = new SolidBrush(Color.FromArgb(160, 0, 0, 0));
        g.DrawString(text, font, shadow, new RectangleF(r.X + 1, r.Y + 1, r.Width, r.Height), fmt);
        using var brush = new SolidBrush(Color.FromArgb(230, 255, 255, 255));
        g.DrawString(text, font, brush, r, fmt);
    }

    private static void DrawPhone(Graphics g, RectangleF area, OverlayScene scene, OverlaySettings settings, bool live, float[] m)
    {
        var BodyColor = Opaque(settings.Color("body"));
        var ScreenColor = Opaque(settings.Color("screen"));
        var PressedColor = Opaque(settings.Color("lit"));
        var LatchedColor = Opaque(settings.Color("latched"));
        var fingerColor = Color.FromArgb(settings.Color("fingers"));
        var layout = scene.Layout;
        var f = scene.Frame;
        var size = new Size((int)area.Width, (int)area.Height);

        // The slab: the screen's shape (from the layout) plus a thin bezel.
        float aspect = layout is { WidthPx: > 0, HeightPx: > 0 } ? (float)layout.WidthPx / layout.HeightPx : 20f / 9f;
        bool portrait = aspect < 1f;
        float longSide = 2.2f;
        float sw = portrait ? longSide * aspect : longSide;
        float sh = portrait ? longSide : longSide / aspect;
        float bw = sw + 2 * Bezel, bh = sh + 2 * Bezel;


        float radius = MathF.Sqrt(bw * bw + bh * bh) / 2f + Thickness;
        float half = Math.Min(size.Width, size.Height) / 2f * 0.94f;
        float k = half * (CameraZ - radius) / (CameraZ * radius);
        var cx = size.Width / 2f;
        var cy = size.Height / 2f;

        PointF Project((float X, float Y, float Z) p)
        {
            var v = Mul(m, p);
            float s = k * CameraZ / (CameraZ - v.Z);
            return new PointF(cx + v.X * s, cy - v.Y * s);
        }

        bool Faces((float X, float Y, float Z) at, (float X, float Y, float Z) normal)
        {
            var c = Mul(m, at);
            var n = Mul(m, normal);
            return n.X * -c.X + n.Y * -c.Y + n.Z * (CameraZ - c.Z) > 0;
        }

        float Shade((float X, float Y, float Z) normal)
        {
            var n = Mul(m, normal);
            float d = n.X * Light.X + n.Y * Light.Y + n.Z * Light.Z;
            return 0.4f + 0.6f * Math.Max(0f, d);
        }

        // The slab's outline (rounded rectangle) in the screen's plane.
        var outline = RoundedRect(bw, bh, Math.Min(bw, bh) * 0.13f, 6);
        float zf = Thickness / 2f, zb = -Thickness / 2f;
        var front = outline.Select(p => Project((p.X, p.Y, zf))).ToArray();
        var back = outline.Select(p => Project((p.X, p.Y, zb))).ToArray();

        // Sides: visible faces of a convex solid never overlap, so their order does not matter.
        for (int i = 0; i < outline.Length; i++)
        {
            int j = (i + 1) % outline.Length;
            var a = outline[i];
            var b = outline[j];
            var n = Normalize(b.Y - a.Y, a.X - b.X, 0f); // outward for a counter-clockwise outline
            var mid = ((a.X + b.X) / 2, (a.Y + b.Y) / 2, 0f);
            if (!Faces(mid, n)) continue;
            using var brush = new SolidBrush(Tint(BodyColor, Shade(n) * 0.85f));
            var quad = new[] { front[i], front[j], back[j], back[i] };
            g.FillPolygon(brush, quad);
            using var seam = new Pen(brush.Color, 1f);
            g.DrawPolygon(seam, quad); // hides hairline gaps between neighbours
        }

        if (!Faces((0, 0, zf), (0, 0, 1)))
        {
            // The back: the body and a camera island.
            using var body = new SolidBrush(Tint(BodyColor, Shade((0, 0, -1))));
            g.FillPolygon(body, back);
            float cs = Math.Min(bw, bh) * 0.32f;
            // Top left as seen from behind the phone held upright.
            float ix = bw / 2 - cs * 0.8f;
            float iy = bh / 2 - cs * 0.8f;
            var island = RoundedRect(cs, cs, cs * 0.3f, 4).Select(p => Project((p.X + ix, p.Y + iy, zb - 0.002f))).ToArray();
            using var islandBrush = new SolidBrush(Tint(Color.FromArgb(0x3A, 0x3F, 0x48), Shade((0, 0, -1))));
            g.FillPolygon(islandBrush, island);
            return;
        }

        using (var body = new SolidBrush(Tint(BodyColor, Shade((0, 0, 1)))))
            g.FillPolygon(body, front);

        float zs = zf + 0.001f;
        (float X, float Y, float Z) OnScreen(float u, float v) => ((u - 0.5f) * sw, (0.5f - v) * sh, zs);
        PointF P(float u, float v) => Project(OnScreen(u, v));

        var screen = RoundedRect(sw, sh, Math.Min(sw, sh) * 0.09f, 6).Select(p => Project((p.X, p.Y, zs))).ToArray();
        using (var sb = new SolidBrush(ScreenColor)) g.FillPolygon(sb, screen);
        // The front camera: in the top bezel's middle (portrait) or the left one (landscape).
        var cam = portrait ? OnScreen(0.5f, -Bezel / 2 / sh) : OnScreen(-Bezel / 2 / sw, 0.5f);
        var camP = Project(cam);
        float camR = Math.Max(1.2f, k * 0.012f);
        using (var cb = new SolidBrush(Color.FromArgb(0x05, 0x06, 0x08))) g.FillEllipse(cb, camP.X - camR, camP.Y - camR, camR * 2, camR * 2);

        using var clipPath = new GraphicsPath();
        clipPath.AddPolygon(screen);
        var savedClip = g.Clip;
        g.SetClip(clipPath, CombineMode.Intersect);

        float pxW = layout is { WidthPx: > 0 } ? layout.WidthPx : 2400;
        float pxH = layout is { HeightPx: > 0 } ? layout.HeightPx : 1080;
        float fontPx = Math.Min(pxW, pxH) * LabelSize;

        if (layout != null)
        {
            var sticks = new Dictionary<int, (float X, float Y)>();
            if (live) for (int s = 0; s < f.StickCount; s++) sticks[f.StickIndex[s]] = (f.StickXY[s * 2], f.StickXY[s * 2 + 1]);
            for (int i = 0; i < layout.Controls.Count; i++)
            {
                var c = layout.Controls[i];
                bool pressed = live && f.IsPressed(i);
                bool latched = live && !pressed && f.IsLatched(i);
                var shape = c.Round ? Ellipse(c.X, c.Y, c.W / 2, c.H / 2, 32) : RoundedBox(c, pxW, pxH);
                var pts = shape.Select(p => P(p.X, p.Y)).ToArray();
                float op = Math.Clamp(c.Opacity, 0f, 1f);
                if (c.Kind == OverlayControlKind.Display)
                {
                    using var faint = new Pen(Color.FromArgb((int)(40 * op), 255, 255, 255), 1f) { DashStyle = DashStyle.Dot };
                    g.DrawPolygon(faint, pts);
                    continue;
                }
                var tint = pressed ? PressedColor : latched ? LatchedColor : Idle;
                if (pressed || latched)
                {
                    using var fill = new SolidBrush(Color.FromArgb((int)(0.25f * 255 * op), tint));
                    g.FillPolygon(fill, pts);
                }
                using (var pen = new Pen(Color.FromArgb((int)(tint.A * op), tint), Math.Clamp(k * 0.008f, 1f, 3f)))
                    g.DrawPolygon(pen, pts);
                if (c.Kind == OverlayControlKind.Stick)
                {
                    var knob = sticks.TryGetValue(i, out var kv) ? kv : (0f, 0f);
                    float kx = c.X + knob.Item1 * 0.6f * c.W / 2, ky = c.Y + knob.Item2 * 0.6f * c.H / 2;
                    var kp = Ellipse(kx, ky, c.W / 5, c.H / 5, 24).Select(p => P(p.X, p.Y)).ToArray();
                    using var kb = new SolidBrush(Color.FromArgb((int)(0.6f * tint.A * op), tint));
                    g.FillPolygon(kb, kp);
                }
                if (c.Label.Length > 0)
                    DrawLabel(g, c.Label, c.X, c.Y, c.W * pxW, fontPx, pxW, pxH, P, Color.FromArgb((int)(255 * Math.Max(op, 0.5f)), 255, 255, 255));
            }
            if (layout.Layer.Length > 0)
                DrawLabel(g, layout.Layer, 0.5f, 0.05f, pxW * 0.6f, fontPx, pxW, pxH, P, Color.FromArgb(230, 255, 255, 255), pill: true);
        }

        if (live && scene.Trails is { } trails)
        {
            // Each trail fades along its length: older points fainter and thinner.
            float width = Math.Clamp(k * 0.02f, 1.5f, 6f);
            foreach (var t in trails.All)
            {
                var pts = t.Points;
                for (int i = 1; i < pts.Count; i++)
                {
                    float life = 1f - Math.Clamp((scene.NowTicks - pts[i].T) / (float)FingerTrails.LifeMs, 0f, 1f);
                    if (life <= 0f) continue;
                    using var pen = new Pen(Color.FromArgb((int)(fingerColor.A * 0.8f * life), fingerColor), width * (0.4f + 0.6f * life))
                    {
                        StartCap = LineCap.Round,
                        EndCap = LineCap.Round,
                    };
                    g.DrawLine(pen, P(pts[i - 1].U, pts[i - 1].V), P(pts[i].U, pts[i].V));
                }
            }
        }

        if (live && settings.FingerDots)
        {
            using var dot = new SolidBrush(Color.FromArgb((int)(fingerColor.A * 0.6f), fingerColor));
            float r = 0.035f;
            for (int i = 0; i < f.FingerCount; i++)
            {
                var dp = Ellipse(f.Fingers[i * 2], f.Fingers[i * 2 + 1], r * pxH / pxW, r, 16).Select(p => P(p.X, p.Y)).ToArray();
                g.FillPolygon(dot, dp);
            }
        }

        string? note = !scene.Connected ? "Waiting for the phone"
            : !live ? "Waiting for the phone's overlay data"
            : !f.PadShown ? "The pad is not open"
            : layout == null ? "Getting the layout" : null;
        if (note != null)
        {
            using var dim = new SolidBrush(Color.FromArgb(150, 0, 0, 0));
            g.FillPolygon(dim, screen);
            DrawLabel(g, note, 0.5f, 0.5f, pxW * 0.9f, fontPx * 1.3f, pxW, pxH, P, Color.FromArgb(230, 255, 255, 255));
        }
        g.Clip = savedClip;
    }

    /// <summary>The pose shown: the window's pose, from the levelled one (if any), its turn scaled.</summary>
    private static (float W, float X, float Y, float Z) Shown(OverlayFrame f, OverlaySettings s)
    {
        var q = (f.Qw, f.Qx, f.Qy, f.Qz);
        // Seen from the resting grip: held that way it shows face-on, and the phone's own yaw, pitch
        // and roll show as the same turns about the PC screen's axes.
        // Kept only with gravity: without it Level restarts the pose, so a grip left from gravity is ignored.
        if (s.Gravity && !s.IsTrackball && s.Rest is { Length: 4 } c) q = MulQ((c[0], -c[1], -c[2], -c[3]), q);
        float scale = Math.Clamp(s.MotionScale, 0.25f, 4f);
        if (MathF.Abs(scale - 1f) < 0.001f) return q;
        if (q.Item1 < 0) q = (-q.Item1, -q.Item2, -q.Item3, -q.Item4); // the short way round
        float w = Math.Clamp(q.Item1, -1f, 1f);
        float angle = 2f * MathF.Acos(w);
        float sn = MathF.Sqrt(Math.Max(0f, 1f - w * w));
        if (sn < 1e-5f) return (1, 0, 0, 0);
        float a = Math.Min(angle * scale, MathF.PI) / 2f;
        float r = MathF.Sin(a) / sn;
        return (MathF.Cos(a), q.Item2 * r, q.Item3 * r, q.Item4 * r);
    }

    private static Color Opaque(int argb) => Color.FromArgb(255, Color.FromArgb(argb));

    private static (float, float, float, float) MulQ((float W, float X, float Y, float Z) a, (float W, float X, float Y, float Z) b) => (
        a.W * b.W - a.X * b.X - a.Y * b.Y - a.Z * b.Z,
        a.W * b.X + a.X * b.W + a.Y * b.Z - a.Z * b.Y,
        a.W * b.Y - a.X * b.Z + a.Y * b.W + a.Z * b.X,
        a.W * b.Z + a.X * b.Y - a.Y * b.X + a.Z * b.W);

    private static float[] Matrix3((float W, float X, float Y, float Z) q)
    {
        var (w, x, y, z) = q;
        return
        [
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        ];
    }

    private static (float X, float Y, float Z) Mul(float[] m, (float X, float Y, float Z) p) => (
        m[0] * p.X + m[1] * p.Y + m[2] * p.Z,
        m[3] * p.X + m[4] * p.Y + m[5] * p.Z,
        m[6] * p.X + m[7] * p.Y + m[8] * p.Z);

    private static (float X, float Y, float Z) Normalize(float x, float y, float z)
    {
        float l = MathF.Sqrt(x * x + y * y + z * z);
        return l == 0 ? (0, 0, 1) : (x / l, y / l, z / l);
    }

    private static Color Tint(Color c, float k) =>
        Color.FromArgb(c.A, Math.Clamp((int)(c.R * k), 0, 255), Math.Clamp((int)(c.G * k), 0, 255), Math.Clamp((int)(c.B * k), 0, 255));

    /// <summary>A rounded rectangle centred on 0, counter-clockwise (y up), with <paramref name="seg"/> segments per corner.</summary>
    private static (float X, float Y)[] RoundedRect(float w, float h, float r, int seg)
    {
        var pts = new List<(float, float)>();
        float hx = w / 2 - r, hy = h / 2 - r;
        (float cx, float cy, float start)[] corners = [(hx, -hy, -MathF.PI / 2), (hx, hy, 0), (-hx, hy, MathF.PI / 2), (-hx, -hy, MathF.PI)];
        foreach (var (ccx, ccy, start) in corners)
            for (int i = 0; i <= seg; i++)
            {
                float a = start + i * (MathF.PI / 2) / seg;
                pts.Add((ccx + r * MathF.Cos(a), ccy + r * MathF.Sin(a)));
            }
        return pts.ToArray();
    }

    /// <summary>An ellipse in pad coordinates (u right, v down).</summary>
    private static (float X, float Y)[] Ellipse(float u, float v, float ru, float rv, int n)
    {
        var pts = new (float, float)[n];
        for (int i = 0; i < n; i++)
        {
            float a = i * 2 * MathF.PI / n;
            pts[i] = (u + ru * MathF.Cos(a), v + rv * MathF.Sin(a));
        }
        return pts;
    }

    /// <summary>A square control's rounded box (corners 12% of its width, as on the phone) in pad coordinates.</summary>
    private static (float X, float Y)[] RoundedBox(OverlayControl c, float pxW, float pxH)
    {
        float w = c.W * pxW, h = c.H * pxH;
        float r = Math.Min(w, h) * 0.12f;
        return RoundedRect(w, h, r, 4).Select(p => (c.X + p.X / pxW, c.Y + p.Y / pxH)).ToArray();
    }

    /// <summary>
    /// Text lying on the screen, centred at pad point (u, v): drawn in pad pixels under the affine
    /// transform that the projection has around that point.
    /// </summary>
    private static void DrawLabel(Graphics g, string text, float u, float v, float maxWidthPx, float fontPx,
        float pxW, float pxH, Func<float, float, PointF> project, Color color, bool pill = false)
    {
        var p0 = project(u, v);
        var px = project(u + 1f / pxW * 100f, v);
        var py = project(u, v + 1f / pxH * 100f);
        float ax = (px.X - p0.X) / 100f, ay = (px.Y - p0.Y) / 100f;
        float bx = (py.X - p0.X) / 100f, by = (py.Y - p0.Y) / 100f;
        // Edge on: too thin to read.
        if (MathF.Abs(ax * by - ay * bx) * fontPx * fontPx < 6f) return;
        var saved = g.Transform;
        using var tm = new Matrix(ax, ay, bx, by, p0.X, p0.Y);
        g.MultiplyTransform(tm);
        using var font = new Font(FontFamily.GenericSansSerif, Math.Max(1f, fontPx), FontStyle.Bold, GraphicsUnit.Pixel);
        using var fmt = new StringFormat
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center,
            Trimming = StringTrimming.EllipsisCharacter,
            FormatFlags = StringFormatFlags.NoWrap,
        };
        float w = Math.Max(fontPx, maxWidthPx - fontPx * 0.4f);
        var rect = new RectangleF(-w / 2, -fontPx, w, fontPx * 2);
        if (pill)
        {
            var size = g.MeasureString(text, font, (int)w, fmt);
            using var bg = new SolidBrush(Color.FromArgb(0xE0, 0x1E, 0x23, 0x2B));
            g.FillRectangle(bg, -size.Width / 2 - fontPx * 0.5f, -fontPx * 0.75f, size.Width + fontPx, fontPx * 1.5f);
        }
        using var brush = new SolidBrush(color);
        g.DrawString(text, font, brush, rect, fmt);
        g.Transform = saved;
    }

    private static void DrawCaption(Graphics g, Size size, string text)
    {
        using var font = new Font(FontFamily.GenericSansSerif, 11f, FontStyle.Regular, GraphicsUnit.Pixel);
        using var brush = new SolidBrush(Color.FromArgb(230, 255, 255, 255));
        var fmt = new StringFormat { Alignment = StringAlignment.Center };
        g.DrawString(text, font, brush, new RectangleF(0, size.Height - 17, size.Width, 16), fmt);
    }
}
