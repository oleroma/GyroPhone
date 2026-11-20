using System.Reflection;
using System.Runtime.InteropServices;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// The Rust input program (companion/src/input): the same job as <see cref="InputHost"/>, written in Rust, behind
/// "Use the Rust input program" on the Performance page. It is a library (gp_input.dll) that rides inside this exe;
/// <c>GyroPhoneCompanion.exe --input --rust</c> unpacks it next to the exe, loads it and hands the process over
/// to it. So the input process is still this exe: its firewall rules, its place on disk and its admin rights
/// are the ones the original input process has. .NET stays loaded but idle: every thread that carries input is
/// Rust's own, which .NET never pauses.
/// </summary>
internal static class RustInput
{
    /// <summary>With <see cref="InputHost.Arg"/>: be the Rust input program.</summary>
    public const string Arg = "--rust";
    /// <summary>What the Rust input program's version ends with, so a companion can tell which one it attached to.</summary>
    public const string VersionSuffix = "+rust";
    private const string Resource = "gp_input.dll";
    /// <summary>gp_input_run's arguments as this build calls them (gp_input_abi in lib.rs).</summary>
    private const uint Abi = 1;
    /// <summary>The exit code when the library couldn't be unpacked, loaded or called.</summary>
    private const int CantStart = 3;

    /// <summary>The version an input process of this build reports: the Rust one's ends with <see cref="VersionSuffix"/>.</summary>
    public static string VersionOf(bool rust) => rust ? InputHost.Version + VersionSuffix : InputHost.Version;

    /// <summary>False for a build made without Rust installed: the switch then can't be turned on.</summary>
    public static bool InThisBuild => typeof(RustInput).Assembly.GetManifestResourceInfo(Resource) != null;

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate uint AbiFn();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int RunFn(IntPtr version, uint versionLength, int longWait);

    /// <summary>The input process's whole life, in Rust; returns its exit code (as <see cref="InputHost.Run"/>'s).</summary>
    public static int Run(string[] args)
    {
        string? path = null;
        RunFn run;
        try
        {
            path = Unpack();
            IntPtr lib = NativeLibrary.Load(path);
            uint abi = Marshal.GetDelegateForFunctionPointer<AbiFn>(NativeLibrary.GetExport(lib, "gp_input_abi"))();
            if (abi != Abi) throw new InvalidOperationException($"it speaks version {abi}, this companion {Abi}");
            run = Marshal.GetDelegateForFunctionPointer<RunFn>(NativeLibrary.GetExport(lib, "gp_input_run"));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or DllNotFoundException or BadImageFormatException
            or EntryPointNotFoundException or InvalidOperationException)
        {
            using var log = new FileLog(echo: null);
            log.Write($"input: the Rust input program can't start ({path ?? "not unpacked"}): {e.GetType().Name}: {e.Message}");
            return CantStart;
        }
        string version = VersionOf(rust: true);
        bool longWait = args.Contains(Program.WokenArg) || args.Contains(Program.UpdatedArg);
        IntPtr v = Marshal.StringToHGlobalUni(version);
        try
        {
            return run(v, (uint)version.Length, longWait ? 1 : 0);
        }
        finally
        {
            Marshal.FreeHGlobal(v);
        }
    }

    /// <summary>
    /// Puts the library on disk and returns its path: next to the exe, or in %LOCALAPPDATA%\GyroPhone\bin when
    /// that folder can't be written. The file is named after the build, so an update never has to replace one a
    /// running input process still has open; older builds' files are removed.
    /// </summary>
    private static string Unpack()
    {
        using var res = typeof(RustInput).Assembly.GetManifestResourceStream(Resource)
            ?? throw new FileNotFoundException("this build was made without the Rust input program");
        var bytes = new byte[res.Length];
        res.ReadExactly(bytes);
        string name = $"gp_input-{Safe(InputHost.Version)}.dll";
        string exeDir = Path.GetDirectoryName(Environment.ProcessPath ?? "") ?? "";
        string fallback = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GyroPhone", "bin");
        Exception? last = null;
        foreach (string dir in new[] { exeDir, fallback })
        {
            if (dir.Length == 0) continue;
            try
            {
                Directory.CreateDirectory(dir);
                string path = Path.Combine(dir, name);
                if (!Same(path, bytes))
                {
                    string tmp = $"{path}.{Environment.ProcessId}.tmp";
                    File.WriteAllBytes(tmp, bytes);
                    File.Move(tmp, path, overwrite: true);
                }
                RemoveOthers(dir, name);
                return path;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                last = e;
            }
        }
        throw last ?? new IOException("no folder to unpack the Rust input program into");
    }

    private static string Safe(string version)
    {
        var chars = version.Select(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' ? c : '_').ToArray();
        return new string(chars);
    }

    private static bool Same(string path, byte[] bytes)
    {
        var info = new FileInfo(path);
        return info.Exists && info.Length == bytes.Length && File.ReadAllBytes(path).AsSpan().SequenceEqual(bytes);
    }

    private static void RemoveOthers(string dir, string keep)
    {
        foreach (string f in Directory.EnumerateFiles(dir, "gp_input-*"))
        {
            string n = Path.GetFileName(f);
            if (n.Equals(keep, StringComparison.OrdinalIgnoreCase)) continue;
            if (!n.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) && !n.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                File.Delete(f);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // still open in an input process that is on its way out: the next start removes it
            }
        }
    }
}
