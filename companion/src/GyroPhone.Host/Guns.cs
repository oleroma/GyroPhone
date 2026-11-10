namespace GyroPhone.Host;

/// <summary>
/// The pointer's guns (OverlaySettings.LaserGun): the Gun look's models, and for the Crosshair look the recoil it kicks
/// like. Each has its own model (boxes in its own frame: x right, y up, z toward the stock, the muzzle out ahead at
/// <see cref="Gun.MuzzleZ"/>), recoil, default fire rate, muzzle size (<see cref="Gun.Bore"/>, how wide its flash is)
/// and calibre (<see cref="Gun.Flash"/>, how long), and its scope (none on the pistol). As the phone's Guns.
/// </summary>
public static class Guns
{
    /// <summary>A round scope along the barrel at height Y, from Front to Back (z), its tube Radius, its objective Bell.</summary>
    public sealed record Scope(float Y, float Front, float Back, float Radius, float Bell);

    /// <summary>One gun. Boxes: centre x, y, z and half sizes. PivotZ: what it pitches about (the shoulder, or the wrist for a pistol).</summary>
    public sealed record Gun(string Key, string Name, float[] Boxes, float MuzzleZ, float Bore, float Flash,
        CrosshairKick.Recoil Recoil, int Rpm, Scope? Scope, float PivotZ = 0.6f);

    public static readonly Gun Pistol = new(
        "pistol", "Pistol",
        [
            // slide, frame, grip, trigger guard, rear sight, front sight
            0f, 0.035f, -0.2f, 0.035f, 0.04f, 0.25f,
            0f, -0.02f, -0.17f, 0.03f, 0.025f, 0.2f,
            0f, -0.16f, 0.0f, 0.032f, 0.13f, 0.055f,
            0f, -0.07f, -0.1f, 0.008f, 0.03f, 0.045f,
            0f, 0.085f, 0.02f, 0.025f, 0.012f, 0.01f,
            0f, 0.085f, -0.42f, 0.006f, 0.012f, 0.01f,
        ],
        MuzzleZ: -0.46f, Bore: 0.012f, Flash: 0.12f,
        Recoil: new CrosshairKick.Recoil(18f, 35f, 120f, 220f, 0.1f, 16f),
        Rpm: 400, Scope: null, PivotZ: 0.15f);

    public static readonly Gun SMG = new(
        "smg", "SMG",
        [
            // receiver, shroud, barrel, stock, magazine, grip, front sight
            0f, 0f, 0f, 0.05f, 0.07f, 0.22f,
            0f, 0f, -0.32f, 0.032f, 0.032f, 0.12f,
            0f, 0f, -0.5f, 0.014f, 0.014f, 0.06f,
            0f, -0.02f, 0.42f, 0.03f, 0.06f, 0.2f,
            0f, -0.2f, -0.06f, 0.025f, 0.12f, 0.03f,
            0f, -0.16f, 0.15f, 0.028f, 0.09f, 0.04f,
            0f, 0.08f, -0.36f, 0.01f, 0.04f, 0.012f,
        ],
        MuzzleZ: -0.56f, Bore: 0.016f, Flash: 0.16f,
        Recoil: new CrosshairKick.Recoil(14f, 25f, 90f, 150f, 0.06f, 5f),
        Rpm: 800, Scope: new Scope(0.13f, -0.12f, 0.16f, 0.026f, 0.036f), PivotZ: 0.45f);

    public static readonly Gun AR = new(
        "ar", "Assault rifle",
        [
            // stock, buffer tube, receiver, carry rail, handguard, barrel, muzzle, front sight, magazine, grip
            0.0f, -0.05f, 0.68f, 0.05f, 0.09f, 0.2f,
            0.0f, 0.0f, 0.42f, 0.03f, 0.03f, 0.08f,
            0.0f, 0.0f, 0.12f, 0.055f, 0.075f, 0.22f,
            0.0f, 0.1f, 0.1f, 0.028f, 0.025f, 0.18f,
            0.0f, 0.0f, -0.32f, 0.055f, 0.055f, 0.2f,
            0.0f, 0.0f, -0.7f, 0.018f, 0.018f, 0.18f,
            0.0f, 0.0f, -0.9f, 0.026f, 0.026f, 0.03f,
            0.0f, 0.07f, -0.5f, 0.012f, 0.06f, 0.015f,
            0.0f, -0.2f, 0.0f, 0.035f, 0.12f, 0.06f,
            0.0f, -0.17f, 0.28f, 0.03f, 0.09f, 0.045f,
        ],
        MuzzleZ: -0.93f, Bore: 0.02f, Flash: 0.22f,
        Recoil: new CrosshairKick.Recoil(24f, 42f, 150f, 280f, 0.12f, 10f),
        Rpm: 600, Scope: new Scope(0.17f, -0.12f, 0.3f, 0.032f, 0.045f), PivotZ: 0.6f);

