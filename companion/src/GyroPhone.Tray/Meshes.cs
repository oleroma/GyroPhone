using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// A shape for the 3D shape overlay (the phone's Shapes3D, ported): <see cref="Vertices"/> (x, y, z
/// in the phone's frame, the shape within the unit ball), <see cref="Faces"/>, each face's outward
/// normal and centre, and its edges with the faces either side. <see cref="TwoSided"/>: an open
/// sheet (the paper plane). <see cref="Convex"/>: no face hides another that faces the viewer.
/// <see cref="OnSphere"/>: its vertices lie on the unit ball, so it can be drawn smooth. The scratch
/// arrays hold one frame's projection (only while ShapeDrawing holds its lock).
/// </summary>
internal sealed class Mesh
{
    public const double SmoothPieceDeg = 6.0;

    public readonly float[] Vertices;
    public readonly int[][] Faces;
    public readonly bool TwoSided, Convex, OnSphere;
    public readonly float[] Normals, Centres;
    public readonly int[] Edges, EdgeFaces, EdgePieces;
    public readonly float[] Tx, Ty, Tz, FaceZ, FaceShade;
    public readonly bool[] FaceFront;
    public readonly int[] Order;

    public Mesh(float[] vertices, int[][] faces, bool twoSided = false, bool convex = true, bool onSphere = false, Func<int, float[]>? normalsOf = null)
    {
        Vertices = vertices;
        Faces = faces;
        TwoSided = twoSided;
        Convex = convex;
        OnSphere = onSphere;
        Normals = new float[faces.Length * 3];
        Centres = new float[faces.Length * 3];
        for (int f = 0; f < faces.Length; f++)
        {
            float cx = 0, cy = 0, cz = 0;
            foreach (int i in faces[f]) { cx += vertices[i * 3]; cy += vertices[i * 3 + 1]; cz += vertices[i * 3 + 2]; }
            cx /= faces[f].Length; cy /= faces[f].Length; cz /= faces[f].Length;
            Centres[f * 3] = cx; Centres[f * 3 + 1] = cy; Centres[f * 3 + 2] = cz;
            var n = normalsOf?.Invoke(f) ?? (convex && !twoSided ? [cx, cy, cz] : Cross(faces[f]));
            float l = Math.Max(MathF.Sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1e-6f);
            Normals[f * 3] = n[0] / l; Normals[f * 3 + 1] = n[1] / l; Normals[f * 3 + 2] = n[2] / l;
        }
        var index = new Dictionary<long, int>();
        var pairs = new List<int>();
        var sides = new List<int>();
        for (int f = 0; f < faces.Length; f++)
        {
            var face = faces[f];
            for (int k = 0; k < face.Length; k++)
            {
                int a = face[k], b = face[(k + 1) % face.Length];
                long key = Math.Min(a, b) * 100000L + Math.Max(a, b);
                if (index.TryGetValue(key, out int e)) sides[e * 2 + 1] = f;
                else
                {
                    index[key] = pairs.Count / 2;
                    pairs.Add(a); pairs.Add(b);
                    sides.Add(f); sides.Add(-1);
                }
            }
        }
        Edges = pairs.ToArray();
        EdgeFaces = sides.ToArray();
        EdgePieces = new int[Edges.Length / 2];
        for (int e = 0; e < EdgePieces.Length; e++)
        {
            int a = Edges[e * 2] * 3, b = Edges[e * 2 + 1] * 3;
            float dot = Math.Clamp(vertices[a] * vertices[b] + vertices[a + 1] * vertices[b + 1] + vertices[a + 2] * vertices[b + 2], -1f, 1f);
            EdgePieces[e] = Math.Clamp((int)Math.Ceiling(Math.Acos(dot) * 180 / Math.PI / SmoothPieceDeg), 1, 16);
        }
        int nv = vertices.Length / 3;
        Tx = new float[nv]; Ty = new float[nv]; Tz = new float[nv];
        FaceZ = new float[faces.Length];
        FaceShade = new float[faces.Length];
        FaceFront = new bool[faces.Length];
        Order = Enumerable.Range(0, faces.Length).ToArray();
    }

