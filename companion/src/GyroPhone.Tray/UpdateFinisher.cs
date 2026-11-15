using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using GyroPhone.Host.Update;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// GyroPhoneCompanion.exe.new --finish-update &lt;pid&gt; [--tray] [--watch]: the second half of
/// <see cref="CompanionUpdater"/>. Started from the new build waiting at "&lt;exe&gt;.new" by the instance it
/// replaces. Waits for that instance (and a watcher told to stop) to exit, moves itself over the exe, then
/// starts the companion and/or the watcher from the exe's usual path.
/// <para>Moved, not copied: a rename keeps the file the antivirus already checked when this finisher started,
/// so the companion starts at once from it. A copy is a new file that gets checked all over again, about 4 s
/// for the 190 MB exe, every second of it with no companion running.</para>
/// <para>The exe is single-file and opens itself by path to load each assembly the first time it is used. Once
/// moved, this finisher's own path is gone, so everything after the move uses only assemblies already loaded
/// (the runtime's own, this one, FileLog's) and starts processes through CreateProcess directly, not
/// <see cref="Process.Start(ProcessStartInfo)"/>, which may load more.</para>
/// <para>Then it moves in OpenCV's DLLs that wait beside the exe as "&lt;dll&gt;.new"
/// (<see cref="UpdateInfo.WaitingDlls"/>), which the old instance may have held. If the move fails, it starts the
/// old build again with <see cref="FailedArg"/>, which then doesn't retry this .new until it is installed again.</para>
/// </summary>
internal static class UpdateFinisher
{
    public const string Arg = "--finish-update";
    public const string TrayArg = "--tray";
    public const string FailedArg = "--update-failed";
    public const string NewSuffix = UpdateInfo.NewSuffix;
    private const string MutexName = @"Local\GyroPhoneUpdate";
    private const int ExitWaitMs = 30_000;
    private const int CopyTries = 40;
    private const int CopyRetryMs = 500;
    // The waiting DLLs are optional (latency test only), so they hold up the restart for at most 5 s each.
    private const int DllMoveTries = 10;

    /// <summary>The product version of a waiting build, or null when there is none (or it can't be read yet).</summary>
    public static string? VersionOf(string path)
    {
        try
        {
            return File.Exists(path) ? FileVersionInfo.GetVersionInfo(path).ProductVersion : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null; // being copied
        }
    }

