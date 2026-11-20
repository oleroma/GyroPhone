#!/usr/bin/env bash
# Collects GyroPhone Diagnostics saves and merges the phone's and the PC's into one file.
# Runs on the PC (Git Bash) with the phone on adb. Never touches the phone's screen or inputs.
#
#   tools/debug-collect.sh            merge the newest phone save with the PC's of the same moment
#   tools/debug-collect.sh save       save both sides now (Diagnostics must be on), then merge
#   tools/debug-collect.sh dump [n]   print the phone's live status and its newest n events (default 60)
#   tools/debug-collect.sh merge <phone.txt> <pc.txt>   merge two files you already have
#
# Output goes to build/debug/ in the repo: the phone file as pulled, and merged-<time>.txt with
# every line of both sides sorted by time (PC time; the phone's times are shifted by the clock
# offset the two measured, when both files carry a clock-pair line).
# The PC's recordings are in Documents\GyroPhone\Debug (DEBUG_PC_DIR overrides it).
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
out=build/debug
mkdir -p "$out"
pkg=com.oleroma.gyrophone
svc="$pkg/.service.GamepadService"

pc_dir() {
  if [ -n "${DEBUG_PC_DIR:-}" ]; then echo "$DEBUG_PC_DIR"; return; fi
  local docs=""
  if command -v powershell.exe >/dev/null 2>&1; then
    docs="$(powershell.exe -NoProfile -Command "[Environment]::GetFolderPath('MyDocuments')" 2>/dev/null | tr -d '\r')"
  fi
  [ -n "$docs" ] || docs="${USERPROFILE:-$HOME}/Documents"
  if command -v cygpath >/dev/null 2>&1; then docs="$(cygpath -u "$docs")"; fi
  echo "$docs/GyroPhone/Debug"
}

# Wall ms a recording was saved at (its "# saved at wall ms" line).
saved_ms() { sed -n 's/^# saved at wall ms \([0-9]*\).*/\1/p' "$1" | head -1; }

merge() {
  local phone="$1" pc="$2" dest="$3"
  # shift (ms) that moves phone wall times onto the PC's clock:
  # pc_wall = W2 + (P + O - H) / 1000 for the phone instant at W, so shift = that - W.
  local shift
  shift="$(awk '
    /^# clock-pair: wall_ms=[0-9]+ phone_us=/ { for (i = 3; i <= NF; i++) { split($i, kv, "="); p[kv[1]] = kv[2] } }
    /^# clock-pair: wall_ms=[0-9]+ host_us=/  { for (i = 3; i <= NF; i++) { split($i, kv, "="); h[kv[1]] = kv[2] } }
    END {
      if (("wall_ms" in p) && ("wall_ms" in h)) printf "%d", h["wall_ms"] + (p["phone_us"] + p["offset_us"] - h["host_us"]) / 1000 - p["wall_ms"]
      else printf "0"
    }' "$phone" "$pc")"
  {
    echo "# GyroPhone debug recording, merged"
    echo "# phone: $(basename "$phone") · pc: $(basename "$pc") · phone times shifted by $shift ms onto the PC clock"
    grep '^#' "$phone" | sed 's/^# /# phone | /'
    grep '^#' "$pc" | sed 's/^# /# pc    | /'
    echo "# time (PC ms)	side	kind	what"
    {
      grep -v '^#' "$phone" | awk -v s="$shift" 'BEGIN { FS = OFS = "\t" } NF >= 4 { $1 = $1 + s; print }'
      grep -v '^#' "$pc" | awk 'BEGIN { FS = OFS = "\t" } NF >= 4 { print }'
    } | sort -t "$(printf '\t')" -k1,1n -s
  } >"$dest"
}

# The phone keeps its recordings in the app's external files, which adb reads directly (the
# phone runs a non-debuggable build, so run-as doesn't work there).
phone_dir=/sdcard/Android/data/$pkg/files/debug

pull_newest_phone() {
  local name
  name="$(adb exec-out ls -1 "$phone_dir" 2>/dev/null | tr -d '\r' | grep '^phone-.*\.txt$' | sort | tail -1 || true)"
  if [ -z "$name" ]; then
    echo "No phone save yet. Turn on Performance > Diagnostics and tap Save, or run: $0 save" >&2
    exit 1
  fi
  adb exec-out cat "$phone_dir/$name" >"$out/$name"
  # Its per-packet flight record, when Diagnostics made one (tools/diag/gpdiag.py flight reads it).
  local flight="${name%.txt}-flight.bin"
  if adb exec-out ls -1 "$phone_dir" 2>/dev/null | tr -d '\r' | grep -qx "$flight"; then
    adb exec-out cat "$phone_dir/$flight" >"$out/$flight"
    echo "phone flight record: $out/$flight" >&2
  fi
  echo "$out/$name"
}

newest_pc_near() {
  local at="$1" dir best="" bestd=""
  dir="$(pc_dir)"
  [ -d "$dir" ] || return 0
  for f in "$dir"/pc-*.txt; do
    [ -f "$f" ] || continue
    local ms d
    ms="$(saved_ms "$f")"; [ -n "$ms" ] || continue
    d=$(( ms > at ? ms - at : at - ms ))
    if [ "$d" -le 60000 ] && { [ -z "$bestd" ] || [ "$d" -lt "$bestd" ]; }; then best="$f"; bestd="$d"; fi
  done
  echo "$best"
}

case "${1:-}" in
  dump)
    adb shell dumpsys activity service "$svc" events "${2:-60}" | tr -d '\r'
    exit 0
    ;;
  merge)
    dest="$out/merged-$(date +%Y%m%d-%H%M%S).txt"
    merge "$2" "$3" "$dest"
    echo "merged: $dest"
    exit 0
    ;;
  save)
    adb shell dumpsys activity service "$svc" save | tr -d '\r' | grep -E '^(saved|diagnostics)' || true
    sleep 2 # the PC saves its side when the phone's next LINK_INFO arrives
    ;;
  "") ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac

phone="$(pull_newest_phone)"
echo "phone: $phone"
pc="$(newest_pc_near "$(saved_ms "$phone")")"
if [ -z "$pc" ]; then
  echo "No PC save within a minute of it in $(pc_dir) (the PC saves one only over Wi-Fi or USB)."
  exit 0
fi
echo "pc:    $pc"
records=""
phone_flight="$out/$(basename "$phone" .txt)-flight.bin"
[ -f "$phone_flight" ] && records="$phone_flight"
for extra in flight net; do
  f="${pc%.txt}-$extra.bin"
  if [ -f "$f" ]; then
    cp "$f" "$out/" && echo "pc $extra record: $out/$(basename "$f")"
    records="$records $out/$(basename "$f")"
  fi
done
[ -n "$records" ] && echo "per packet: python tools/diag/gpdiag.py flight$records"
dest="$out/merged-$(basename "$phone" .txt | sed 's/^phone-//').txt"
merge "$phone" "$pc" "$dest"
echo "merged: $dest"