    private float[] Cross(int[] face)
    {
        var v = Vertices;
        int a = face[0] * 3, b = face[1] * 3, c = face[2] * 3;
        float ux = v[b] - v[a], uy = v[b + 1] - v[a + 1], uz = v[b + 2] - v[a + 2];
        float vx = v[c] - v[a], vy = v[c + 1] - v[a + 1], vz = v[c + 2] - v[a + 2];
        return [uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx];
    }

    /// <summary>Turns every vertex by row-major <paramref name="m"/> (view frame, z toward the viewer) and marks each face front facing or not, its depth and shade.</summary>
    public void Project(float[] m)
    {
        var v = Vertices;
        for (int i = 0; i < Tx.Length; i++)
        {
            float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
            Tx[i] = m[0] * x + m[1] * y + m[2] * z;
            Ty[i] = m[3] * x + m[4] * y + m[5] * z;
            Tz[i] = m[6] * x + m[7] * y + m[8] * z;
        }
        for (int f = 0; f < Faces.Length; f++)
        {
            float nz = m[6] * Normals[f * 3] + m[7] * Normals[f * 3 + 1] + m[8] * Normals[f * 3 + 2];
            FaceFront[f] = TwoSided || nz > 0f;
            FaceShade[f] = MathF.Abs(nz);
            FaceZ[f] = m[6] * Centres[f * 3] + m[7] * Centres[f * 3 + 1] + m[8] * Centres[f * 3 + 2];
        }
    }

    /// <summary><see cref="Order"/> sorted far to near (nearly sorted from the last frame: insertion sort).</summary>
    public void SortFarToNear()
    {
        for (int i = 1; i < Order.Length; i++)
        {
            int f = Order[i], j = i - 1;
            while (j >= 0 && FaceZ[Order[j]] > FaceZ[f]) { Order[j + 1] = Order[j]; j--; }
            Order[j + 1] = f;
        }
    }

    public bool EdgeFront(int e)
    {
        int f1 = EdgeFaces[e * 2], f2 = EdgeFaces[e * 2 + 1];
        return FaceFront[f1] || (f2 >= 0 && FaceFront[f2]);
    }
}

/// <summary>The meshes, built on first use; null for the globe and gimbal rings (drawn as circles).</summary>
internal static class Meshes
{
    public const int MaxSubdivision = 3;
    private const double TorusR = 0.66, TorusTube = 0.3;
    private static readonly Dictionary<string, Mesh> Cache = [];

    public static Mesh? Of(OverlayShape shape, int subdivision, bool smooth)
    {
        string? key = shape switch
        {
            OverlayShape.Globe or OverlayShape.Gimbal => null,
            OverlayShape.Icosphere => $"ico{Math.Clamp(subdivision, 0, MaxSubdivision)}",
            OverlayShape.Torus => $"torus{smooth}",
            _ => shape.ToString(),
        };
        if (key == null) return null;
        if (Cache.TryGetValue(key, out var m)) return m;
        m = shape switch
        {
            OverlayShape.Icosphere => Icosphere(Math.Clamp(subdivision, 0, MaxSubdivision)),
            OverlayShape.Cube => Cube(),
            OverlayShape.Tetrahedron => Tetrahedron(),
            OverlayShape.Dodecahedron => Dodecahedron(),
            OverlayShape.Torus => smooth ? Torus(28, 14) : Torus(12, 6),
            _ => Plane(),
        };
        Cache[key] = m;
        return m;
    }

    private static float[] Unit(List<float[]> pts)
    {
        var o = new float[pts.Count * 3];
        for (int i = 0; i < pts.Count; i++)
        {
            var p = pts[i];
            float l = MathF.Sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            o[i * 3] = p[0] / l; o[i * 3 + 1] = p[1] / l; o[i * 3 + 2] = p[2] / l;
        }
        return o;
    }

