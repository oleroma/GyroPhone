using System.Drawing;
using System.Drawing.Drawing2D;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The 3D shape overlay (the phone's 3D shape display, ported): a globe, a solid, a torus, gimbal
/// rings or a paper plane, turned by the window's pose, drawn flat-on (no perspective) as on the
/// phone. Lines in front are solid, lines behind faint (Hidden edges), faces filled and shaded.
/// UI thread only.
/// </summary>
internal static class ShapeDrawing
{
    private const int SphereSteps = 96;
    private const int PolySteps = 12;
    private const float HiddenAlpha = 0.3f;
    private static readonly double[] GimbalRadii = [0.95, 0.82, 0.69];

    /// <summary>Polylines in front of the ball's middle plane and behind it, collected by <see cref="To"/>.</summary>
    private sealed class Pen3
    {
        public readonly List<List<PointF>> Front = [], Back = [];
        public bool KeepBack;
        private float[] _m = [];
        private PointF _c;
        private float _r;
        private float _x, _y, _z;
        private bool _down;
        private List<PointF>? _line;

        public void Begin(float[] m, PointF c, float r, bool keepBack)
        {
            _m = m; _c = c; _r = r; KeepBack = keepBack;
            Front.Clear(); Back.Clear();
            _down = false;
        }

        public void Up() => _down = false;

        private void Start(bool front, PointF p)
        {
            _line = front ? [] : KeepBack ? [] : null;
            if (_line == null) return;
            _line.Add(p);
            (front ? Front : Back).Add(_line);
        }

        /// <summary>The line on to phone-frame point (px, py, pz), cut where it crosses the middle plane (pushed out onto the ball's rim when <paramref name="sphere"/>).</summary>
        public void To(float px, float py, float pz, bool sphere)
        {
            var m = _m;
            float x = m[0] * px + m[1] * py + m[2] * pz;
            float y = m[3] * px + m[4] * py + m[5] * pz;
            float z = m[6] * px + m[7] * py + m[8] * pz;
            var s = new PointF(_c.X + x * _r, _c.Y - y * _r);
            if (!_down) Start(z >= 0f, s);
            else
            {
                bool wasFront = _z >= 0f, front = z >= 0f;
                if (wasFront == front) _line?.Add(s);
                else
                {
                    float t = _z / (_z - z);
                    float rx = _x + (x - _x) * t, ry = _y + (y - _y) * t;
                    if (sphere)
                    {
                        float l = MathF.Sqrt(rx * rx + ry * ry);
                        if (l > 0f) { rx /= l; ry /= l; }
                    }
                    var cut = new PointF(_c.X + rx * _r, _c.Y - ry * _r);
                    _line?.Add(cut);
                    Start(front, cut);
                    _line?.Add(s);
                }
            }
            _x = x; _y = y; _z = z;
            _down = true;
        }
    }

    private static readonly Pen3 P = new();

    public static void Draw(Graphics g, RectangleF area, OverlaySettings s, float[] m, int liveAxes)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        var lines = Color.FromArgb(s.Color("lines"));
        var faint = Color.FromArgb((int)(lines.A * HiddenAlpha), lines);
        var body = Color.FromArgb(s.Color("body"));
        float width = Math.Clamp(r * 0.012f, 1f, 3f);
        using var linePen = new Pen(lines, width) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        using var faintPen = new Pen(faint, width) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        P.Begin(m, c, r, s.HiddenEdges);
        var mesh = Meshes.Of(s.Shape, s.Subdivision, s.Smooth);
        if (s.Shape == OverlayShape.Gimbal) DrawGimbal(g, c, r, s, liveAxes, width);
        else if (mesh == null)
        {
            // The globe: latitudes every 30° round the phone's up axis, meridians every 30°.
            int steps = s.Smooth ? SphereSteps : PolySteps;
            for (int lat = -60; lat <= 60; lat += 30)
            {
                if (lat == 0 && s.Equator) continue;
                double a = lat * Math.PI / 180;
                SphereCircle((float)Math.Sin(a), 0, 1, 0, (float)Math.Cos(a), 0, 0, 1, 1, 0, 0, steps);
            }
            for (int lon = 0; lon < 180; lon += 30)
            {
                double a = lon * Math.PI / 180;
                SphereCircle(0, 0, 0, 0, 1, 0, 1, 0, (float)Math.Sin(a), 0, (float)Math.Cos(a), steps);
            }
            Stroke(g, P.Back, faintPen);
            if (s.FillFaces) DrawBody(g, c, r, body);
            Stroke(g, P.Front, linePen);
            g.DrawEllipse(linePen, c.X - r, c.Y - r, 2 * r, 2 * r);
            if (s.Equator)
            {
                P.Begin(m, c, r, false);
                SphereCircle(0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, steps);
                using var eq = new Pen(Color.FromArgb(s.Color("equator")), width * 2.2f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
                Stroke(g, P.Front, eq);
            }
        }
        else if (s.Smooth && mesh.OnSphere)
        {
            // Each edge bent onto the ball, cut exactly where it crosses the rim.
            var v = mesh.Vertices;
            var e = mesh.Edges;
            for (int k = 0; k < e.Length / 2; k++)
            {
                int ia = e[k * 2] * 3, ib = e[k * 2 + 1] * 3, n = mesh.EdgePieces[k];
                P.Up();
                for (int j = 0; j <= n; j++)
                {
                    float t = j / (float)n;
                    float px = v[ia] + (v[ib] - v[ia]) * t, py = v[ia + 1] + (v[ib + 1] - v[ia + 1]) * t, pz = v[ia + 2] + (v[ib + 2] - v[ia + 2]) * t;
                    float l = MathF.Sqrt(px * px + py * py + pz * pz);
                    P.To(px / l, py / l, pz / l, sphere: true);
                }
            }
            Stroke(g, P.Back, faintPen);
            if (s.FillFaces) DrawBody(g, c, r, body);
            Stroke(g, P.Front, linePen);
            g.DrawEllipse(linePen, c.X - r, c.Y - r, 2 * r, 2 * r);
        }
        else
        {
            mesh.Project(m);
            PointF At(int i) => new(c.X + mesh.Tx[i] * r, c.Y - mesh.Ty[i] * r);
            var e = mesh.Edges;
            if (s.HiddenEdges)
                for (int k = 0; k < e.Length / 2; k++)
                    if (!mesh.EdgeFront(k)) g.DrawLine(faintPen, At(e[k * 2]), At(e[k * 2 + 1]));
            if (!s.FillFaces || mesh.Convex)
            {
                if (s.FillFaces)
                    for (int f = 0; f < mesh.Faces.Length; f++)
                        if (mesh.FaceFront[f]) FillFace(g, mesh, f, At, body);
                for (int k = 0; k < e.Length / 2; k++)
                    if (mesh.EdgeFront(k)) g.DrawLine(linePen, At(e[k * 2]), At(e[k * 2 + 1]));
            }
            else
            {
                // Far to near, each face with its own outline, so near ones cover far ones.
                mesh.SortFarToNear();
                foreach (int f in mesh.Order)
                {
                    if (!mesh.FaceFront[f]) continue;
                    var pts = FillFace(g, mesh, f, At, body);
                    g.DrawPolygon(linePen, pts);
                }
            }
        }
        if (s.ScreenMark) SphereDot(g, c, r, m, 0, 0, 1, Color.FromArgb(s.Color("mark")));
        if (s.TopMark) SphereDot(g, c, r, m, 0, 1, 0, Color.FromArgb(s.Color("pole")));
    }

    private static void Stroke(Graphics g, List<List<PointF>> lines, Pen pen)
    {
        foreach (var l in lines) if (l.Count >= 2) g.DrawLines(pen, l.ToArray());
    }

    private static PointF[] FillFace(Graphics g, Mesh mesh, int f, Func<int, PointF> at, Color body)
    {
        var face = mesh.Faces[f];
        var pts = new PointF[face.Length];
        for (int i = 0; i < face.Length; i++) pts[i] = at(face[i]);
        float k = 0.45f + 0.55f * mesh.FaceShade[f];
        using var b = new SolidBrush(Color.FromArgb(body.A, (int)(body.R * k), (int)(body.G * k), (int)(body.B * k)));
        g.FillPolygon(b, pts);
        return pts;
    }

    /// <summary>A smooth ball's fill: one disk, lit from the top left.</summary>
    private static void DrawBody(Graphics g, PointF c, float r, Color body)
    {
        var lit = Color.FromArgb(body.A, body.R + (int)((255 - body.R) * 0.35f), body.G + (int)((255 - body.G) * 0.35f), body.B + (int)((255 - body.B) * 0.35f));
        using var path = new GraphicsPath();
        path.AddEllipse(c.X - r, c.Y - r, 2 * r, 2 * r);
        using var brush = new PathGradientBrush(path)
        {
            CenterPoint = new PointF(c.X - r * 0.35f, c.Y - r * 0.4f),
            CenterColor = lit,
            SurroundColors = [body],
        };
        g.FillPath(brush, path);
    }

    /// <summary>Three rings, one per axis (yaw round up, pitch round right, roll round the screen), lit while that axis moves the mouse.</summary>
    private static void DrawGimbal(Graphics g, PointF c, float r, OverlaySettings s, int liveAxes, float width)
    {
        int steps = s.Smooth ? SphereSteps : PolySteps;
        string[] keys = ["gyaw", "gpitch", "groll"];
        var fronts = new List<List<PointF>>[3];
        var backs = new List<List<PointF>>[3];
        var colors = new Color[3];
        for (int i = 0; i < 3; i++)
        {
            var col = Color.FromArgb(s.Color(keys[i]));
            bool on = (liveAxes & (1 << i)) != 0;
            colors[i] = Color.FromArgb((int)(col.A * (on ? 1f : 0.3f)), col);
            double rho = GimbalRadii[i];
            P.Up();
            for (int j = 0; j <= steps; j++)
            {
                double t = j * (2 * Math.PI / steps);
                float ct = (float)(rho * Math.Cos(t)), st = (float)(rho * Math.Sin(t));
                switch (i)
                {
                    case 0: P.To(ct, 0, st, sphere: false); break;
                    case 1: P.To(0, ct, st, sphere: false); break;
                    default: P.To(ct, st, 0, sphere: false); break;
                }
            }
            fronts[i] = [.. P.Front];
            backs[i] = [.. P.Back];
            P.Front.Clear();
            P.Back.Clear();
        }
        // Back halves first (faint, under everything), then the front halves.
        if (s.HiddenEdges)
            for (int i = 0; i < 3; i++)
            {
                using var pen = new Pen(Color.FromArgb((int)(colors[i].A * HiddenAlpha), colors[i]), width * 2.2f) { StartCap = LineCap.Round, EndCap = LineCap.Round };
                Stroke(g, backs[i], pen);
            }
        for (int i = 0; i < 3; i++)
        {
            using var pen = new Pen(colors[i], width * 2.2f) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
            Stroke(g, fronts[i], pen);
        }
    }

    /// <summary>A circle on the unit ball <paramref name="h"/> along (ax, ay, az), radius <paramref name="rho"/>, spanned by u and v.</summary>
    private static void SphereCircle(float h, float ax, float ay, float az, float rho,
        float ux, float uy, float uz, float vx, float vy, float vz, int steps)
    {
        P.Up();
        for (int i = 0; i <= steps; i++)
        {
            double t = i * (2 * Math.PI / steps);
            float ct = (float)Math.Cos(t), st = (float)Math.Sin(t);
            P.To(h * ax + rho * (ct * ux + st * vx), h * ay + rho * (ct * uy + st * vy), h * az + rho * (ct * uz + st * vz), sphere: true);
        }
    }

    /// <summary>A mark on the ball at the phone-frame direction (px, py, pz), shown while it faces the player.</summary>
    private static void SphereDot(Graphics g, PointF c, float r, float[] m, float px, float py, float pz, Color color)
    {
        float z = m[6] * px + m[7] * py + m[8] * pz;
        if (z <= 0f) return;
        float x = c.X + (m[0] * px + m[1] * py + m[2] * pz) * r;
        float y = c.Y - (m[3] * px + m[4] * py + m[5] * pz) * r;
        float d = r * 0.1f * (0.4f + 0.6f * z);
        using var b = new SolidBrush(color);
        g.FillEllipse(b, x - d, y - d, 2 * d, 2 * d);
    }
}