    /// <summary>
    /// The waiting build's version and write time, or null. A failed update isn't retried until this changes,
    /// so installing the same build again retries it.
    /// </summary>
    public static string? StampOf(string path)
    {
        string? version = VersionOf(path);
        if (version == null) return null;
        try
        {
            return $"{version}@{File.GetLastWriteTimeUtc(path).Ticks}";
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Starts the finisher from <paramref name="newPath"/>. False if it couldn't be started.</summary>
    public static bool Launch(string newPath, bool tray, bool watch, Action<string> log)
    {
        var args = $"{Arg} {Environment.ProcessId}{(tray ? " " + TrayArg : "")}{(watch ? " " + Watcher.Arg : "")}";
        try
        {
            using var p = Process.Start(new ProcessStartInfo(newPath, args)
            {
                UseShellExecute = false, // keeps this instance's elevation
                WorkingDirectory = Path.GetDirectoryName(newPath) ?? "",
            });
            return true;
        }
        catch (Exception e) when (e is Win32Exception or IOException)
        {
            log($"could not start the update from {newPath}: {e.GetType().Name} {e.Message}");
            return false;
        }
    }

    public static int Run(string[] args)
    {
        using var log = new FileLog();
        string self = Environment.ProcessPath ?? Application.ExecutablePath;
        if (!self.EndsWith(NewSuffix, StringComparison.OrdinalIgnoreCase))
        {
            log.Write($"update: {self} is not a waiting build, nothing to do");
            return 1;
        }
        string exe = self[..^NewSuffix.Length];
        bool tray = args.Contains(TrayArg), watch = args.Contains(Watcher.Arg);
        int i = Array.IndexOf(args, Arg);
        int pid = i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out int n) ? n : 0;

        using var mutex = new Mutex(false, MutexName);
        try
        {
            mutex.WaitOne();
        }
        catch (AbandonedMutexException)
        {
            // a finisher died holding it; ours now
        }
        try
        {
            WaitForExit(pid);
            WaitForWatcherExit();
            // Everything the steps after the move need is worked out (and its assemblies loaded) before it.
            string version = Application.ProductVersion;
            string dir = Path.GetDirectoryName(exe) ?? "";
            var dlls = UpdateInfo.WaitingDlls(dir);
            Program.IsRunning();
            bool moved = MoveIn(self, exe, log);
            try
            {
                log.Write(moved
                    ? $"update: {version} moved to {exe}"
                    : $"update: couldn't replace {exe}; starting the old build again");
                if (tray)
                {
                    Start(exe, moved ? $"{Program.RestartedArg} {Program.UpdatedArg}" : $"{Program.RestartedArg} {FailedArg}", log);
                }
                // Only with the new exe in place: the old build keeps the DLLs it came with. After the companion
                // starts: it doesn't load them until the latency test opens.
                if (moved) MoveWaitingDlls(dlls, log);
                if (tray)
                {
                    // The watcher comes second, so it finds the companion running instead of starting another one.
                    var clock = Stopwatch.StartNew();
                    while (watch && !Program.IsRunning() && clock.ElapsedMilliseconds < ExitWaitMs) Thread.Sleep(100);
                }
                if (watch) Start(exe, Watcher.Arg, log);
            }
            catch (Exception e)
            {
                // Not expected; logged so a missing assembly after the move shows up instead of a silent exit.
                log.Write($"update: finishing failed: {e.GetType().Name} {e.Message}");
            }
            return moved ? 0 : 1;
        }
        finally
        {
            mutex.ReleaseMutex();
        }
    }

    private static void WaitForExit(int pid)
    {
        if (pid <= 0) return;
        try
        {
            using var p = Process.GetProcessById(pid);
            p.WaitForExit(ExitWaitMs);
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException or Win32Exception)
        {
            // already gone
        }
    }

    private static void WaitForWatcherExit()
    {
        var clock = Stopwatch.StartNew();
        while (Watcher.IsRunning() && clock.ElapsedMilliseconds < ExitWaitMs) Thread.Sleep(200);
    }

    /// <summary>Moves the waiting build (this finisher's own file) over the exe, retrying while an exiting instance still holds it.</summary>
    private static bool MoveIn(string from, string to, FileLog log)
    {
        Exception? last = null;
        for (int t = 0; t < CopyTries; t++)
        {
            try
            {
                File.Move(from, to, overwrite: true);
                return true;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                last = e;
                Thread.Sleep(CopyRetryMs);
            }
        }
        log.Write($"update: copy failed: {last?.GetType().Name} {last?.Message}");
        return false;
    }

    /// <summary>
    /// Puts each waiting "&lt;dll&gt;.new" in place of its DLL by renaming it (no copy of the big file on the
    /// restart path), retrying for a few seconds while an exiting instance still holds it. One that can't be
    /// moved stays waiting (logged): only the latency test uses these DLLs, and the next update tries again.
    /// </summary>
    private static void MoveWaitingDlls(IReadOnlyList<(string Waiting, string Target)> dlls, FileLog log)
    {
        foreach (var (waiting, target) in dlls)
        {
            Exception? last = null;
            for (int t = 0; t < DllMoveTries; t++)
            {
                try
                {
                    File.Move(waiting, target, overwrite: true);
                    last = null;
                    break;
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    last = e;
                    Thread.Sleep(CopyRetryMs);
                }
            }
            log.Write(last == null
                ? $"update: {Path.GetFileName(target)} updated"
                : $"update: couldn't update {Path.GetFileName(target)}: {last.GetType().Name} {last.Message}");
        }
    }

    /// <summary>
    /// Starts <paramref name="exe"/> through CreateProcess (no shell, so it keeps this process's elevation), using
    /// nothing outside the runtime's own assembly: it runs after this finisher's file was moved.
    /// </summary>
    private static void Start(string exe, string args, FileLog log)
    {
        var si = new StartupInfo { cb = Marshal.SizeOf<StartupInfo>() };
        var cmd = new System.Text.StringBuilder($"\"{exe}\" {args}");
        if (CreateProcessW(exe, cmd, IntPtr.Zero, IntPtr.Zero, false, 0, IntPtr.Zero, Path.GetDirectoryName(exe), ref si, out var pi))
        {
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
        }
        else
        {
            log.Write($"update: could not start {exe} {args}: error {Marshal.GetLastWin32Error()}");
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int cb;
        public IntPtr lpReserved, lpDesktop, lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInformation
    {
        public IntPtr hProcess, hThread;
        public int dwProcessId, dwThreadId;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(string lpApplicationName, System.Text.StringBuilder lpCommandLine,
        IntPtr lpProcessAttributes, IntPtr lpThreadAttributes, bool bInheritHandles, int dwCreationFlags,
        IntPtr lpEnvironment, string? lpCurrentDirectory, ref StartupInfo lpStartupInfo, out ProcessInformation lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);
}