    private static Mesh Icosphere(int n)
    {
        float t = (float)((1 + Math.Sqrt(5.0)) / 2);
        var pts = new List<float[]>();
        int Add(float x, float y, float z)
        {
            float l = MathF.Sqrt(x * x + y * y + z * z);
            pts.Add([x / l, y / l, z / l]);
            return pts.Count - 1;
        }
        foreach (var (a, b) in new[] { (-1f, t), (1f, t), (-1f, -t), (1f, -t) })
        {
            Add(a, b, 0f); Add(0f, a, b); Add(b, 0f, a);
        }
        float tip = MathF.Atan2(1f, t);
        foreach (var p in pts)
        {
            float x = p[0], y = p[1];
            p[0] = x * MathF.Cos(tip) - y * MathF.Sin(tip);
            p[1] = x * MathF.Sin(tip) + y * MathF.Cos(tip);
        }
        var faces = Triangles(pts, 2 / MathF.Sqrt(1 + t * t));
        for (int r = 0; r < n; r++)
        {
            var mids = new Dictionary<long, int>();
            int Mid(int i, int j)
            {
                long key = Math.Min(i, j) * 100000L + Math.Max(i, j);
                if (!mids.TryGetValue(key, out int v))
                    mids[key] = v = Add(pts[i][0] + pts[j][0], pts[i][1] + pts[j][1], pts[i][2] + pts[j][2]);
                return v;
            }
            faces = faces.SelectMany(f =>
            {
                int ab = Mid(f[0], f[1]), bc = Mid(f[1], f[2]), ca = Mid(f[2], f[0]);
                return new[] { new[] { f[0], ab, ca }, new[] { ab, f[1], bc }, new[] { ca, bc, f[2] }, new[] { ab, bc, ca } };
            }).ToList();
        }
        return new Mesh(Unit(pts), faces.ToArray(), onSphere: true);
    }

    private static List<int[]> Triangles(List<float[]> pts, float edge)
    {
        bool Near(int i, int j) => MathF.Abs(Dist(pts[i], pts[j]) - edge) < 1e-3f;
        var o = new List<int[]>();
        for (int i = 0; i < pts.Count; i++)
            for (int j = i + 1; j < pts.Count; j++)
                for (int k = j + 1; k < pts.Count; k++)
                    if (Near(i, j) && Near(j, k) && Near(i, k)) o.Add([i, j, k]);
        return o;
    }

    private static float Dist(float[] a, float[] b)
    {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return MathF.Sqrt(x * x + y * y + z * z);
    }

    private static Mesh Cube()
    {
        var pts = new List<float[]>();
        foreach (float x in new[] { -1f, 1f }) foreach (float y in new[] { -1f, 1f }) foreach (float z in new[] { -1f, 1f }) pts.Add([x, y, z]);
        static int At(int x, int y, int z) => x * 4 + y * 2 + z;
        int[][] faces =
        [
            [At(0, 0, 0), At(0, 1, 0), At(0, 1, 1), At(0, 0, 1)],
            [At(1, 0, 0), At(1, 0, 1), At(1, 1, 1), At(1, 1, 0)],
            [At(0, 0, 0), At(0, 0, 1), At(1, 0, 1), At(1, 0, 0)],
            [At(0, 1, 0), At(1, 1, 0), At(1, 1, 1), At(0, 1, 1)],
            [At(0, 0, 0), At(1, 0, 0), At(1, 1, 0), At(0, 1, 0)],
            [At(0, 0, 1), At(0, 1, 1), At(1, 1, 1), At(1, 0, 1)],
        ];
        return new Mesh(Unit(pts), faces, onSphere: true);
    }

    private static Mesh Tetrahedron()
    {
        var pts = new List<float[]> { new[] { 0f, 1f, 0f } };
        for (int i = 0; i < 3; i++)
        {
            double a = i * 2 * Math.PI / 3;
            pts.Add([(float)(Math.Sqrt(8.0 / 9) * Math.Cos(a)), -1f / 3, (float)(Math.Sqrt(8.0 / 9) * Math.Sin(a))]);
        }
        int[][] faces = [[0, 1, 2], [0, 2, 3], [0, 3, 1], [1, 3, 2]];
        return new Mesh(Unit(pts), faces, onSphere: true);
    }

