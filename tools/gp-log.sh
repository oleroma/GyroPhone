#!/usr/bin/env bash
# Switches GP's Performance-tab logs on and off without Olex touching anything (Git Bash on the PC).
#   tools/gp-log.sh phone quiet          debug tools on without the panel (catches misfires while playing)
#   tools/gp-log.sh phone on|off         debug tools with the panel / off (both sides stop logging)
#   tools/gp-log.sh phone record         save the last 3 minutes on the phone and the PC
#   tools/gp-log.sh phone ratchet on|off Ratchet timing (logcat GPRatchet)
#   tools/gp-log.sh phone drawlog on|off Display timing log (logcat GPDraw)
#   tools/gp-log.sh phone status         what is on
#   tools/gp-log.sh pc input on|off      Record input log (input.log)
#   tools/gp-log.sh pc overlay on|off    Overlay timing log (companion.log)
# The phone goes over adb (dumpsys); the PC over a loopback LOG_CTL packet the companion takes only from this PC.
set -euo pipefail
export MSYS_NO_PATHCONV=1
usage() { sed -n '2,10p' "$0" >&2; exit 2; }
side="${1:-}"; shift || true

case "$side" in
  phone)
    case "${1:-}" in
      quiet|on|off|record) arg="$1" ;;
      ratchet|drawlog) [[ "${2:-}" == on || "${2:-}" == off ]] || usage; arg="$1 $2" ;;
      status) arg="" ;;
      *) usage ;;
    esac
    # shellcheck disable=SC2086
    adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService $arg | sed -n '1,/^--- last/p' | grep -v '^--- last'
    ;;
  pc)
    case "${1:-}" in input) log=1 ;; overlay) log=2 ;; *) usage ;; esac
    case "${2:-}" in on) on=1 ;; off) on=0 ;; *) usage ;; esac
    # LOG_CTL: 'G' 'P', version 1, type 0x7D, sid 0, seq 0, u8 log, u8 on, 2 reserved bytes.
    if ! (printf "GP\\x01\\x7d\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x0${log}\\x0${on}\\x00\\x00" >/dev/udp/127.0.0.1/47800) 2>/dev/null; then
      powershell.exe -NoProfile -Command "\$u = New-Object Net.Sockets.UdpClient; \$b = [byte[]](0x47,0x50,1,0x7d,0,0,0,0,0,0,0,0,$log,$on,0,0); [void]\$u.Send(\$b, \$b.Length, '127.0.0.1', 47800); \$u.Close()" >/dev/null
    fi
    echo "pc: $1 log $2 (sent; the companion notes it in companion.log)"
    ;;
  *) usage ;;
esac
