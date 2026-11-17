using System.Drawing;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The Keys overlay's rows (docs/spec/pc-overlays.md), kept per window: each new press takes the topmost free row and
/// keeps it while held; let go, it fades out over the Fade out time and leaves a gap, and nothing below moves up. A
/// press of the same key while its row still fades takes that row back. Filled from the phone's <see cref="OverlayKeys"/>
/// on the window's own thread; made once, rows reused.
/// </summary>
internal sealed class KeysBoard
{
    public sealed class Row
    {
        public string Name = "", Target = "";
        public int Flags;
        public bool Held;
        /// <summary>When it was let go (ms), while it fades.</summary>
        public long GoneAt;
        /// <summary>1 while held, falling to 0 as it fades out.</summary>
        public float Alpha;
        public bool Used;
    }

    private readonly List<Row> _rows = new();
    private OverlayKeys? _seen;

    /// <summary>The rows from the top (unused ones are gaps).</summary>
    public IReadOnlyList<Row> Rows => _rows;

    /// <summary>The newest keys from the phone (its sticks), or null when none came.</summary>
    public OverlayKeys? Keys => _seen;

    /// <summary>Takes <paramref name="keys"/> (null: the phone sends none) at <paramref name="now"/> (ms); true when the picture changed.</summary>
    public bool Update(OverlayKeys? keys, long now, int fadeMs)
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
/// Draws the Keys overlay: a row for each stick that sends something ("LS" with a plus whose arms light as it pushes its
/// keys, then the keys; or a dot where it points when it moves the mouse or a gamepad stick), then the key rows of
/// <see cref="KeysBoard"/> ("A = Space"; a trigger's row with a bar for how far it is pulled; a Toggle that is on in its
/// colour). The rows are as tall as the window's width allows; rows that don't fit aren't drawn.
/// </summary>
internal static class KeysDrawing
{
    // The keys in the order W A S D reads them (up, left, down, right): their index in OverlayKeyStick.Keys and their bit.
    private static readonly int[] WayIndex = [0, 3, 2, 1];
    private static readonly int[] WayBit = [OverlayKeyStick.Up, OverlayKeyStick.Left, OverlayKeyStick.Down, OverlayKeyStick.Right];

    public static void Draw(ICanvas g, RectangleF area, OverlayScene scene, OverlaySettings s)
    {
        var nameColor = Color.FromArgb(s.Color("name"));
        var target = Color.FromArgb(s.Color("target"));
        var on = Color.FromArgb(s.Color("on"));
        var dim = Color.FromArgb(s.Color("dim"));
        var panel = Color.FromArgb(s.Color("panel"));
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
            : !scene.Frame.PadAttached ? "No controller on the phone" : null;
        if (note != null)
        {
            g.DrawText(note, Math.Max(9f, px * 0.7f), false, dim, new RectangleF(inner.X, inner.Y, inner.Width, rowH), TextAlign.Near, TextAlign.Center);
            return;
        }

        foreach (var st in board!.Keys!.Sticks)
        {
            if (y + rowH > inner.Bottom + 0.5f) return;
            StickRow(g, new RectangleF(inner.X, y, inner.Width, rowH), st, scene.Frame, px, nameColor, target, dim);
            y += rowH;
        }
        if (board.Keys.Sticks.Count > 0) y += pad / 2;
        foreach (var r in board.Rows)
        {
            if (y + rowH > inner.Bottom + 0.5f) return;
            if (r.Used && r.Alpha > 0f) KeyRow(g, new RectangleF(inner.X, y, inner.Width, rowH), r, scene.Frame, px, nameColor, target, on);
            y += rowH;
        }
    }

    private static Color Faded(Color c, float a) => Color.FromArgb((int)Math.Clamp(c.A * a, 0f, 255f), c);

