using System.Drawing;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The 3D shape overlay (the phone's 3D shape display, ported): a globe, a solid, a torus, gimbal
/// rings or a paper plane, turned by the window's pose, drawn flat-on (no perspective) as on the
/// phone. Lines in front are solid, lines behind faint (Hidden edges), faces filled and shaded.
/// Any thread: one drawing at a time.
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
    // P and the meshes' projection arrays are shared: one window draws a shape at a time (the pacer's
    // thread and the UI thread both draw windows).
    private static readonly object DrawLock = new();

    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, int liveAxes)
    {
        if (DrawOnCard(g, area, s, m, liveAxes)) return;
        lock (DrawLock) DrawLocked(g, area, s, m, liveAxes);
    }

    /// <summary>
    /// The same drawing through the graphics card (<see cref="Gpu3D"/>): the shape's lines and faces go up once,
    /// then each frame only its turn. False when the card can't (the CPU draws it then).
    /// </summary>
    private static bool DrawOnCard(ICanvas g, RectangleF area, OverlaySettings s, float[] m, int liveAxes)
    {
        int look = HashCode.Combine(
            HashCode.Combine(s.Shape, s.Subdivision, s.Smooth, s.FillFaces, s.HiddenEdges, s.Equator, s.ScreenMark, s.TopMark),
            HashCode.Combine(s.Color("lines"), s.Color("body"), s.Color("equator"), s.Color("mark"), s.Color("pole")),
            HashCode.Combine(s.Color("gyaw"), s.Color("gpitch"), s.Color("groll")));
        var key = new Gpu3D.Key(look, Gpu3D.TurnOf(m), area, s.Shape == OverlayShape.Gimbal ? liveAxes : 0);
        return Gpu3D.Draw(g, area, key, (area, s, m, liveAxes), static (f, st) => Build(f, st.area, st.s, st.m, st.liveAxes));
    }

    private static void Build(Gpu3D.Frame f, RectangleF area, OverlaySettings s, float[] m, int liveAxes)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        var lines = Color.FromArgb(s.Color("lines"));
        var faint = Color.FromArgb((int)(lines.A * HiddenAlpha), lines);
        var body = Color.FromArgb(s.Color("body"));
        float width = Math.Clamp(r * 0.012f, 1f, 3f);
        int steps = s.Smooth ? SphereSteps : PolySteps;
        var mesh = Meshes.Of(s.Shape, s.Subdivision, s.Smooth);
        string meshName = $"{s.Shape}:{Math.Clamp(s.Subdivision, 0, Meshes.MaxSubdivision)}:{s.Smooth}";
        if (s.Shape == OverlayShape.Gimbal)
        {
            string[] keys = ["gyaw", "gpitch", "groll"];
            var rings = new Gpu3D.Geo[3];
            var colors = new Color[3];
            for (int i = 0; i < 3; i++)
            {
                int ring = i;
                rings[i] = f.Geometry($"shape:gimbal{i}:{steps}", b => GimbalRing(b, ring, steps));
                var col = Color.FromArgb(s.Color(keys[i]));
                bool on = (liveAxes & (1 << i)) != 0;
                colors[i] = Color.FromArgb((int)(col.A * (on ? 1f : 0.3f)), col);
            }
            if (s.HiddenEdges)
                for (int i = 0; i < 3; i++)
                    f.Lines(rings[i], m, c, r, Color.FromArgb((int)(colors[i].A * HiddenAlpha), colors[i]), width * 2.2f, Gpu3D.Side.Back);
            for (int i = 0; i < 3; i++) f.Lines(rings[i], m, c, r, colors[i], width * 2.2f, Gpu3D.Side.Front);
        }
        else if (mesh == null || (s.Smooth && mesh.OnSphere))
        {
            // The globe's circles, or a mesh's edges bent onto the ball: front and back told per pixel.
            var geo = mesh == null
                ? f.Geometry($"shape:globe:{steps}:{s.Equator}", b => Globe(b, steps, s.Equator))
                : f.Geometry("shape:bent:" + meshName, b => Bent(b, mesh));
            if (s.HiddenEdges) f.Lines(geo, m, c, r, faint, width, Gpu3D.Side.Back);
            if (s.FillFaces) Body(f, c, r, body);
            f.Lines(geo, m, c, r, lines, width, Gpu3D.Side.Front);
            f.Lines(f.Geometry("shape:rim", Rim), null, c, r, lines, width, Gpu3D.Side.Both);
            if (mesh == null && s.Equator)
                f.Lines(f.Geometry($"shape:equator:{steps}", b => Circle(b, 0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, steps)), m, c, r,
                    Color.FromArgb(s.Color("equator")), width * 2.2f, Gpu3D.Side.Front);
        }
        else
        {
            var edges = f.Geometry("shape:edges:" + meshName, b => Edges(b, mesh));
            if (s.HiddenEdges) f.Lines(edges, m, c, r, faint, width, Gpu3D.Side.Back, mesh.TwoSided);
            if (s.FillFaces) f.Faces(f.Geometry("shape:faces:" + meshName, b => Faces(b, mesh)), m, c, r, body, mesh.TwoSided);
            // A shape that can hide its own faces: its edges hidden behind nearer faces, as the CPU's far-to-near drawing.
            f.Lines(edges, m, c, r, lines, width, Gpu3D.Side.Front, mesh.TwoSided, depthTest: s.FillFaces && !mesh.Convex);
        }
        if (s.ScreenMark) CardDot(f, c, r, m, 0, 0, 1, Color.FromArgb(s.Color("mark")));
        if (s.TopMark) CardDot(f, c, r, m, 0, 1, 0, Color.FromArgb(s.Color("pole")));
    }

    private static void Body(Gpu3D.Frame f, PointF c, float r, Color body)
    {
        var lit = Color.FromArgb(body.A, body.R + (int)((255 - body.R) * 0.35f), body.G + (int)((255 - body.G) * 0.35f), body.B + (int)((255 - body.B) * 0.35f));
        f.Ball(c, r, new PointF(c.X - r * 0.35f, c.Y - r * 0.4f), lit, body);
    }

    private static void CardDot(Gpu3D.Frame f, PointF c, float r, float[] m, float px, float py, float pz, Color color)
    {
        float z = m[6] * px + m[7] * py + m[8] * pz;
        if (z <= 0f) return;
        float x = c.X + (m[0] * px + m[1] * py + m[2] * pz) * r;
        float y = c.Y - (m[3] * px + m[4] * py + m[5] * pz) * r;
        f.Disk(new PointF(x, y), r * 0.1f * (0.4f + 0.6f * z), color);
    }

    /// <summary>A circle as <see cref="SphereCircle"/>'s, as line pieces.</summary>
    private static void Circle(Gpu3D.GeoBuilder b, float h, float ax, float ay, float az, float rho,
        float ux, float uy, float uz, float vx, float vy, float vz, int steps)
    {
        float px = 0, py = 0, pz = 0;
        for (int i = 0; i <= steps; i++)
        {
            double t = i * (2 * Math.PI / steps);
            float ct = (float)Math.Cos(t), st = (float)Math.Sin(t);
            float x = h * ax + rho * (ct * ux + st * vx), y = h * ay + rho * (ct * uy + st * vy), z = h * az + rho * (ct * uz + st * vz);
            if (i > 0) b.Line(px, py, pz, x, y, z);
            px = x; py = y; pz = z;
        }
    }

    private static void Globe(Gpu3D.GeoBuilder b, int steps, bool equator)
    {
        for (int lat = -60; lat <= 60; lat += 30)
        {
            if (lat == 0 && equator) continue;
            double a = lat * Math.PI / 180;
            Circle(b, (float)Math.Sin(a), 0, 1, 0, (float)Math.Cos(a), 0, 0, 1, 1, 0, 0, steps);
        }
        for (int lon = 0; lon < 180; lon += 30)
        {
            double a = lon * Math.PI / 180;
            Circle(b, 0, 0, 0, 0, 1, 0, 1, 0, (float)Math.Sin(a), 0, (float)Math.Cos(a), steps);
        }
    }

    /// <summary>The ball's outline, in the view's plane (drawn unturned), fine enough to look round at any size.</summary>
    private static void Rim(Gpu3D.GeoBuilder b) => Circle(b, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 256);

    private static void GimbalRing(Gpu3D.GeoBuilder b, int i, int steps)
    {
        float rho = (float)GimbalRadii[i];
        switch (i)
        {
            case 0: Circle(b, 0, 0, 0, 0, rho, 1, 0, 0, 0, 0, 1, steps); break;
            case 1: Circle(b, 0, 0, 0, 0, rho, 0, 1, 0, 0, 0, 1, steps); break;
            default: Circle(b, 0, 0, 0, 0, rho, 1, 0, 0, 0, 1, 0, steps); break;
        }
    }

    /// <summary>Each edge bent onto the ball in the CPU's pieces.</summary>
    private static void Bent(Gpu3D.GeoBuilder b, Mesh mesh)
    {
        var v = mesh.Vertices;
        var e = mesh.Edges;
        for (int k = 0; k < e.Length / 2; k++)
        {
            int ia = e[k * 2] * 3, ib = e[k * 2 + 1] * 3, n = mesh.EdgePieces[k];
            float qx = 0, qy = 0, qz = 0;
            for (int j = 0; j <= n; j++)
            {
                float t = j / (float)n;
                float px = v[ia] + (v[ib] - v[ia]) * t, py = v[ia + 1] + (v[ib + 1] - v[ia + 1]) * t, pz = v[ia + 2] + (v[ib + 2] - v[ia + 2]) * t;
                float l = MathF.Sqrt(px * px + py * py + pz * pz);
                px /= l; py /= l; pz /= l;
                if (j > 0) b.Line(qx, qy, qz, px, py, pz);
                qx = px; qy = py; qz = pz;
            }
        }
    }

    /// <summary>Each edge, in front when either of its faces faces the viewer (as <see cref="Mesh.EdgeFront"/>).</summary>
    private static void Edges(Gpu3D.GeoBuilder b, Mesh mesh)
    {
        var v = mesh.Vertices;
        var e = mesh.Edges;
        System.Numerics.Vector3 N(int f) => new(mesh.Normals[f * 3], mesh.Normals[f * 3 + 1], mesh.Normals[f * 3 + 2]);
        for (int k = 0; k < e.Length / 2; k++)
        {
            int ia = e[k * 2] * 3, ib = e[k * 2 + 1] * 3;
            int f1 = mesh.EdgeFaces[k * 2], f2 = mesh.EdgeFaces[k * 2 + 1];
            b.Edge(v[ia], v[ia + 1], v[ia + 2], v[ib], v[ib + 1], v[ib + 2], N(f1), f2 >= 0 ? N(f2) : null);
        }
    }

    /// <summary>Each face as a fan of triangles, with its normal.</summary>
    private static void Faces(Gpu3D.GeoBuilder b, Mesh mesh)
    {
        var v = mesh.Vertices;
        System.Numerics.Vector3 P(int i) => new(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
        for (int f = 0; f < mesh.Faces.Length; f++)
        {
            var face = mesh.Faces[f];
            var n = new System.Numerics.Vector3(mesh.Normals[f * 3], mesh.Normals[f * 3 + 1], mesh.Normals[f * 3 + 2]);
            for (int i = 1; i + 1 < face.Length; i++) b.Triangle(P(face[0]), P(face[i]), P(face[i + 1]), n);
        }
    }

    private static void DrawLocked(ICanvas g, RectangleF area, OverlaySettings s, float[] m, int liveAxes)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        var lines = Color.FromArgb(s.Color("lines"));
        var faint = Color.FromArgb((int)(lines.A * HiddenAlpha), lines);
        var body = Color.FromArgb(s.Color("body"));
        float width = Math.Clamp(r * 0.012f, 1f, 3f);
        var linePen = (lines, width);
        var faintPen = (faint, width);
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
            g.DrawEllipse(lines, width, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
            if (s.Equator)
            {
                P.Begin(m, c, r, false);
                SphereCircle(0, 0, 1, 0, 1, 0, 0, 1, 1, 0, 0, steps);
                Stroke(g, P.Front, (Color.FromArgb(s.Color("equator")), width * 2.2f));
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
            g.DrawEllipse(lines, width, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
        }
        else
        {
            mesh.Project(m);
            PointF At(int i) => new(c.X + mesh.Tx[i] * r, c.Y - mesh.Ty[i] * r);
            var e = mesh.Edges;
            if (s.HiddenEdges)
                for (int k = 0; k < e.Length / 2; k++)
                    if (!mesh.EdgeFront(k)) g.DrawLine(faint, width, At(e[k * 2]), At(e[k * 2 + 1]), round: true);
            if (!s.FillFaces || mesh.Convex)
            {
                if (s.FillFaces)
                    for (int f = 0; f < mesh.Faces.Length; f++)
                        if (mesh.FaceFront[f]) FillFace(g, mesh, f, At, body);
                for (int k = 0; k < e.Length / 2; k++)
                    if (mesh.EdgeFront(k)) g.DrawLine(lines, width, At(e[k * 2]), At(e[k * 2 + 1]), round: true);
            }
            else
            {
                // Far to near, each face with its own outline, so near ones cover far ones.
                mesh.SortFarToNear();
                foreach (int f in mesh.Order)
                {
                    if (!mesh.FaceFront[f]) continue;
                    var pts = FillFace(g, mesh, f, At, body);
                    g.DrawPolygon(lines, width, pts);
                }
            }
        }
        if (s.ScreenMark) SphereDot(g, c, r, m, 0, 0, 1, Color.FromArgb(s.Color("mark")));
        if (s.TopMark) SphereDot(g, c, r, m, 0, 1, 0, Color.FromArgb(s.Color("pole")));
    }

    private static void Stroke(ICanvas g, List<List<PointF>> lines, (Color Color, float Width) pen)
    {
        foreach (var l in lines) if (l.Count >= 2) g.DrawLines(pen.Color, pen.Width, l.ToArray());
    }

    private static PointF[] FillFace(ICanvas g, Mesh mesh, int f, Func<int, PointF> at, Color body)
    {
        var face = mesh.Faces[f];
        var pts = new PointF[face.Length];
        for (int i = 0; i < face.Length; i++) pts[i] = at(face[i]);
        float k = 0.45f + 0.55f * mesh.FaceShade[f];
        g.FillPolygon(Color.FromArgb(body.A, (int)(body.R * k), (int)(body.G * k), (int)(body.B * k)), pts);
        return pts;
    }

    /// <summary>A smooth ball's fill: one disk, lit from the top left.</summary>
    private static void DrawBody(ICanvas g, PointF c, float r, Color body)
    {
        var lit = Color.FromArgb(body.A, body.R + (int)((255 - body.R) * 0.35f), body.G + (int)((255 - body.G) * 0.35f), body.B + (int)((255 - body.B) * 0.35f));
        g.FillBall(new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r), new PointF(c.X - r * 0.35f, c.Y - r * 0.4f), lit, body);
    }

    /// <summary>Three rings, one per axis (yaw round up, pitch round right, roll round the screen), lit while that axis moves the mouse.</summary>
    private static void DrawGimbal(ICanvas g, PointF c, float r, OverlaySettings s, int liveAxes, float width)
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
                Stroke(g, backs[i], (Color.FromArgb((int)(colors[i].A * HiddenAlpha), colors[i]), width * 2.2f));
            }
        for (int i = 0; i < 3; i++)
        {
            Stroke(g, fronts[i], (colors[i], width * 2.2f));
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
    private static void SphereDot(ICanvas g, PointF c, float r, float[] m, float px, float py, float pz, Color color)
    {
        float z = m[6] * px + m[7] * py + m[8] * pz;
        if (z <= 0f) return;
        float x = c.X + (m[0] * px + m[1] * py + m[2] * pz) * r;
        float y = c.Y - (m[3] * px + m[4] * py + m[5] * pz) * r;
        float d = r * 0.1f * (0.4f + 0.6f * z);
        g.FillEllipse(color, new RectangleF(x - d, y - d, 2 * d, 2 * d));
    }
}
