#!/usr/bin/env bash
# Switches GP's Diagnostics (Performance tab) without Olex touching anything (Git Bash on the PC).
# Phone and PC switch together, so either side's on/off does both; a Save on either side saves both.
#   tools/gp-log.sh phone on|off      Diagnostics on or off (both sides)
#   tools/gp-log.sh phone save        save the kept minutes on the phone and the PC
#   tools/gp-log.sh phone keep N      keep the last N minutes on the phone (1-30)
#   tools/gp-log.sh phone status      what is on, and the phone's newest events
#   tools/gp-log.sh phone workspace [name]   the active (or named) workspace's settings, as an export file
#   tools/gp-log.sh phone everything  every workspace, the Defaults, presets and library, as an export file
#   tools/gp-log.sh pc on|off         the same switch, from the PC
#   tools/gp-log.sh pc save           save both sides (the PC's becomes a record in Performance › Results)
#   tools/gp-log.sh pc keep N         keep the last N minutes on the PC (1-30)
# The phone goes over adb (dumpsys); the PC over a loopback LOG_CTL packet the companion takes only from this PC.
set -euo pipefail
export MSYS_NO_PATHCONV=1
usage() { sed -n '2,12p' "$0" >&2; exit 2; }
side="${1:-}"; shift || true
minutes() { [[ "${1:-}" =~ ^[0-9]+$ ]] && (( $1 >= 1 && $1 <= 30 )) || usage; }

case "$side" in
  phone)
    case "${1:-}" in
      on|off|save) arg="$1" ;;
      keep) minutes "${2:-}"; arg="keep $2" ;;
      status) arg="" ;;
      workspace|everything)
        # Printed whole (not cut at the saves list).
        adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService "$@" | sed '1{/^SERVICE /d}'
        exit 0 ;;
      *) usage ;;
    esac
    # shellcheck disable=SC2086
    adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService $arg | sed -n '1,/^--- saves/p' | grep -v '^--- saves'
    ;;
  pc)
    # LOG_CTL: 'G' 'P', version 1, type 0x7D, sid 0, seq 0, u8 what (1 Diagnostics, 2 Save, 3 Keep), u8 value, 2 reserved bytes.
    case "${1:-}" in
      on) what=1; value=1 ;;
      off) what=1; value=0 ;;
      save) what=2; value=0 ;;
      keep) minutes "${2:-}"; what=3; value="$2" ;;
      *) usage ;;
    esac
    hex=$(printf '\\x%02x' "$value")
    if ! (printf "GP\\x01\\x7d\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x0${what}${hex}\\x00\\x00" >/dev/udp/127.0.0.1/47800) 2>/dev/null; then
      powershell.exe -NoProfile -Command "\$u = New-Object Net.Sockets.UdpClient; \$b = [byte[]](0x47,0x50,1,0x7d,0,0,0,0,0,0,0,0,$what,$value,0,0); [void]\$u.Send(\$b, \$b.Length, '127.0.0.1', 47800); \$u.Close()" >/dev/null
    fi
    echo "pc: diagnostics $* (sent; the companion notes it in companion.log)"
    ;;
  *) usage ;;
esac
