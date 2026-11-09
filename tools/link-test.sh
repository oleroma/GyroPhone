#!/usr/bin/env bash
# Link test: finds out why phone packets get lost or held up on Wi-Fi.
# Runs on the PC (Git Bash) while GyroPhone on the phone is connected to the companion.
# Never touches the phone's screen or inputs.
#
#   tools/link-test.sh [minutes] [--open]
#
# 1. Asks the companion (over loopback UDP) to run a Link test: the phone sends fake moving input
#    at its full rate, which the companion's input log counts but never injects.
# 2. Meanwhile, when the phone is on adb, records its Wi-Fi side: logcat (all buffers), the Wi-Fi
#    status every 2 s, and dumpsys wifi / wifiscanner before and after.
# 3. Copies the companion's input.log and companion.log, then runs tools/link-test-analyze.py
#    (when Python is there) to line each dropout up with what the phone's Wi-Fi was doing.
#
# --open starts GyroPhone on the phone first (am start; it connects to the last PC by itself).
# Default 10 minutes. Ctrl+C stops the test early. Output: build/link-test/<time>/.
set -uo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
minutes=10
open=0
for a in "$@"; do
  case "$a" in
    --open) open=1 ;;
    ''|*[!0-9]*) echo "usage: $0 [minutes] [--open]" >&2; exit 2 ;;
    *) minutes="$a" ;;
  esac
done
seconds=$(( minutes * 60 ))
[ "$seconds" -ge 10 ] && [ "$seconds" -le 3600 ] || { echo "minutes must be 1 to 60" >&2; exit 2; }
pkg=com.oleroma.gyrophone
out="build/link-test/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
log() { echo "$(date '+%H:%M:%S') $*" | tee -a "$out/run.txt"; }

# LINK_TEST_CTL: 'G' 'P', version 1, type 0x7E, sid 0, seq 0, u16 seconds, 2 reserved bytes.
send_ctl() {
  local s="$1" lo hi
  lo=$(printf '\\x%02x' $(( s & 255 ))); hi=$(printf '\\x%02x' $(( (s >> 8) & 255 )))
  if (printf "GP\\x01\\x7e\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00${lo}${hi}\\x00\\x00" >/dev/udp/127.0.0.1/47800) 2>/dev/null; then return 0; fi
  powershell.exe -NoProfile -Command "\$u = New-Object Net.Sockets.UdpClient; \$b = [byte[]](0x47,0x50,1,0x7e,0,0,0,0,0,0,0,0,$(( s & 255 )),$(( (s >> 8) & 255 )),0,0); [void]\$u.Send(\$b, \$b.Length, '127.0.0.1', 47800); \$u.Close()" >/dev/null 2>&1
}

# The companion's log folder. A shell started by some tools sees a private copy of %LOCALAPPDATA%,
# so the real one is also tried through the admin share; the newest input.log wins.
log_dir() {
  local best="" bt=0 d t
  for d in "${LOCALAPPDATA:-}/GyroPhone/logs" "//localhost/c\$/Users/${USERNAME:-$USER}/AppData/Local/GyroPhone/logs"; do
    [ -f "$d/input.log" ] || continue
    t=$(stat -c %Y "$d/input.log" 2>/dev/null || echo 0)
    if [ "$t" -gt "$bt" ]; then best="$d"; bt="$t"; fi
  done
  echo "$best"
}

phone=0
if command -v adb >/dev/null 2>&1 && [ "$(adb get-state 2>/dev/null | tr -d '\r')" = device ]; then phone=1; fi
if [ "$phone" = 1 ]; then
  log "phone on adb: $(adb shell getprop ro.product.model | tr -d '\r')"
  if [ "$open" = 1 ]; then
    adb shell am start -n "$pkg/.ui.MainActivity" >/dev/null 2>&1 && log "opened GyroPhone, waiting 10 s for it to connect"
    sleep 10
  fi
  adb shell dumpsys wifi >"$out/wifi-before.txt" 2>&1
  adb shell dumpsys wifiscanner >"$out/wifiscanner-before.txt" 2>&1
  adb shell settings get global wifi_scan_always_enabled >"$out/settings.txt" 2>&1
  adb shell settings get global ble_scan_always_enabled >>"$out/settings.txt" 2>&1
else
  log "phone NOT on adb: only the PC side is recorded (turn on wireless debugging and adb connect to add the phone's Wi-Fi side)"
fi

# Clocks: PC epoch and local time, and the phone's epoch read over adb (good to ~20 ms).
{
  echo "pc_epoch $(date +%s.%N)"
  echo "pc_local $(date '+%Y-%m-%d %H:%M:%S.%N')"
  echo "pc_utc_offset $(date +%z)"
  if [ "$phone" = 1 ]; then
    a=$(date +%s.%N); p=$(adb shell date +%s.%N | tr -d '\r'); b=$(date +%s.%N)
    echo "phone_epoch $p between_pc $a $b"
  fi
} >"$out/clock.txt"

pids=()
if [ "$phone" = 1 ]; then
  adb logcat -b all -v epoch -T 1 >"$out/logcat.txt" 2>&1 &
  pids+=($!)
  ( while :; do
      echo "== $(date +%s.%N)"
      adb shell 'cmd wifi status 2>/dev/null; cat /proc/net/wireless 2>/dev/null' | tr -d '\r'
      sleep 2
    done ) >"$out/wifi-status.txt" 2>&1 &
  pids+=($!)
fi

stop() {
  send_ctl 0 && log "asked the companion to stop the Link test"
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  wait 2>/dev/null
}
trap 'stop; exit 130' INT TERM

if send_ctl "$seconds"; then log "asked the companion for a ${minutes} min Link test"; else log "could not reach the companion on UDP 47800"; fi
sleep 3
dir="$(log_dir)"
if [ -n "$dir" ] && tail -n 200 "$dir/companion.log" 2>/dev/null | grep -q 'link test started'; then
  log "companion confirmed the test"
else
  log "companion did not confirm the test (is it running a build with Link test, and is the phone connected?)"
fi
end=$(( $(date +%s) + seconds ))
while [ "$(date +%s)" -lt "$end" ]; do
  left=$(( end - $(date +%s) ))
  [ $(( left % 60 )) -lt 5 ] && log "$(( left / 60 )) min left"
  sleep 5
done
stop
trap - INT TERM

if [ "$phone" = 1 ]; then
  adb shell dumpsys wifi >"$out/wifi-after.txt" 2>&1
  adb shell dumpsys wifiscanner >"$out/wifiscanner-after.txt" 2>&1
fi
dir="$(log_dir)"
if [ -n "$dir" ]; then
  cp "$dir/input.log" "$out/input.log" 2>/dev/null
  cp "$dir/companion.log" "$out/companion.log" 2>/dev/null
  log "copied the companion's logs from $dir"
else
  log "companion input.log not found"
fi
py=""
for c in python3 python py; do command -v "$c" >/dev/null 2>&1 && { py="$c"; break; }; done
if [ -n "$py" ] && [ -f "$out/input.log" ]; then
  "$py" tools/link-test-analyze.py "$out" >"$out/report.txt" 2>&1
  log "report: $out/report.txt"
  head -40 "$out/report.txt"
else
  log "files in $out (no Python here: run tools/link-test-analyze.py $out elsewhere)"
fi
echo "result: done"
