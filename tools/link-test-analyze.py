#!/usr/bin/env python3
"""Lines up a Link test's dropouts with what the phone's Wi-Fi was doing (tools/link-test.sh).

    python tools/link-test-analyze.py build/link-test/<time>

Reads input.log (the companion's), clock.txt, and when the phone was on adb logcat.txt and
wifi-status.txt. Prints: the dropouts during the test (lost packets, hold-ups, releases, phone gaps),
how regularly they come, and for each one the phone's Wi-Fi-related log lines from 1.5 s before to
0.5 s after, plus which log words show up near dropouts far more than at other times.
"""
import os
import re
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone

WIFI = re.compile(
    r"wifi|wlan|wpa|supplicant|scan|roam|cnss|wcn|qca|qcom|icnss|powersave|power save|psm|doze|idle|"
    r"bluetooth|btcoex|coex|nan|p2p|aware|softap|hotspot|location|gnss|NetworkMonitor|Connectivity|rssi|beacon|dfs|csa",
    re.I,
)
EVENT = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(-?[\d.]+)\s+(\S+(?: \S+)?)\s{2,}(.*)$")
EVENT_LOOSE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(-?[\d.]+)\s+(.*)$")
KINDS = ("lost", "late", "phone gap", "release", "catch-up", "link test", "session start", "session end")
BEFORE_S, AFTER_S = 1.5, 0.5


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return []


def clock(run):
    c = {}
    phone = []
    for line in read(os.path.join(run, "clock.txt")):
        parts = line.split()
        if len(parts) >= 2:
            c[parts[0]] = parts[1:]
        if parts[:1] == ["phone_epoch"] and len(parts) >= 5:
            try:
                phone.append((float(parts[1]), float(parts[3]), float(parts[4])))
            except ValueError:
                pass
    off = 0
    if "pc_utc_offset" in c:
        z = c["pc_utc_offset"][0]
        sign = -1 if z.startswith("-") else 1
        off = sign * (int(z[1:3]) * 3600 + int(z[3:5]) * 60)
    phone_shift = 0.0  # seconds to add to phone epoch to get PC epoch
    if phone:
        # The reading with the shortest adb round trip: the phone's time lies within half of it.
        p, a, b = min(phone, key=lambda r: r[2] - r[1])
        phone_shift = (a + b) / 2 - p
        print(f"Phone clock: read over adb to within {(b - a) / 2 * 1000:.1f} ms (shortest of {len(phone)} round trips)")
        if len(phone) > 7:
            # Read again after the test: how far the two clocks drifted apart meanwhile.
            first = min(phone[:7], key=lambda r: r[2] - r[1])
            last = min(phone[7:], key=lambda r: r[2] - r[1])
            drift = ((last[1] + last[2]) / 2 - last[0]) - ((first[1] + first[2]) / 2 - first[0])
            print(f"Phone clock drift during the test: {drift * 1000:+.1f} ms")
    return timezone(timedelta(seconds=off)), phone_shift


def events(run, tz):
    """Each event at its exact time: the line's second column (seconds on the engine's clock when it happened), not its
    wall-clock time, which is when the line was written (for a "late" line, the end of the hold-up). The two are tied by
    the smallest gap between them over the log: the line written soonest after its event."""
    rows = []
    for line in read(os.path.join(run, "input.log")):
        m = EVENT_LOOSE.match(line)
        if not m:
            continue
        rest = m.group(3)
        kind = next((k for k in KINDS if rest.startswith(k)), rest.split(" ", 1)[0])
        detail = rest[len(kind):].strip()
        wall = datetime.strptime(m.group(1)[:26], "%Y-%m-%d %H:%M:%S.%f").replace(tzinfo=tz).timestamp()
        rows.append((wall, float(m.group(2)), kind, detail))
    out = []
    # The engine clock restarts with the input process: tie each run of rising times on its own.
    start = 0
    for i in range(1, len(rows) + 1):
        if i == len(rows) or rows[i][1] < rows[i - 1][1] - 1:
            part = rows[start:i]
            if part:
                shift = min(w - r for w, r, _, _ in part)
                out.extend((r + shift, k, d) for _, r, k, d in part)
            start = i
    # Only the newest Link test.
    starts = [i for i, e in enumerate(out) if e[1] == "link test" and e[2].startswith("started")]
    if not starts:
        return out, None, None
    i0 = starts[-1]
    t0 = out[i0][0]
    t1 = next((e[0] for e in out[i0 + 1:] if e[1] == "link test" or e[1] == "session end"), out[-1][0])
    return [e for e in out[i0:] if e[0] <= t1], t0, t1


