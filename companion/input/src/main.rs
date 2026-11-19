// No console window: it is started by the companion and by the watcher, never by hand.
#![windows_subsystem = "windows"]

fn main() {
    std::process::exit(gp_input::run());
}
