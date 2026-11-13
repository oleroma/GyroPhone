namespace GyroPhone.Windows;

/// <summary>
/// How the FPS helper (GyroPhone.FrameHelper, run as administrator by a Windows task) passes the frames it reads
/// with <see cref="EtwPresents"/> to the companion, which runs without admin. A one-way named pipe that only this
/// Windows user can open: on connect the helper sends <see cref="Magic"/>, <see cref="Version"/> and the frame
/// watch's Windows error (0 = watching), then one <see cref="RecordSize"/>-byte record per frame (process id,
/// QueryPerformanceCounter ticks; little-endian) and a process id 0 record each second so a closed companion is
/// noticed. The companion closing the pipe stops the watch. The helper reads nothing from the pipe.
/// No dependencies: the helper compiles this file too.
/// </summary>
public static class FrameHelperWire
{
    /// <summary>Bump when the helper or this wire changes: the companion then asks to set the helper up again.</summary>
    public const int Version = 1;
    public const uint Magic = 0x48465047; // "GPFH"
    public const int HelloSize = 12, RecordSize = 12;

    public const string TaskName = "GyroPhone FPS helper";
    public const string ExeName = "GyroPhoneFpsHelper.exe";
    /// <summary>Beside the helper: the <see cref="Version"/> the setup installed.</summary>
    public const string VersionFile = "fps-helper-version.txt";

    /// <summary>Per Windows user, so two signed-in users each get their own helper.</summary>
    public static string PipeName(string userSid) => "GyroPhoneFrames-" + userSid;

    /// <summary>C:\Program Files\GyroPhone: only administrators can change what's in it.</summary>
    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "GyroPhone");
}
