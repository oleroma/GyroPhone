using System.Drawing;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The Keys overlay's rows (docs/spec/pc-overlays.md), kept per window: each new press takes the topmost free row and
/// keeps it while held; let go, it fades out over the Fade out time and leaves a gap, and nothing below moves up. A
/// press of the same key while its row still fades takes that row back. Filled from <see cref="OverlayKeysReader"/>'s
/// view on the window's own thread; made once, rows reused.
/// </summary>
internal sealed class KeysBoard
{
    public sealed class Row
    {
        public string Name = "", Target = "";
        public int Flags;
        /// <summary>A stick's or the D-pad's picture (its row while something on it fires), or null.</summary>
        public OverlayKeyStick? Stick;
        public bool Held;
        /// <summary>When it was let go (ms), while it fades.</summary>
        public long GoneAt;
        /// <summary>1 while held, falling to 0 as it fades out.</summary>
        public float Alpha;
        public bool Used;
    }

    private readonly List<Row> _rows = new();
    private OverlayKeysView? _seen;

    /// <summary>The rows from the top (unused ones are gaps).</summary>
    public IReadOnlyList<Row> Rows => _rows;

    /// <summary>The newest view (its sticks), or null without the phone's table.</summary>
    public OverlayKeysView? Keys => _seen;

    /// <summary>Takes <paramref name="keys"/> (null: no table from the phone) at <paramref name="now"/> (ms); true when the picture changed.</summary>
    public bool Update(OverlayKeysView? keys, long now, int fadeMs)
    {
        bool changed = false;
        if (!ReferenceEquals(keys, _seen))
        {
            _seen = keys;
            changed = true;
            var rows = keys?.Rows ?? [];
            // Held rows the phone no longer sends: let go now.
            foreach (var r in _rows)
            {
                if (!r.Used || !r.Held) continue;
                bool still = false;
                foreach (var k in rows) if (k.Name == r.Name) { still = true; break; }
                if (still) continue;
                r.Held = false;
                r.GoneAt = now;
            }
            foreach (var k in rows)
            {
                // Its own row, held or still fading; else the topmost gap; else a new row at the bottom.
                Row? at = null;
                foreach (var r in _rows) if (r.Used && r.Name == k.Name) { at = r; break; }
                if (at == null) foreach (var r in _rows) if (!r.Used) { at = r; break; }
                if (at == null) _rows.Add(at = new Row());
                at.Used = true;
                at.Name = k.Name;
                at.Target = k.Target;
                at.Flags = k.Flags;
                at.Stick = k.Stick;
                at.Held = true;
                at.Alpha = 1f;
            }
        }
        // Fading rows.
        foreach (var r in _rows)
        {
            if (!r.Used || r.Held) continue;
            float a = fadeMs <= 0 ? 0f : 1f - (now - r.GoneAt) / (float)fadeMs;
            if (a <= 0f)
            {
                r.Used = false;
                r.Alpha = 0f;
            }
            else r.Alpha = Math.Min(1f, a);
            changed = true;
        }
        // Gaps at the bottom go: the list stays as short as what it shows.
        while (_rows.Count > 0 && !_rows[^1].Used) _rows.RemoveAt(_rows.Count - 1);
        return changed;
    }

    /// <summary>Some row is fading out: the window draws again every frame meanwhile.</summary>
    public bool Fading
    {
        get
        {
            foreach (var r in _rows) if (r.Used && !r.Held) return true;
            return false;
        }
    }
}

/// <summary>
/// Draws the Keys overlay: the always-shown controls on top (Look's Always shown; dim while they fire nothing), then the
/// rows of <see cref="KeysBoard"/>. A key's row is "A = Space" (a touch button's only "Space"; a trigger's with a bar for
/// how far it is pulled; a Toggle that is on in its colour). A stick's or the D-pad's row is "LS" and a pie: its four
/// slices light as their keys fire and its outer ring as its Outer mappings do (symbols, not the real pie sizes), with
/// a dot where the stick points; then its keys (W A S D order), its Outer keys and what its axes move ("Mouse"). The
/// rows are as tall as the window's width allows; rows that don't fit aren't drawn.
/// </summary>
internal static class KeysDrawing
{
    // The keys in the order W A S D reads them (up, left, down, right): their index in OverlayKeyStick.Keys.
    private static readonly int[] WayIndex = [0, 3, 2, 1];