    public static readonly Gun BattleRifle = new(
        "battle", "Battle rifle",
        [
            // stock, receiver, handguard, barrel, muzzle brake, front sight, magazine, grip, rail
            0f, -0.04f, 0.7f, 0.055f, 0.1f, 0.2f,
            0f, 0f, 0.12f, 0.06f, 0.08f, 0.26f,
            0f, 0f, -0.36f, 0.06f, 0.06f, 0.24f,
            0f, 0f, -0.76f, 0.02f, 0.02f, 0.18f,
            0f, 0f, -0.94f, 0.032f, 0.032f, 0.035f,
            0f, 0.08f, -0.56f, 0.012f, 0.06f, 0.015f,
            0f, -0.2f, -0.02f, 0.04f, 0.12f, 0.07f,
            0f, -0.17f, 0.32f, 0.03f, 0.09f, 0.045f,
            0f, 0.1f, 0.06f, 0.03f, 0.022f, 0.24f,
        ],
        MuzzleZ: -0.975f, Bore: 0.024f, Flash: 0.28f,
        Recoil: new CrosshairKick.Recoil(28f, 55f, 180f, 340f, 0.16f, 13f),
        Rpm: 450, Scope: new Scope(0.17f, -0.16f, 0.3f, 0.034f, 0.05f), PivotZ: 0.6f);

    public static readonly Gun LMG = new(
        "lmg", "LMG",
        [
            // stock, receiver, shroud, barrel, box magazine, grip, carry handle, folded bipod
            0f, -0.03f, 0.64f, 0.05f, 0.09f, 0.2f,
            0f, 0f, 0.1f, 0.07f, 0.09f, 0.25f,
            0f, 0.01f, -0.36f, 0.045f, 0.045f, 0.22f,
            0f, 0f, -0.76f, 0.022f, 0.022f, 0.18f,
            0f, -0.18f, 0.02f, 0.07f, 0.09f, 0.08f,
            0f, -0.17f, 0.32f, 0.03f, 0.09f, 0.045f,
            0f, 0.14f, -0.02f, 0.015f, 0.04f, 0.09f,
            0f, -0.06f, -0.66f, 0.012f, 0.012f, 0.16f,
        ],
        MuzzleZ: -0.94f, Bore: 0.022f, Flash: 0.28f,
        Recoil: new CrosshairKick.Recoil(22f, 40f, 140f, 260f, 0.1f, 8f),
        Rpm: 700, Scope: new Scope(0.17f, -0.2f, 0.18f, 0.032f, 0.045f), PivotZ: 0.6f);

    public static readonly Gun Shotgun = new(
        "shotgun", "Shotgun",
        [
            // stock, receiver, barrel, magazine tube, pump, bead sight
            0f, -0.06f, 0.56f, 0.045f, 0.1f, 0.25f,
            0f, 0f, 0.1f, 0.05f, 0.08f, 0.18f,
            0f, 0.02f, -0.45f, 0.03f, 0.03f, 0.4f,
            0f, -0.05f, -0.35f, 0.025f, 0.025f, 0.3f,
            0f, -0.05f, -0.38f, 0.042f, 0.042f, 0.12f,
            0f, 0.06f, -0.82f, 0.008f, 0.01f, 0.008f,
        ],
        MuzzleZ: -0.85f, Bore: 0.034f, Flash: 0.36f,
        Recoil: new CrosshairKick.Recoil(30f, 80f, 240f, 450f, 0.22f, 18f),
        Rpm: 80, Scope: new Scope(0.12f, -0.1f, 0.22f, 0.03f, 0.042f), PivotZ: 0.6f);

    public static readonly Gun Sniper = new(
        "sniper", "Sniper",
        [
            // stock, cheek rest, receiver, barrel, muzzle brake, bolt handle, magazine, grip
            0f, -0.04f, 0.6f, 0.05f, 0.11f, 0.24f,
            0f, 0.06f, 0.56f, 0.03f, 0.03f, 0.12f,
            0f, 0f, 0.1f, 0.05f, 0.06f, 0.22f,
            0f, 0f, -0.58f, 0.02f, 0.02f, 0.34f,
            0f, 0f, -0.94f, 0.032f, 0.032f, 0.035f,
            0.07f, 0.02f, 0.2f, 0.03f, 0.012f, 0.012f,
            0f, -0.12f, 0.05f, 0.03f, 0.06f, 0.05f,
            0f, -0.16f, 0.32f, 0.03f, 0.09f, 0.045f,
        ],
        MuzzleZ: -0.975f, Bore: 0.024f, Flash: 0.38f,
        Recoil: new CrosshairKick.Recoil(34f, 100f, 300f, 600f, 0.26f, 14f),
        Rpm: 45, Scope: new Scope(0.16f, -0.26f, 0.32f, 0.04f, 0.065f), PivotZ: 0.6f);

    /// <summary>Every gun, by its number.</summary>
    public static readonly Gun[] All = [Pistol, SMG, AR, BattleRifle, LMG, Shotgun, Sniper];

    /// <summary>The default gun's number: the assault rifle.</summary>
    public const int Default = 2;

    /// <summary>Gun <paramref name="i"/>, or the assault rifle.</summary>
    public static Gun Of(int i) => i >= 0 && i < All.Length ? All[i] : AR;
}
