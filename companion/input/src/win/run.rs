//! The program's start-up on Windows: makes every Windows piece, hands them to `InputHost` and waits until
//! the companion says Exit. The same steps, in the same order, as the C# `InputHost.Run`.

use std::path::PathBuf;
use std::sync::Arc;

use crate::control::names;
use crate::file_log::FileLog;
use crate::host::{InputHost, Platform};
use crate::ports::PadSink;
use crate::udp::{CatchUpWait, Log};
use crate::usb_link::AoaUsbLink;
use crate::watchdog::TickSleep;
use crate::win::basics::{
    DpapiKeyProtector, ExactCatchUpWait, HighResolutionSleep, QpcClock, SendInputSink, is_self_elevated, mmcss_enter, power_qos_off,
    set_process_priority_high,
};
use crate::win::drivers::{self, GyroPhoneInputDriver};
use crate::win::pc_pad::XInputPcPad;
use crate::win::process::{
    NamedAppendLock, NamedEvent, NamedPipeListener, RegionOpenError, local_clock, local_folder, local_stamp, machine_name, mutex_exists,
    open_region, roaming_folder, session_id, set_named_event, take_instance_lock, user_name, wait_for_exit, watch_folder,
};
use crate::win::raw_input::{RawDeliveryProbe, RawInputListener};
use crate::win::usb::WindowsUsb;
use crate::win::{probes, wide};
use crate::{host, wire};

/// The build this is, as the companion checks it against its own ("0.1.<build>-<commit>"); set by the build.
pub const VERSION: &str = match option_env!("GP_INPUT_VERSION") {
    Some(v) => v,
    None => "dev",
};

const COMPANION_MUTEX: &str = r"Local\GyroPhoneCompanion";
const WATCHER_YIELD: &str = r"Local\GyroPhoneWatcherYield";
const COMPANION_EXE: &str = "GyroPhoneCompanion.exe";
/// The companion's "start without showing the window, the input process is already running".
const REATTACH_ARG: &str = "--reattach";

/// Exit codes: 0 done (or another input process already runs), 1 settings.json stayed locked (the companion
/// says so), 2 start-up failed (the log says why).
pub fn run() -> i32 {
    // One input process per Windows session, the C# one included: they share the mutex.
    let Some(_instance) = take_instance_lock(names::INPUT_MUTEX) else {
        return 0;
    };
    // The companion sets this for the C# input process it starts; a companion this process starts again must
    // not get it.
    // SAFETY: nothing else runs yet (no thread has been started), so no one reads the environment meanwhile.
    unsafe { std::env::remove_var("DOTNET_GCgen0size") };
    // Above the game's normal-priority threads, so it is never queued behind them.
    set_process_priority_high();
    let long_wait = std::env::args().any(|a| a == "--woken" || a == "--updated");

    let logs = local_folder().join("logs");
    let file = Arc::new(file_log(&logs, "companion.log", 1_000_000));
    let input_file = Arc::new(file_log(&logs, "input.log", 5_000_000));
    let log: Log = {
        let f = file.clone();
        Arc::new(move |m: &str| f.write(m))
    };
    let input_log: Log = {
        let f = input_file.clone();
        Arc::new(move |m: &str| f.write(m))
    };
    let code = serve(&log, input_log, &file, long_wait);
    input_file.close();
    file.close();
    code
}

fn file_log(dir: &std::path::Path, name: &str, max_bytes: u64) -> FileLog {
    let path = dir.join(name);
    let lock: Box<dyn crate::file_log::AppendLock> = match NamedAppendLock::new(&path) {
        Some(l) => Box::new(l),
        None => Box::new(crate::file_log::NoAppendLock),
    };
    FileLog::new(dir, name, max_bytes, Box::new(local_stamp), lock)
}

