using System.Drawing;
using System.Numerics;
using GyroPhone.Host;
using GyroPhone.Host.Models3D;

namespace GyroPhone.Tray;

/// <summary>
/// The pointer's Gun look with a real gun (shipped models <c>gun-&lt;key&gt;.glb</c>, in the gun's own frame of
/// <see cref="Guns"/>: x right, y up, z toward the stock), drawn by the graphics card with the real look's materials and
/// light (<see cref="Gpu3D.Frame.Lit"/>). It moves exactly as the box gun did (<see cref="LaserTrace.RifleAt"/>: fitted,
/// shouldered, kicked, lowered, from the hip); the slide (or pump) runs back with each kick, and the scope grows on while
/// aiming as the box gun's. A gun without a model, or a card that can't, keeps the box gun.
/// </summary>
internal static class RealGun
{
    /// <summary>Whether gun <paramref name="key"/> has a model and the card can draw it.</summary>
    public static bool Has(string key) => Gpu3D.LitStatus.Length == 0 && Model3D.IsShipped(Model3D.Gun(key));

    // Per thread (the pacer's, or the UI thread's): the parts' places, made each picture.
    [ThreadStatic] private static Gpu3D.PartPose[]? t_parts;
    [ThreadStatic] private static Gpu3D.Material[]? t_mats;

    /// <summary>
    /// <see cref="LaserTrace.RifleAt"/> for points as one matrix (row vectors: p' = p · M): fitted, then pitched about
    /// its pivot by its climb and lowering, then pulled back.
    /// </summary>
    private static Matrix4x4 RifleMatrix(Guns.Gun g, float aim, float kick, float lower, float hip)
    {
        var o = LaserTrace.RifleAt(0f, 0f, 0f, aim, kick, false, g, lower, hip);
        var ex = LaserTrace.RifleAt(1f, 0f, 0f, aim, kick, false, g, lower, hip);
        var ey = LaserTrace.RifleAt(0f, 1f, 0f, aim, kick, false, g, lower, hip);
        var ez = LaserTrace.RifleAt(0f, 0f, 1f, aim, kick, false, g, lower, hip);
        // RifleAt is affine: its columns from where the axes' ends go.
        return new Matrix4x4(
            ex.X - o.X, ex.Y - o.Y, ex.Z - o.Z, 0f,
            ey.X - o.X, ey.Y - o.Y, ey.Z - o.Z, 0f,
            ez.X - o.X, ez.Y - o.Y, ez.Z - o.Z, 0f,
            o.X, o.Y, o.Z, 1f);
    }

    /// <summary>
    /// Draws gun <paramref name="g"/> into <paramref name="fr"/> turned by pose <paramref name="m"/> and seen as
    /// <see cref="LaserTrace.ToView"/> sees it (<paramref name="axon"/>, <paramref name="turned"/>), in perspective with
    /// <paramref name="persp"/>, at centre <paramref name="c"/>, radius <paramref name="r"/> in pixels.
    /// </summary>
    public static void Draw(Gpu3D.Frame fr, Guns.Gun g, float[] m, bool axon, float[]? turned, bool persp, float aim, float kick,
        float lower, bool scope, float hip, PointF c, float r, Color rifle)
    {
        var model = Model3D.Shipped(Model3D.Gun(g.Key));
        var mesh = fr.Model("model:" + model.Name, model);
        float ak = Math.Clamp(aim, 0f, 1f);
        var place = RifleMatrix(g, ak, kick, lower, hip);

        // The view: the pose, then the sphere's turn (its transpose) and the fixed axonometric view, as ToView.
        Span<float> v = stackalloc float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
            {
                // Column j of m through ToView.
                var q = LaserTrace.ToView(m[j], m[3 + j], m[6 + j], axon, turned);
                v[j] = q.X; v[3 + j] = q.Y; v[6 + j] = q.Z;
            }
        var view = new float[9];
        v.CopyTo(view);

        var parts = t_parts is { } tp && tp.Length >= model.Parts.Length ? tp : t_parts = new Gpu3D.PartPose[Math.Max(model.Parts.Length, 32)];
        // The slide (or pump) runs back with the kick; the scope grows on about its middle while aiming.
        float slide = Math.Clamp(kick, 0f, 1f) * (g.Key == "pistol" ? 0.07f : g.Key == "shotgun" ? 0.08f : 0.04f);
        var sc = g.Scope;
        float scopeK = scope && sc != null ? ak : 0f;
        var scopeAt = sc == null ? Vector3.Zero : new Vector3(0f, sc.Y, (sc.Front + sc.Back) / 2f);
        var scopeMove = Matrix4x4.CreateTranslation(-scopeAt) * Matrix4x4.CreateScale(scopeK) * Matrix4x4.CreateTranslation(scopeAt);
        // The whole gun moved so its barrel's hole sits on the pointer's axis (x 0, y 0), where the beam, the laser sight
        // and the muzzle flash start (LaserTrace.MuzzleAt): the pistol's and shotgun's bores sit a little above it.
        int bore = model.PartIndex(g.Key + "_bore");
        var boreAt = bore >= 0 ? model.Parts[bore].Centre : Vector3.Zero;
        var onAxis = Matrix4x4.CreateTranslation(-boreAt.X, -boreAt.Y, 0f);
        float reach = 0f;
        for (int i = 0; i < model.Parts.Length; i++)
        {
            string n = model.Parts[i].Name;
            var move = Matrix4x4.Identity;
            if (n.EndsWith("_slide", StringComparison.Ordinal) || n.EndsWith("_pump", StringComparison.Ordinal))
                move = Matrix4x4.CreateTranslation(0f, 0f, slide);
            else if (n.Contains("_scope", StringComparison.Ordinal))
                move = scopeMove;
            var full = move * onAxis * place;
            Matrix4x4.Invert(full, out var inv);
            parts[i] = new Gpu3D.PartPose
            {
                R0 = new Vector4(full.M11, full.M21, full.M31, full.M41),
                R1 = new Vector4(full.M12, full.M22, full.M32, full.M42),
                R2 = new Vector4(full.M13, full.M23, full.M33, full.M43),
                N0 = new Vector4(inv.M11, inv.M12, inv.M13, 0f),
                N1 = new Vector4(inv.M21, inv.M22, inv.M23, 0f),
                N2 = new Vector4(inv.M31, inv.M32, inv.M33, 0f),
            };
            var p = model.Parts[i];
            for (int k = 0; k < 8; k++)
            {
                var corner = new Vector3((k & 1) == 0 ? p.Min.X : p.Max.X, (k & 2) == 0 ? p.Min.Y : p.Max.Y, (k & 4) == 0 ? p.Min.Z : p.Max.Z);
                reach = MathF.Max(reach, Vector3.Transform(corner, full).Length());
            }
        }

        var mats = t_mats ??= new Gpu3D.Material[Gpu3D.MaterialRoom];
        RealLook.FillMaterials(mats, rifle, rifle, rifle);
        // Without perspective: seen from far away, so it shows flat (and depth still works).
        float camera = persp ? LaserTrace.CameraDistance : 200f;
        fr.Lit(mesh, parts.AsSpan(0, model.Parts.Length), mats, view, c, r, camera, MathF.Max(reach, 0.1f) * 1.05f, 0, rifle);
    }
}