    public static void Draw(ICanvas g, RectangleF area, OverlayScene scene, OverlaySettings s)
    {
        var c = new Colors(Color.FromArgb(s.Color("name")), Color.FromArgb(s.Color("target")), Color.FromArgb(s.Color("on")), Color.FromArgb(s.Color("dim")));
        var panel = Color.FromArgb(s.Color("panel"));
        if (s.KeysTransparent) panel = Color.Transparent;
        if (panel.A > 0) g.FillRectangle(panel, area);
        float pad = Math.Clamp(Math.Min(area.Width, area.Height) * 0.04f, 3f, 10f);
        var inner = RectangleF.Inflate(area, -pad, -pad);
        float px = Math.Clamp(inner.Width / 11f, 10f, 30f);
        float rowH = px * 1.55f;
        float y = inner.Y;

        bool live = scene.Connected && scene.FrameAgeUs is >= 0 and < 1_500_000;
        var board = scene.Keys;
        string? note = !scene.Connected ? "Waiting for the phone"
            : !live || board?.Keys == null ? "Waiting for the phone's keys"
            : !scene.Frame.PadAttached && board.Keys.Shown.Count == 0 && board.Rows.Count == 0 ? "No controller on the phone; touch buttons show when pressed" : null;
        if (note != null)
        {
            g.DrawText(note, Math.Max(9f, px * 0.7f), false, c.Dim, new RectangleF(inner.X, inner.Y, inner.Width, rowH), TextAlign.Near, TextAlign.Center);
            return;
        }

        foreach (var r in board!.Keys!.Shown)
        {
            if (y + rowH > inner.Bottom + 0.5f) return;
            var box = new RectangleF(inner.X, y, inner.Width, rowH);
            if (r.Stick != null) StickRow(g, box, r.Name, r.Stick, scene.Frame, px, 1f, c);
            else KeyRow(g, box, r.Name, r.Target, r.Flags, r.Lit ? 1f : 0.45f, scene.Frame, px, c);
            y += rowH;
        }
        if (board.Keys.Shown.Count > 0) y += pad / 2;
        foreach (var r in board.Rows)
        {
            if (y + rowH > inner.Bottom + 0.5f) return;
            var box = new RectangleF(inner.X, y, inner.Width, rowH);
            if (r.Used && r.Alpha > 0f)
            {
                if (r.Stick != null) StickRow(g, box, r.Name, r.Stick, scene.Frame, px, r.Alpha, c);
                else KeyRow(g, box, r.Name, r.Target, r.Flags, r.Alpha, scene.Frame, px, c);
            }
            y += rowH;
        }
    }

    private readonly record struct Colors(Color Name, Color Target, Color On, Color Dim);

    private static Color Faded(Color c, float a) => Color.FromArgb((int)Math.Clamp(c.A * a, 0f, 255f), c);

