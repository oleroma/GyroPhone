using System.ComponentModel;
using System.Diagnostics;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// GyroPhoneCompanion.exe.new --finish-update &lt;pid&gt; [--tray] [--watch]: the second half of
/// <see cref="CompanionUpdater"/>. Started from the new build waiting at "&lt;exe&gt;.new" by the instance it
/// replaces. Waits for that instance (and a watcher told to stop) to exit, copies itself over the exe, then
/// starts the companion and/or the watcher from the exe's usual path. The finisher only copies its own file,
/// so nothing it reads is ever moved under it. If the copy fails, it starts the old build again with
/// <see cref="FailedArg"/>, which then doesn't retry this .new until it changes.
/// </summary>
internal static class UpdateFinisher
{
    public const string Arg = "--finish-update";
    public const string TrayArg = "--tray";
    public const string FailedArg = "--update-failed";
    public const string NewSuffix = ".new";
    private const string MutexName = @"Local\GyroPhoneUpdate";
    private const int ExitWaitMs = 30_000;
    private const int CopyTries = 40;
    private const int CopyRetryMs = 500;

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
            bool copied = Copy(self, exe, log);
            log.Write(copied
                ? $"update: {Application.ProductVersion} copied to {exe}"
                : $"update: couldn't replace {exe}; starting the old build again");
            if (tray)
            {
                Start(exe, copied ? $"{Program.RestartedArg} {Program.UpdatedArg}" : $"{Program.RestartedArg} {FailedArg}", log);
                // The watcher comes second, so it finds the companion running instead of starting another one.
                var clock = Stopwatch.StartNew();
                while (watch && !Program.IsRunning() && clock.ElapsedMilliseconds < ExitWaitMs) Thread.Sleep(100);
            }
            if (watch) Start(exe, Watcher.Arg, log);
            return copied ? 0 : 1;
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

    /// <summary>Copies the waiting build over the exe, retrying while an exiting instance still holds it.</summary>
    private static bool Copy(string from, string to, FileLog log)
    {
        if (string.Equals(VersionOf(to), Application.ProductVersion, StringComparison.OrdinalIgnoreCase)) return true;
        Exception? last = null;
        for (int t = 0; t < CopyTries; t++)
        {
            try
            {
                File.Copy(from, to, overwrite: true);
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

    private static void Start(string exe, string args, FileLog log)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo(exe, args)
            {
                UseShellExecute = false,
                WorkingDirectory = Path.GetDirectoryName(exe) ?? "",
            });
        }
        catch (Exception e) when (e is Win32Exception or IOException)
        {
            log.Write($"update: could not start {exe} {args}: {e.GetType().Name}");
        }
    }
}