def logcat(run, shift):
    lines = []
    for line in read(os.path.join(run, "logcat.txt")):
        m = re.match(r"^\s*(\d{9,}\.\d+)\s+(.*)$", line)
        if m:
            lines.append((float(m.group(1)) + shift, m.group(2)))
    return lines


def words(text):
    return set(w.lower() for w in re.findall(r"[A-Za-z][A-Za-z_]{3,}", text))


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    run = sys.argv[1]
    tz, shift = clock(run)
    evs, t0, t1 = events(run, tz)
    if t0 is None:
        print("No 'link test started' line in input.log: the test didn't run (old companion, or no phone session).")
        return 1
    sec = [e for e in evs if e[1] == "sec"]
    drops = [e for e in evs if e[1] in ("lost", "late", "release", "phone gap")]
    print(f"Link test {datetime.fromtimestamp(t0, tz):%Y-%m-%d %H:%M:%S} for {t1 - t0:.0f} s")
    tot = Counter(e[1] for e in drops)
    print("dropout events: " + (", ".join(f"{k} {v}" for k, v in tot.most_common()) or "none"))
    lost_n = sum(int(m.group(1)) for e in evs if e[1] == "lost" for m in [re.match(r"(\d+)", e[2])] if m)
    print(f"packets lost (in runs of 3+): {lost_n}")
    for e in evs:
        if e[1] == "session end":
            print("session totals: " + e[2])

    # Group events within 1 s into one dropout.
    groups = []
    for e in drops:
        if groups and e[0] - groups[-1][-1][0] <= 1.0:
            groups[-1].append(e)
        else:
            groups.append([e])
    print(f"\n{len(groups)} dropouts")
    if len(groups) >= 3:
        gaps = [b[0][0] - a[0][0] for a, b in zip(groups, groups[1:])]
        gaps.sort()
        med = gaps[len(gaps) // 2]
        near = sum(1 for g in gaps if abs(g - med) <= 2)
        print(f"time between dropouts: median {med:.1f} s, {near} of {len(gaps)} within 2 s of it"
              + ("  -> regular, like a timer" if near >= 0.6 * len(gaps) else ""))
        secs = Counter(int(datetime.fromtimestamp(g[0][0], tz).second) for g in groups)
        print("second of the minute: " + ", ".join(f":{s:02d}x{n}" for s, n in sorted(secs.items())))

    cat = logcat(run, shift)
    wifi = [(t, l) for t, l in cat if WIFI.search(l)]
    if not cat:
        print("\nNo phone logcat (phone not on adb): only the PC side is known.")
    near_words, all_words = Counter(), Counter()
    for t, l in wifi:
        all_words.update(words(l))
    for g in groups:
        ts = g[0][0]
        print(f"\n== {datetime.fromtimestamp(ts, tz):%H:%M:%S.%f}"[:-3] + " " + "; ".join(f"{e[1]} {e[2]}" for e in g)[:300])
        hits = [(t, l) for t, l in wifi if ts - BEFORE_S <= t <= ts + AFTER_S]
        for t, l in hits[:40]:
            print(f"   {t - ts:+6.2f} s  {l[:220]}")
        if len(hits) > 40:
            print(f"   ... {len(hits) - 40} more")
        for _, l in hits:
            near_words.update(words(l))
    if groups and wifi:
        span = max(1.0, (t1 - t0))
        win = len(groups) * (BEFORE_S + AFTER_S)
        lift = []
        for w, n in near_words.items():
            if n < len(groups) * 0.5:
                continue
            rate_near = n / win
            rate_all = all_words[w] / span
            lift.append((rate_near / rate_all if rate_all else 99, n, w))
        lift.sort(reverse=True)
        print("\nwords much more common near dropouts (lift, count near, word):")
        for l, n, w in lift[:25]:
            print(f"   {l:6.1f}  {n:4d}  {w}")
    statuses = read(os.path.join(run, "wifi-status.txt"))
    if statuses:
        rssi = [int(m.group(1)) for s in statuses for m in [re.search(r"RSSI[:=]\s*(-\d+)", s, re.I)] if m]
        if rssi:
            print(f"\nphone RSSI during the test: min {min(rssi)} max {max(rssi)} dBm")
    print(f"\nsec lines: {len(sec)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