    private static void KeyRow(ICanvas g, RectangleF r, KeysBoard.Row row, OverlayFrame f, float px, Color nameColor, Color target, Color on)
    {
        float a = row.Alpha;
        float x = r.X;
        var name = row.Name;
        float nw = g.MeasureText(name, px, true).Width;
        g.DrawText(name, px, true, Faded(nameColor, a), new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
        x += nw + px * 0.3f;
        const string Is = "=";
        g.DrawText(Is, px, false, Faded(nameColor, a * 0.6f), new RectangleF(x, r.Y, Math.Max(0f, r.Right - x), r.Height), TextAlign.Near, TextAlign.Center);
        x += g.MeasureText(Is, px, false).Width + px * 0.3f;
        bool toggle = (row.Flags & OverlayKeyRow.Toggle) != 0;
        g.DrawText(row.Target, px, false, Faded(toggle ? on : target, a), new RectangleF(x, r.Y, Math.Max(0f, r.Right - x), r.Height), TextAlign.Near, TextAlign.Center);
        // A trigger's pull, as a thin bar along the row's foot.
        float pull = (row.Flags & OverlayKeyRow.LeftTrigger) != 0 ? f.PadL2Pull : (row.Flags & OverlayKeyRow.RightTrigger) != 0 ? f.PadR2Pull : -1f;
        if (pull >= 0f)
        {
            float h = Math.Max(2f, px * 0.14f);
            var bar = new RectangleF(r.X, r.Bottom - h - px * 0.08f, r.Width, h);
            g.FillRectangle(Faded(nameColor, a * 0.2f), bar);
            g.FillRectangle(Faded(target, a), new RectangleF(bar.X, bar.Y, bar.Width * Math.Clamp(pull, 0f, 1f), bar.Height));
        }
    }

    private static void StickRow(ICanvas g, RectangleF r, OverlayKeyStick st, OverlayFrame f, float px, Color nameColor, Color target, Color dim)
    {
        float x = r.X;
        var name = st.Stick == 0 ? "LS" : "RS";
        float nw = g.MeasureText("RS", px, true).Width;
        g.DrawText(name, px, true, nameColor, new RectangleF(x, r.Y, nw + 2, r.Height), TextAlign.Near, TextAlign.Center);
        x += nw + px * 0.45f;
        float icon = r.Height * 0.8f;
        var box = new RectangleF(x, r.Y + (r.Height - icon) / 2, icon, icon);
        bool hasKeys = false;
        foreach (var k in st.Keys) if (k.Length > 0) { hasKeys = true; break; }
        if (hasKeys)
        {
            Plus(g, box, st, target, dim);
            x += icon + px * 0.45f;
            // The keys, up, left, down, right (W A S D), each lit while its way is pushed.
            for (int i = 0; i < 4; i++)
            {
                var t = st.Keys[WayIndex[i]];
                if (t.Length == 0) continue;
                bool lit = (st.Lit & WayBit[i]) != 0;
                if (x >= r.Right) return;
                g.DrawText(t, px, lit, lit ? target : dim, new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
                x += g.MeasureText(t, px, lit).Width + px * 0.35f;
            }
        }
        if (st.Analog.Length == 0 || x >= r.Right) return;
        if (hasKeys) x += px * 0.2f;
        else
        {
            const string Is = "=";
            g.DrawText(Is, px, false, Color.FromArgb(nameColor.A * 3 / 5, nameColor), new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
            x += g.MeasureText(Is, px, false).Width + px * 0.3f;
        }
        // Where the stick points now, as a dot in a ring, then what it moves.
        var ring = new RectangleF(x, r.Y + (r.Height - icon) / 2, icon, icon);
        float sx = f.PadSticks[st.Stick == 0 ? 0 : 2], sy = f.PadSticks[st.Stick == 0 ? 1 : 3];
        bool moved = Math.Abs(sx) > 0.05f || Math.Abs(sy) > 0.05f;
        g.DrawEllipse(dim, Math.Max(1f, px * 0.08f), ring);
        float d = icon * 0.3f;
        float cx = ring.X + ring.Width / 2 + sx * (icon - d) / 2, cy = ring.Y + ring.Height / 2 + sy * (icon - d) / 2;
        g.FillEllipse(moved ? target : dim, new RectangleF(cx - d / 2, cy - d / 2, d, d));
        x += icon + px * 0.35f;
        if (x < r.Right)
            g.DrawText(st.Analog, px, moved, moved ? target : dim, new RectangleF(x, r.Y, r.Right - x, r.Height), TextAlign.Near, TextAlign.Center);
    }

    /// <summary>A plus: its four arms, each lit while the stick pushes that way.</summary>
    private static void Plus(ICanvas g, RectangleF b, OverlayKeyStick st, Color lit, Color dim)
    {
        float t = b.Width / 3f;
        float cx = b.X + t, cy = b.Y + t;
        g.FillRectangle(Color.FromArgb(dim.A / 2, dim), new RectangleF(cx, cy, t, t));
        Arm(new RectangleF(cx, b.Y, t, t), OverlayKeyStick.Up);
        Arm(new RectangleF(cx + t, cy, t, t), OverlayKeyStick.Right);
        Arm(new RectangleF(cx, cy + t, t, t), OverlayKeyStick.Down);
        Arm(new RectangleF(b.X, cy, t, t), OverlayKeyStick.Left);

        void Arm(RectangleF a, int bit) => g.FillRectangle((st.Lit & bit) != 0 ? lit : dim, RectangleF.Inflate(a, -0.5f, -0.5f));
    }
}