fn serve(log: &Log, input_log: Log, file: &Arc<FileLog>, long_wait: bool) -> i32 {
    drivers::set_log(log);
    let region = match open_region(names::REGION) {
        Ok(r) => r,
        Err(RegionOpenError::OtherLayout) => {
            log("input: the shared memory belongs to another build that still runs; not starting");
            return 2;
        }
        Err(RegionOpenError::Io(e)) => {
            log(&format!("input: the shared memory can't be opened: {e}"));
            return 2;
        }
    };
    let overlay_wake = match NamedEvent::create(names::OVERLAY_WAKE) {
        Ok(e) => e,
        Err(e) => {
            log(&format!("input: the overlay wake event can't be made: {e}"));
            return 2;
        }
    };
    let pipes = match NamedPipeListener::new(&names::pipe(&user_name(), session_id())) {
        Ok(p) => p,
        Err(e) => {
            log(&format!("input: the pipe can't be made: {e}"));
            return 2;
        }
    };
    // Windows may otherwise run a process that never has focus on efficient cores at low clocks.
    power_qos_off(true, log);

    let clock = Arc::new(QpcClock::new());
    let roaming = roaming_folder();
    let local = local_folder();
    let platform = Platform {
        version: VERSION.to_string(),
        pid: std::process::id(),
        elevated: is_self_elevated(),
        machine_name: machine_name(),
        log: log.clone(),
        log_error: {
            let f = file.clone();
            Arc::new(move || f.last_error())
        },
        input_log,
        local_time: Arc::new(local_clock),
        clock: clock.clone(),
        settings_path: roaming.join("settings.json"),
        held_path: local.join("held.json"),
        keys_dir: roaming,
        send_input: Arc::new(SendInputSink::new(log.clone())),
        input_driver: Arc::new(GyroPhoneInputDriver),
        pads: {
            let log = log.clone();
            Arc::new(move || -> (Arc<dyn PadSink>, Option<String>) { drivers::pads(&log) })
        },
        protector: Arc::new(DpapiKeyProtector),
        region,
        region_name: names::REGION.to_string(),
        overlay_wake: Arc::new(move || overlay_wake.set()),
        overlay_wake_name: names::OVERLAY_WAKE.to_string(),
        pc_pad: Some(Arc::new(XInputPcPad::new(log.clone(), clock))),
        key_listener: Some(Arc::new(RawInputListener::new(log.clone()))),
        delivery_probe: {
            let log = log.clone();
            Arc::new(move || -> Option<Arc<dyn host::DeliveryProbe>> { Some(Arc::new(RawDeliveryProbe::new(log.clone()))) })
        },
        watch_folder: {
            let log = log.clone();
            Arc::new(move |dir, changed| watch_folder(dir, changed, &log))
        },
        rx_thread_start: Some({
            let log = log.clone();
            Arc::new(move || {
                mmcss_enter("Games", &log);
                power_qos_off(false, &log);
            })
        }),
        catch_up_wait: Some({
            let log = log.clone();
            Arc::new(move || -> Box<dyn CatchUpWait> { Box::new(ExactCatchUpWait::new(log.clone())) })
        }),
        tick_sleep: Some(Arc::new(|| -> Box<dyn TickSleep> { Box::new(HighResolutionSleep::new()) })),
        probes: probes::probes(),
        usb: Arc::new(AoaUsbLink::new(Arc::new(WindowsUsb { log: log.clone() }), wire::PORT, log.clone())),
        yield_port: Arc::new(|| set_named_event(WATCHER_YIELD)),
        pipes: Arc::new(pipes),
        relaunch_companion: {
            let log = log.clone();
            Arc::new(move |pid, delay_ms, still_wanted| relaunch_companion(pid, delay_ms, still_wanted, log.clone()))
        },
        bind: None,
        retry_ms: 0,
    };

    let host = match InputHost::start(platform, long_wait) {
        Ok(h) => h,
        Err(_) => return 1, // settings.json stayed locked: the companion says so
    };
    InputHost::install_panic_hook(&host);
    host.serve();
    host.wait_exit();
    host.shutdown();
    0
}

/// The companion crashed or was killed: start it again once it is surely gone, unless a new one attached.
fn relaunch_companion(pid: i32, delay_ms: u64, still_wanted: Box<dyn Fn() -> bool + Send>, log: Log) {
    let work = move || {
        if pid > 0 {
            wait_for_exit(pid as u32, 5000);
        }
        std::thread::sleep(std::time::Duration::from_millis(delay_ms));
        if !still_wanted() || mutex_exists(COMPANION_MUTEX) {
            return;
        }
        let Some(dir) = exe_folder() else {
            log("input: starting the companion failed: this program's folder is unknown");
            return;
        };
        let exe = dir.join(COMPANION_EXE);
        if let Err(e) = start_detached(&exe, &dir) {
            log(&format!("input: starting the companion failed: {e}"));
        }
    };
    let _ = std::thread::Builder::new().name("gp-relaunch".to_string()).spawn(work);
}

fn exe_folder() -> Option<PathBuf> {
    std::env::current_exe().ok()?.parent().map(|p| p.to_path_buf())
}

/// Starts the companion with `--reattach`, in its own folder, inheriting none of this process's handles (a
/// companion holding the input pipe or a device handle open would keep them alive after this process ends).
fn start_detached(exe: &std::path::Path, dir: &std::path::Path) -> std::io::Result<()> {
    use windows_sys::Win32::System::Threading as th;
    let mut command = wide(&format!("\"{}\" {REATTACH_ARG}", exe.display()));
    let exe_w = wide(&exe.to_string_lossy());
    let dir_w = wide(&dir.to_string_lossy());
    // SAFETY: zeroed is the documented starting state of both structs.
    let mut startup: th::STARTUPINFOW = unsafe { std::mem::zeroed() };
    startup.cb = std::mem::size_of::<th::STARTUPINFOW>() as u32;
    // SAFETY: as above.
    let mut made: th::PROCESS_INFORMATION = unsafe { std::mem::zeroed() };
    // SAFETY: NUL-ended strings that outlive the call; the command line is writable as the API asks; no handle
    // is inherited; the environment is this process's own, less the C# input process's variable `run`
    // removed.
    let ok = unsafe {
        th::CreateProcessW(
            exe_w.as_ptr(),
            command.as_mut_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            0,
            0,
            std::ptr::null(),
            dir_w.as_ptr(),
            &startup,
            &mut made,
        )
    };
    if ok == 0 {
        return Err(std::io::Error::from_raw_os_error(crate::win::last_error() as i32));
    }
    drop(crate::win::Handle::new(made.hThread));
    drop(crate::win::Handle::new(made.hProcess));
    Ok(())
}
