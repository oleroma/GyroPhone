namespace GyroPhone.Protocol;

/// <summary>
/// A gyro sample measured in a gyro space: the phone's own yaw, pitch and roll (deg/s, the phone's
/// stored axis names) against the world's up in the phone's frame (right, up, toward the player,
/// unit length). An exact port of the phone's GyroSpaces.kt (float math in the same order); the
/// shared vectors (gyro-math-v1.json, "spaces") check it. In PC gyro mode <see cref="PcGyro"/> runs
/// it with the up the phone sends with each sample.
/// </summary>
public static class GyroSpaces
{
    public const int Local = 0, World = 1, Player = 2;
    private const float FlatMin = 0.1f, FlatFull = 0.35f;
    private const float PlayerRelax = 1.41f;

    /// <summary>
    /// The sample measured in <paramref name="space"/> against up (<paramref name="ux"/>,
    /// <paramref name="uy"/>, <paramref name="uz"/>) into <paramref name="o"/> (yaw, pitch, roll).
    /// Local, or no up (all 0), leaves it as it is.
    /// </summary>
    public static void InSpace(float yaw, float pitch, float roll, int space, float ux, float uy, float uz, Span<float> o)
    {
        o[0] = yaw; o[1] = pitch; o[2] = roll;
        if (space == Local || (ux == 0f && uy == 0f && uz == 0f)) return;
        float wr = pitch, wu = -yaw, wt = -roll;
        if (space == World)
        {
            o[2] = -(wr * ux + wu * uy + wt * uz);
            float rx = 1f - ux * ux, ry = -ux * uy, rz = -ux * uz;
            float n = MathF.Sqrt(rx * rx + ry * ry + rz * rz);
            float b = CoerceIn((n - FlatMin) / (FlatFull - FlatMin), 0f, 1f);
            if (b <= 0f) return;
            rx /= n; ry /= n; rz /= n;
            float tx = ry * uz - rz * uy, ty = rz * ux - rx * uz, tz = rx * uy - ry * ux;
            float wy = wr * tx + wu * ty + wt * tz;
            float wp = wr * rx + wu * ry + wt * rz;
            o[0] = yaw + (wy - yaw) * b;
            o[1] = pitch + (wp - pitch) * b;
        }
        else if (space == Player)
        {
            float world = -(wu * uy + wt * uz);
            float most = MathF.Sqrt(wu * wu + wt * wt);
            o[2] = Sign(world) * MathF.Min(MathF.Abs(world) * PlayerRelax, most);
        }
    }

    /// <summary>
    /// Each axis measured in its own space (<paramref name="spaces"/>, per axis), as the phone's
    /// GyroMouse.inSpaces: yaw from the sample in yaw's space, pitch in pitch's, roll in roll's.
    /// </summary>
    public static void InSpaces(float yaw, float pitch, float roll, int[] spaces, float ux, float uy, float uz, Span<float> o)
    {
        Span<float> t = stackalloc float[3];
        for (int a = 0; a < 3; a++)
        {
            InSpace(yaw, pitch, roll, spaces[a], ux, uy, uz, t);
            o[a] = t[a];
        }
    }

    private static float Sign(float x) => x > 0f ? 1f : x < 0f ? -1f : x;
    private static float CoerceIn(float x, float min, float max) => x < min ? min : x > max ? max : x;
}