    private static void KeyRow(ICanvas g, RectangleF r, string name, string targetText, int flags, float a, OverlayFrame f, float px, Colors c)
    {
        float x = r.X;
        // A touch button's row shows only what it sends.
        if ((flags & OverlayKeyRow.Touch) == 0)
        {
            float nw = g.MeasureText(name, px, true).Width;
            g.DrawText(name, px, true, Faded(c.Name, a), new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
            x += nw + px * 0.3f;
            const string Is = "=";
            g.DrawText(Is, px, false, Faded(c.Name, a * 0.6f), new RectangleF(x, r.Y, Math.Max(0f, r.Right - x), r.Height), TextAlign.Near, TextAlign.Center);
            x += g.MeasureText(Is, px, false).Width + px * 0.3f;
        }
        bool toggle = (flags & OverlayKeyRow.Toggle) != 0;
        g.DrawText(targetText, px, false, Faded(toggle ? c.On : c.Target, a), new RectangleF(x, r.Y, Math.Max(0f, r.Right - x), r.Height), TextAlign.Near, TextAlign.Center);
        // A trigger's pull, as a thin bar along the row's foot.
        float pull = (flags & OverlayKeyRow.LeftTrigger) != 0 ? f.PadL2Pull : (flags & OverlayKeyRow.RightTrigger) != 0 ? f.PadR2Pull : -1f;
        if (pull >= 0f)
        {
            float h = Math.Max(2f, px * 0.14f);
            var bar = new RectangleF(r.X, r.Bottom - h - px * 0.08f, r.Width, h);
            g.FillRectangle(Faded(c.Name, a * 0.2f), bar);
            g.FillRectangle(Faded(c.Target, a), new RectangleF(bar.X, bar.Y, bar.Width * Math.Clamp(pull, 0f, 1f), bar.Height));
        }
    }

    private static void StickRow(ICanvas g, RectangleF r, string name, OverlayKeyStick st, OverlayFrame f, float px, float a, Colors c)
    {
        float x = r.X;
        float nw = Math.Max(g.MeasureText("RS", px, true).Width, g.MeasureText(name, px, true).Width);
        g.DrawText(name, px, true, Faded(c.Name, a), new RectangleF(x, r.Y, nw + 2, r.Height), TextAlign.Near, TextAlign.Center);
        x += nw + px * 0.45f;
        float icon = r.Height * 0.86f;
        var box = new RectangleF(x, r.Y + (r.Height - icon) / 2, icon, icon);
        bool stick = st.Stick != OverlayKeyStick.DPad;
        float sx = stick ? f.PadSticks[st.Stick * 2] : 0f, sy = stick ? f.PadSticks[st.Stick * 2 + 1] : 0f;
        bool moved = stick && (Math.Abs(sx) > OverlayKeysReader.Moved || Math.Abs(sy) > OverlayKeysReader.Moved);
        Pie(g, box, st, a, c);
        if (stick)
        {
            // Where the stick points now.
            float d = icon * 0.2f;
            float cx = box.X + box.Width / 2 + sx * (icon - d) / 2, cy = box.Y + box.Height / 2 + sy * (icon - d) / 2;
            g.FillEllipse(Faded(moved ? c.Name : c.Dim, a), new RectangleF(cx - d / 2, cy - d / 2, d, d));
        }
        x += icon + px * 0.45f;
        // Its keys, up, left, down, right (W A S D), each bright while it fires; then the Outer ones; then what the axes move.
        for (int i = 0; i < 4 && x < r.Right; i++)
            x = Word(g, r, x, st.Keys[WayIndex[i]], (st.Lit & 1 << WayIndex[i]) != 0, px, a, c);
        if (x < r.Right && OuterAny(st)) x += px * 0.25f;
        for (int i = 0; i < 4 && x < r.Right; i++)
            x = Word(g, r, x, st.Outer[WayIndex[i]], (st.OuterLit & 1 << WayIndex[i]) != 0, px, a, c);
        if (x < r.Right) x = Word(g, r, x, st.Outer[4], (st.OuterLit & OverlayKeyStick.Any) != 0, px, a, c);
        if (st.Analog.Length > 0 && x < r.Right)
            g.DrawText(st.Analog, px, moved, Faded(moved ? c.Target : c.Dim, a), new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
    }

    private static bool OuterAny(OverlayKeyStick st)
    {
        foreach (var o in st.Outer) if (o.Length > 0) return true;
        return false;
    }

    private static float Word(ICanvas g, RectangleF r, float x, string t, bool lit, float px, float a, Colors c)
    {
        if (t.Length == 0) return x;
        g.DrawText(t, px, lit, Faded(lit ? c.Target : c.Dim, a), new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
        return x + g.MeasureText(t, px, lit).Width + px * 0.35f;
    }

    /// <summary>
    /// A pie: four slices (up, right, down, left), each lit while its key fires, dim when mapped, faint when not; and an
    /// outer ring of four parts the same way for the Outer mappings (all four for any way). Symbols, not the real pies.
    /// </summary>
    private static void Pie(ICanvas g, RectangleF b, OverlayKeyStick st, float a, Colors c)
    {
        float cx = b.X + b.Width / 2, cy = b.Y + b.Height / 2, R = b.Width / 2;
        bool hasOuter = OuterAny(st);
        float r1 = hasOuter ? R * 0.68f : R;
        Color Slice(bool mapped, bool lit) => lit ? Faded(c.Target, a) : mapped ? Faded(c.Dim, a) : Faded(c.Dim, a * 0.35f);
        for (int way = 0; way < 4; way++)
        {
            g.FillPolygon(Slice(st.Keys[way].Length > 0, (st.Lit & 1 << way) != 0), Sector(cx, cy, 0f, r1, way));
            if (!hasOuter) continue;
            bool mapped = st.Outer[way].Length > 0 || st.Outer[4].Length > 0;
            bool lit = (st.OuterLit & (1 << way | OverlayKeyStick.Any)) != 0;
            g.FillPolygon(Slice(mapped, lit), Sector(cx, cy, R * 0.78f, R, way));
        }
    }

    /// <summary>The part of the ring <paramref name="r0"/>..<paramref name="r1"/> around way <paramref name="way"/> (0 up, 1 right, 2 down, 3 left), with a small gap to its neighbours.</summary>
    private static PointF[] Sector(float cx, float cy, float r0, float r1, int way)
    {
        const int Steps = 8;
        float mid = way * MathF.PI / 2 - MathF.PI / 2; // up is -90°, y down
        float half = MathF.PI / 4 - 0.07f;
        var pts = new List<PointF>(2 * Steps + 2);
        for (int i = 0; i <= Steps; i++)
        {
            float t = mid - half + 2 * half * i / Steps;
            pts.Add(new PointF(cx + MathF.Cos(t) * r1, cy + MathF.Sin(t) * r1));
        }
        if (r0 <= 0f)
        {
            // A slice: its tip pulled off the centre a little, so the four read apart.
            pts.Add(new PointF(cx + MathF.Cos(mid) * r1 * 0.08f, cy + MathF.Sin(mid) * r1 * 0.08f));
        }
        else
        {
            for (int i = Steps; i >= 0; i--)
            {
                float t = mid - half + 2 * half * i / Steps;
                pts.Add(new PointF(cx + MathF.Cos(t) * r0, cy + MathF.Sin(t) * r0));
            }
        }
        return pts.ToArray();
    }
}
