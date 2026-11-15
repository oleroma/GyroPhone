using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;

namespace GyroPhone.Windows;

/// <summary>
/// Sets up the FPS helper with one admin prompt: copies GyroPhoneFpsHelper.exe (carried inside the companion) to
/// C:\Program Files\GyroPhone, where only administrators can change it, and adds the Windows task
/// "GyroPhone FPS helper" that starts it as administrator at this user's sign-in without asking. The companion
/// itself keeps running without admin. Undo: delete the task in Task Scheduler and the folder.
/// </summary>
public static class FpsHelperSetup
{
    /// <summary>The installed helper's <see cref="FrameHelperWire.Version"/>, or null when it isn't set up.</summary>
    public static int? Installed()
    {
        try
        {
            string dir = FrameHelperWire.Folder;
            if (!File.Exists(Path.Combine(dir, FrameHelperWire.ExeName))) return null;
            return int.TryParse(File.ReadAllText(Path.Combine(dir, FrameHelperWire.VersionFile)).Trim(), out int v) ? v : 0;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// Installs (or updates) the helper and its task, then starts it. One admin prompt. True when done; false when
    /// the prompt was declined, this build carries no helper, or a step failed (logged).
    /// </summary>
    public static bool Install(Action<string> log)
    {
        try
        {
            using var res = Assembly.GetEntryAssembly()?.GetManifestResourceStream(FrameHelperWire.ExeName);
            if (res == null)
            {
                log("fps helper: this build carries no helper (only published builds do)");
                return false;
            }
            string temp = Path.Combine(Path.GetTempPath(), "GyroPhone");
            Directory.CreateDirectory(temp);
            string src = Path.Combine(temp, FrameHelperWire.ExeName);
            byte[] exe;
            using (var ms = new MemoryStream())
            {
                res.CopyTo(ms);
                exe = ms.ToArray();
            }
            File.WriteAllBytes(src, exe);
            string hash = Convert.ToHexString(SHA256.HashData(exe));
            string sid;
            using (var id = WindowsIdentity.GetCurrent()) sid = id.User?.Value ?? throw new InvalidOperationException("no user SID");

            var psi = new ProcessStartInfo("powershell.exe",
                "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand " +
                Convert.ToBase64String(Encoding.Unicode.GetBytes(Script(src, hash, sid))))
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            if (!p.WaitForExit(60_000))
            {
                log("fps helper: setup didn't finish in a minute");
                return false;
            }
            log($"fps helper: setup exit {p.ExitCode}");
            return p.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            log("fps helper: admin prompt declined");
            return false;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            log($"fps helper: setup failed: {e.GetType().Name}: {e.Message}");
            return false;
        }
    }

    /// <summary>
    /// The admin step. The copy is checked against the hash only once it sits in the protected folder, so nothing
    /// can swap the file in the temp folder in between; the task's settings are written here, not read from a file.
    /// </summary>
    private static string Script(string src, string hash, string sid)
    {
        static string Q(string s) => "'" + s.Replace("'", "''") + "'";
        string dir = FrameHelperWire.Folder;
        string exe = Path.Combine(dir, FrameHelperWire.ExeName);
        string xml = TaskXml(exe, dir, sid);
        return $$"""
            $ErrorActionPreference = 'Stop'
            $dir = {{Q(dir)}}; $exe = {{Q(exe)}}; $tmp = $exe + '.new'; $task = {{Q(FrameHelperWire.TaskName)}}
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            try { Stop-ScheduledTask -TaskName $task } catch { }
            try { Get-Process -Name 'GyroPhoneFpsHelper' -ErrorAction SilentlyContinue | Stop-Process -Force } catch { }
            Start-Sleep -Milliseconds 300
            Copy-Item -LiteralPath {{Q(src)}} -Destination $tmp -Force
            if ((Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash -ne {{Q(hash)}}) { Remove-Item -LiteralPath $tmp -Force; exit 3 }
            Move-Item -LiteralPath $tmp -Destination $exe -Force
            Set-Content -LiteralPath (Join-Path $dir {{Q(FrameHelperWire.VersionFile)}}) -Value {{Q(FrameHelperWire.Version.ToString())}}
            Register-ScheduledTask -TaskName $task -Xml {{Q(xml)}} -Force | Out-Null
            Start-ScheduledTask -TaskName $task
            exit 0
            """;
    }

    /// <summary>
    /// At this user's sign-in, as this user with admin rights, no time limit, on battery too; this user may also
    /// start it (the companion does when it was closed).
    /// </summary>
    private static string TaskXml(string exe, string dir, string sid) => $"""
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo>
            <Author>GyroPhone</Author>
            <Description>Watches the game's frames for the GyroPhone FPS overlay. Idle until the GyroPhone companion asks.</Description>
            <SecurityDescriptor>D:(A;;FA;;;BA)(A;;FA;;;SY)(A;;GRGX;;;{sid})</SecurityDescriptor>
          </RegistrationInfo>
          <Triggers>
            <LogonTrigger>
              <Enabled>true</Enabled>
              <UserId>{sid}</UserId>
            </LogonTrigger>
          </Triggers>
          <Principals>
            <Principal id="Author">
              <UserId>{sid}</UserId>
              <LogonType>InteractiveToken</LogonType>
              <RunLevel>HighestAvailable</RunLevel>
            </Principal>
          </Principals>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
            <AllowHardTerminate>true</AllowHardTerminate>
            <StartWhenAvailable>false</StartWhenAvailable>
            <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
            <IdleSettings>
              <StopOnIdleEnd>false</StopOnIdleEnd>
              <RestartOnIdle>false</RestartOnIdle>
            </IdleSettings>
            <AllowStartOnDemand>true</AllowStartOnDemand>
            <Enabled>true</Enabled>
            <Hidden>false</Hidden>
            <RunOnlyIfIdle>false</RunOnlyIfIdle>
            <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
            <Priority>5</Priority>
          </Settings>
          <Actions Context="Author">
            <Exec>
              <Command>"{exe}"</Command>
              <WorkingDirectory>{dir}</WorkingDirectory>
            </Exec>
          </Actions>
        </Task>
        """;
}