    private static Mesh Dodecahedron()
    {
        float p = (float)((1 + Math.Sqrt(5.0)) / 2), q = 1 / p;
        var pts = new List<float[]>();
        foreach (float x in new[] { -1f, 1f }) foreach (float y in new[] { -1f, 1f }) foreach (float z in new[] { -1f, 1f }) pts.Add([x, y, z]);
        foreach (float a in new[] { -1f, 1f })
            foreach (float b in new[] { -1f, 1f })
            {
                pts.Add([0f, a * q, b * p]);
                pts.Add([a * q, b * p, 0f]);
                pts.Add([a * p, 0f, b * q]);
            }
        var v = Unit(pts);
        var faces = new List<int[]>();
        foreach (float a in new[] { -1f, 1f })
            foreach (float b in new[] { -1f, 1f })
                foreach (var d in new[] { new[] { 0f, a * p, b }, new[] { a * p, b, 0f }, new[] { b, 0f, a * p } })
                {
                    float l = MathF.Sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                    float dx = d[0] / l, dy = d[1] / l, dz = d[2] / l;
                    var near = Enumerable.Range(0, pts.Count).OrderByDescending(i => v[i * 3] * dx + v[i * 3 + 1] * dy + v[i * 3 + 2] * dz).Take(5).ToArray();
                    int f0 = near[0] * 3;
                    float k = v[f0] * dx + v[f0 + 1] * dy + v[f0 + 2] * dz;
                    float x0 = v[f0] - k * dx, y0 = v[f0 + 1] - k * dy, z0 = v[f0 + 2] - k * dz;
                    float mm = MathF.Sqrt(x0 * x0 + y0 * y0 + z0 * z0);
                    float ux = x0 / mm, uy = y0 / mm, uz = z0 / mm;
                    float wx = dy * uz - dz * uy, wy = dz * ux - dx * uz, wz = dx * uy - dy * ux;
                    faces.Add(near.OrderBy(i =>
                    {
                        float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
                        return MathF.Atan2(x * wx + y * wy + z * wz, x * ux + y * uy + z * uz);
                    }).ToArray());
                }
        return new Mesh(v, faces.ToArray(), onSphere: true);
    }

    private static Mesh Torus(int u, int w)
    {
        var v = new float[u * w * 3];
        for (int i = 0; i < u; i++)
            for (int j = 0; j < w; j++)
            {
                double a = 2 * Math.PI * i / u, b = 2 * Math.PI * j / w;
                double ring = TorusR + TorusTube * Math.Cos(b);
                int at = (i * w + j) * 3;
                v[at] = (float)(ring * Math.Cos(a));
                v[at + 1] = (float)(TorusTube * Math.Sin(b));
                v[at + 2] = (float)(ring * Math.Sin(a));
            }
        int At(int i, int j) => (i % u) * w + (j % w);
        var faces = new int[u * w][];
        for (int k = 0; k < u * w; k++)
        {
            int i = k / w, j = k % w;
            faces[k] = [At(i, j), At(i, j + 1), At(i + 1, j + 1), At(i + 1, j)];
        }
        return new Mesh(v, faces, convex: false, normalsOf: k =>
        {
            double a = 2 * Math.PI * (k / w + 0.5) / u, b = 2 * Math.PI * (k % w + 0.5) / w;
            return [(float)(Math.Cos(b) * Math.Cos(a)), (float)Math.Sin(b), (float)(Math.Cos(b) * Math.Sin(a))];
        });
    }

    /// <summary>A paper plane, nose up, wings spread across, keel behind (away from the screen).</summary>
    private static Mesh Plane()
    {
        float[] v =
        [
            0f, 0.95f, 0f,
            -0.62f, -0.72f, 0.06f,
            0f, -0.72f, 0f,
            0.62f, -0.72f, 0.06f,
            0f, -0.72f, -0.32f,
        ];
        int[][] faces = [[0, 1, 2], [0, 2, 3], [0, 2, 4]];
        return new Mesh(v, faces, twoSided: true, convex: false);
    }
}
