#!/usr/bin/env python3
"""GyroPhone Diagnostics analysis: one tool, kept in the repo, so every run is measured the same way.

    python tools/diag/gpdiag.py summary <pc-save.txt> [more saves...]
    python tools/diag/gpdiag.py compare <A.txt> [A2.txt ...] -- <B.txt> [B2.txt ...]
    python tools/diag/gpdiag.py ab <pc-save.txt> [more saves...]
    python tools/diag/gpdiag.py flight <pc-...-flight.bin> [phone-...-flight.bin] [pc-...-net.bin]

Reads PC Diagnostics saves (Documents\\GyroPhone\\Debug\\pc-*.txt, from either input program: both write the same
"sec" lines) and the per-packet flight records saved beside them (pc-*-flight.bin, and the phone's
phone-*-flight.bin from tools/debug-collect.sh).

How it counts (docs/spec/pc-companion.md, "Diagnostics", "Comparing runs"):
- Packet-weighted: rates are per 10,000 packets of the whole run, not the middle of per-second numbers, so a quiet
  second (50 packets) weighs less than a busy one (1000).
- Moving and still seconds apart: a second is "moving" when most of its packets moved the mouse or a stick.
- Ranges from minute blocks: slow hand-offs and late packets come in clusters, so the "likely" range resamples whole
  minutes (a bootstrap over minute blocks), not single events. Two runs whose ranges overlap show "no clear
  difference": one short run never confirms a cause.
- The setup lines of both runs are compared and their differences printed first.
- ab: a save made during the Performance page's A/B test is split by its "ab: phase" marks into A and B seconds
  (the first 10 s after each switch left out: the switch itself may hiccup) and compared like two runs.
Python 3.8+, standard library only.
"""
import random
import re
import struct
import sys
from collections import OrderedDict

AB_PHASE = re.compile(r"^ab: phase (\d+) ([AB]): (.*)$")
AB_START = re.compile(r"^ab: start: (.*)$")
AB_SETTLE_MS = 10_000
SEC = re.compile(r"^\s*(-?[\d.]+)\s+sec\s+(.*)$")
NUM = r"(-?[\d.]+)"
FIELDS = {
    "rx": re.compile(r"\brx " + NUM),
    "moving": re.compile(r"\bmoving " + NUM),
    "lost": re.compile(r"\blost " + NUM),
    "late": re.compile(r"\blate " + NUM),
    "maxlate": re.compile(r"\bmaxlate " + NUM),
    "lat": re.compile(r"\blat " + NUM + "/" + NUM),
    "floor": re.compile(r"\bfloor " + NUM),
    "rtt": re.compile(r"\brtt " + NUM + "/" + NUM),
    "over": re.compile(r"\bover " + NUM + "/" + NUM),
    "late3": re.compile(r"\blate3 " + NUM),
    "neglat": re.compile(r"\bneglat " + NUM),
    "handoff": re.compile(r"\bhandoff " + NUM),
    "slow": re.compile(r"\bslow " + NUM),
    "padonly": re.compile(r"\bpadonly " + NUM),
    "lockwait": re.compile(r"\blockwait " + NUM),
    "stall": re.compile(r"\bstall " + NUM + "/" + NUM),
    "heldup": re.compile(r"\bheldup air " + NUM + " pc " + NUM + " phone " + NUM),
}


def parse_sec(text):
    """One "sec" line's numbers as a dict (missing fields are absent)."""
    d = {}
    for k, rx in FIELDS.items():
        m = rx.search(text)
        if not m:
            continue
        g = [float(x) for x in m.groups()]
        if k == "lat":
            d["lat_p50"], d["lat_p99"] = g
        elif k == "rtt":
            d["rtt_min"], d["rtt_typ"] = g
        elif k == "over":
            d["over_p50"], d["over_p99"] = g
        elif k == "stall":
            d["stall_max"], d["stalls"] = g
        elif k == "heldup":
            d["air"], d["pc"], d["phone"] = g
        else:
            d[k] = g[0]
    return d


def read_save(path):
    """(setup dict, seconds) from a PC Diagnostics save: each second has "t" (seconds into the session) and its numbers."""
    setup = OrderedDict()
    secs = []
    ab = None  # (letter, phase, since wall ms, what)
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("# setup: "):
                setup = parse_setup(line[len("# setup: "):])
                continue
            if line.startswith("#"):
                continue
            parts = line.split("\t")
            text = parts[3] if len(parts) >= 4 else line
            wall = int(parts[0]) if len(parts) >= 4 and parts[0].lstrip("-").isdigit() else None
            if text.startswith("ab: "):
                mp = AB_PHASE.match(text)
                if mp:
                    ab = (mp.group(2), int(mp.group(1)), wall, mp.group(3))
                elif text.startswith("ab: stopped"):
                    ab = None
                elif AB_START.match(text):
                    setup["ab"] = AB_START.match(text).group(1)
                continue
            m = SEC.match(text)
            if not m:
                # Setup lines Diagnostics wrote during the run: the newest wins for the run's setup.
                if len(parts) >= 4 and parts[3].startswith("setup: ") and not setup:
                    setup = parse_setup(parts[3][len("setup: "):])
                continue
            d = parse_sec(m.group(2))
            d["t"] = float(m.group(1))
            if wall is not None:
                d["wall_ms"] = wall
            if ab is not None:
                d["ab"], d["ab_phase"], d["ab_what"] = ab[0], ab[1], ab[3]
                d["ab_settling"] = wall is not None and ab[2] is not None and wall - ab[2] < AB_SETTLE_MS
            secs.append(d)
    return setup, secs


SETUP_KEYS = sorted([
    "path", "pc adapter", "pc driver", "pc link", "pc wifi signal", "pc wifi rate", "pc wifi", "pc power source",
    "pc power plan", "pc power mode", "pc display", "pc suspects", "gp", "input program", "keys via", "overlays",
    "fps watch", "phone usb adapter", "phone wifi", "phone charging", "phone battery saver", "phone battery",
    "phone doze", "phone thermal", "phone temp",
], key=len, reverse=True)


def parse_setup(line):
    """ "key value · key value" -> ordered dict (the keys SetupSnapshot and PcSetupReader write; longest first)."""
    out = OrderedDict()
    for part in line.split(" · "):
        part = part.strip()
        if not part:
            continue
        for k in SETUP_KEYS:
            if part == k or part.startswith(k + " "):
                out[k] = part[len(k) + 1:]
                break
        else:
            k, _, v = part.partition(" ")
            out[k] = v
    return out


def is_moving(s):
    rx = s.get("rx", 0)
    return rx > 0 and s.get("moving", 0) * 2 >= rx


def totals(secs):
    """Packet-weighted numbers of a list of seconds."""
    rx = sum(s.get("rx", 0) for s in secs)
    t = {
        "seconds": len(secs),
        "packets": rx,
        "lost": sum(s.get("lost", 0) for s in secs),
        "late": sum(s.get("late", 0) for s in secs),
        "late3": sum(s.get("late3", 0) for s in secs),
        "slow": sum(s.get("slow", 0) for s in secs),
        "neglat": sum(s.get("neglat", 0) for s in secs),
        "stalls": sum(s.get("stalls", 0) for s in secs),
        "air": sum(s.get("air", 0) for s in secs),
        "pc": sum(s.get("pc", 0) for s in secs),
        "phone": sum(s.get("phone", 0) for s in secs),
    }
    t["handoff_max"] = max((s.get("handoff", 0) for s in secs), default=0)
    t["maxlate"] = max((s.get("maxlate", 0) for s in secs), default=0)
    floors = sorted(s["floor"] for s in secs if s.get("floor", 0) > 0)
    t["floor"] = floors[len(floors) // 2] if floors else None
    # Over the floor's slowest 1%, weighted by packets: per-second p99s pooled by their packet counts.
    t["over_p99"] = weighted_quantile([(s["over_p99"], s.get("rx", 0)) for s in secs if "over_p99" in s], 0.99)
    t["over_p50"] = weighted_quantile([(s["over_p50"], s.get("rx", 0)) for s in secs if "over_p50" in s], 0.5)
    t["lat_p50"] = weighted_quantile([(s["lat_p50"], s.get("rx", 0)) for s in secs if "lat_p50" in s], 0.5)
    return t


def weighted_quantile(pairs, q):
    pairs = [(v, w) for v, w in pairs if w > 0]
    if not pairs:
        return None
    pairs.sort()
    total = sum(w for _, w in pairs)
    acc = 0
    for v, w in pairs:
        acc += w
        if acc >= q * total:
            return v
    return pairs[-1][0]


def per10k(n, packets):
    return None if packets <= 0 else n * 10_000.0 / packets


def blocks(secs, seconds=60):
    """The seconds cut into minute blocks (by their session time)."""
    out = OrderedDict()
    for s in secs:
        out.setdefault(int(s["t"] // seconds), []).append(s)
    return list(out.values())


def likely_range(secs, key, seed=1, rounds=1000):
    """5th to 95th percentile of `key` per 10k packets, resampling whole minute blocks (events cluster)."""
    bl = [(sum(s.get(key, 0) for s in b), sum(s.get("rx", 0) for s in b)) for b in blocks(secs)]
    bl = [b for b in bl if b[1] > 0]
    if not bl:
        return None
    rnd = random.Random(seed)
    vals = []
    for _ in range(rounds):
        pick = [bl[rnd.randrange(len(bl))] for _ in bl]
        n = sum(p[0] for p in pick)
        pk = sum(p[1] for p in pick)
        vals.append(n * 10_000.0 / pk)
    vals.sort()
    return vals[int(0.05 * (rounds - 1))], vals[int(0.95 * (rounds - 1))]


def setup_diff(a, b):
    keys = list(a.keys()) + [k for k in b.keys() if k not in a]
    volatile = {"phone battery", "phone temp", "pc wifi signal", "pc wifi rate"}
    return [(k, a.get(k, "-"), b.get(k, "-")) for k in keys if k not in volatile and a.get(k) != b.get(k)]


def fmt(v, digits=2):
    return "-" if v is None else f"{v:.{digits}f}"


def load(paths):
    setup = OrderedDict()
    secs = []
    for p in paths:
        s, x = read_save(p)
        if s:
            setup = s
        secs.extend(x)
    return setup, secs


RATES = [("slow", "slow hand-offs (over 2 ms)"), ("late", "late packets (15 ms+ over the floor)"),
         ("late3", "packets 3-15 ms over the floor"), ("lost", "lost packets")]


def summary_lines(name, secs):
    lines = []
    for label, part in (("all", secs), ("moving", [s for s in secs if is_moving(s)]), ("still", [s for s in secs if not is_moving(s)])):
        t = totals(part)
        if t["seconds"] == 0:
            continue
        lines.append(f"{name} {label}: {t['seconds']} s, {int(t['packets'])} packets; floor {fmt(t['floor'])} ms; "
                     f"over the floor {fmt(t['over_p50'], 1)} / {fmt(t['over_p99'], 1)} ms; "
                     f"slow hand-offs {fmt(per10k(t['slow'], t['packets']), 1)} per 10k (slowest {fmt(t['handoff_max'], 1)} ms); "
                     f"late {int(t['late'])} (air {int(t['air'])} pc {int(t['pc'])} phone {int(t['phone'])}), late3 {int(t['late3'])}, lost {int(t['lost'])}"
                     + (f", {int(t['neglat'])} below-zero latencies" if t["neglat"] else ""))
    return lines


def compare_lines(a_paths, b_paths):
    sa, a = load(a_paths)
    sb, b = load(b_paths)
    return compare_secs(sa, a, sb, b)


def ab_lines(paths):
    setup, secs = load(paths)
    a = [x for x in secs if x.get("ab") == "A" and not x.get("ab_settling")]
    b = [x for x in secs if x.get("ab") == "B" and not x.get("ab_settling")]
    if not a or not b:
        return ["No A/B test in these saves (no \"ab: phase\" marks with seconds in both A and B)."]
    phases = sorted({x["ab_phase"] for x in a + b})
    what_a = next(x["ab_what"] for x in a)
    what_b = next(x["ab_what"] for x in b)
    out = [f"A/B test: A = {what_a}, B = {what_b}; {len(phases)} phases ({len(a)} s in A, {len(b)} s in B, "
           f"the first {AB_SETTLE_MS // 1000} s after each switch left out)"]
    if len(phases) < 4:
        out.append("  (fewer than 4 phases: A and B may differ only by what happened in the game at the time)")
    return out + compare_secs(OrderedDict(), a, OrderedDict(), b, same_session=True)


def compare_secs(sa, a, sb, b, same_session=False):
    out = []
    diff = [] if same_session else setup_diff(sa, sb)
    if diff:
        out.append("Setup differs:")
        for k, va, vb in diff:
            out.append(f"  {k}: A {va}  |  B {vb}")
    elif same_session:
        pass
    elif sa or sb:
        out.append("Setup: the same (apart from battery, temperature and Wi-Fi signal)")
    else:
        out.append("Setup: not in these saves (an older build)")
    out += summary_lines("A", a) + summary_lines("B", b)
    ta, tb = totals(a), totals(b)
    out.append("Rates per 10,000 packets, A against B, with the likely range from minute blocks:")
    for key, label in RATES:
        ra, rb = likely_range(a, key), likely_range(b, key)
        va, vb = per10k(ta[key], ta["packets"]), per10k(tb[key], tb["packets"])
        if va is None or vb is None:
            continue
        verdict = "no clear difference"
        if ra and rb and (ra[1] < rb[0] or rb[1] < ra[0]):
            verdict = "A lower" if ra[1] < rb[0] else "B lower"
        rng = lambda r: f"{r[0]:.1f}-{r[1]:.1f}" if r else "-"
        out.append(f"  {label}: A {va:.1f} (likely {rng(ra)}) | B {vb:.1f} (likely {rng(rb)}): {verdict}")
    if min(len(blocks(a)), len(blocks(b))) < 5:
        out.append("  (fewer than 5 minutes in a run: the ranges are rough; one short run never confirms a cause)")
    return out


# --- flight records ---------------------------------------------------------------------------------------------------

PC_FLAGS = {1: "moving", 2: "active", 4: "lost-before", 8: "pad-only", 16: "refused", 32: "handed-off"}


def read_flight(path):
    """(kind, header dict, entries). kind is "pc" (GPFLIGHT) or "phone" (GPFLPHON)."""
    with open(path, "rb") as f:
        data = f.read()
    magic = data[:8]
    version, size, count, now_us, unix_ms = struct.unpack_from("<iiqqq", data, 8)
    head = {"version": version, "size": size, "count": count, "now_us": now_us, "unix_ms": unix_ms}
    entries = []
    if magic == b"GPFLIGHT":
        for i in range(count):
            recv, phone, extra, hand, lock, drv, flags = struct.unpack_from("<qIiiiiI", data, 40 + i * size)
            entries.append({"recv_us": recv, "phone_us": phone, "extra_us": extra, "handoff_us": hand,
                            "lockwait_us": lock, "driver_us": drv, "flags": flags})
        return "pc", head, entries
    if magic == b"GPFLPHON":
        for i in range(count):
            at, seq, phone, age, send, wake, flags = struct.unpack_from("<qIIiiiI", data, 40 + i * size)
            entries.append({"at_us": at, "seq": seq, "phone_us": phone, "age_us": age, "send_us": send,
                            "wake_late_us": wake, "flags": flags})
        return "phone", head, entries
    if magic == b"GPNETARR":
        for i in range(count):
            at, size_b, port, _ = struct.unpack_from("<qiHH", data, 40 + i * size)
            entries.append({"at_us": at, "bytes": size_b, "port": port})
        return "net", head, entries
    raise ValueError(f"{path}: not a GyroPhone flight record")


def pair_net(pc, net, window_us=50_000):
    """For each PC packet, the time it waited in the PC: from Windows receiving it (the newest arrival not yet used,
    at or before the input program's receive stamp, within 50 ms) to the input program taking it; None when unpaired.
    Both clocks are QueryPerformanceCounter microseconds."""
    import bisect
    at = [x["at_us"] for x in net]
    used = set()
    waits = []
    for x in pc:
        i = bisect.bisect_right(at, x["recv_us"]) - 1
        while i >= 0 and i in used and x["recv_us"] - at[i] <= window_us:
            i -= 1
        if i < 0 or x["recv_us"] - at[i] > window_us:
            waits.append(None)
            continue
        used.add(i)
        waits.append(x["recv_us"] - at[i])
    return waits


def pct(values, q):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(q * len(v)))]


def flight_lines(paths):
    out = []
    pc = phone = net = None
    for p in paths:
        kind, head, e = read_flight(p)
        if kind == "pc":
            pc = e
        elif kind == "net":
            net = e
        else:
            phone = e
    if pc is not None:
        extra = [x["extra_us"] / 1000 for x in pc]
        hand = [x["handoff_us"] / 1000 for x in pc if x["handoff_us"] >= 0]
        merged = sum(1 for x in pc if x["handoff_us"] < 0)
        # Bunching: packets the phone sent at least 0.8 ms apart that arrived within 0.2 ms of the one before.
        bunched = 0
        for a, b in zip(pc, pc[1:]):
            sent = (b["phone_us"] - a["phone_us"]) & 0xFFFFFFFF
            if 800 <= sent < 1_000_000 and 0 <= b["recv_us"] - a["recv_us"] < 200:
                bunched += 1
        out.append(f"PC: {len(pc)} packets; over the floor p50 {fmt(pct(extra, .5))} p99 {fmt(pct(extra, .99))} "
                   f"p99.9 {fmt(pct(extra, .999))} max {fmt(max(extra) if extra else None)} ms")
        out.append(f"PC hand-offs: p50 {fmt(pct(hand, .5))} p99 {fmt(pct(hand, .99))} max {fmt(max(hand) if hand else None)} ms; "
                   f"{merged} packets merged into the next hand-off; {bunched} arrived bunched")
    if phone is not None:
        age = [x["age_us"] / 1000 for x in phone if x["age_us"] >= 0]
        send = [x["send_us"] / 1000 for x in phone]
        wake = [x["wake_late_us"] / 1000 for x in phone]
        failed = sum(1 for x in phone if x["flags"] & 4)
        out.append(f"Phone: {len(phone)} STATEs; gyro sample to send p50 {fmt(pct(age, .5))} p99 {fmt(pct(age, .99))} ms; "
                   f"send p99 {fmt(pct(send, .99))} max {fmt(max(send) if send else None)} ms; woke late p99 {fmt(pct(wake, .99))} ms; failed {failed}")
    if pc is not None and phone is not None:
        by_time = {x["phone_us"]: x for x in phone}
        paired = [(by_time[x["phone_us"]], x) for x in pc if x["phone_us"] in by_time]
        out.append(f"Paired by the phone time they carry: {len(paired)} of {len(pc)} PC packets")
        late = [(ph, p) for ph, p in paired if p["extra_us"] > 3000]
        if late:
            slow_send = sum(1 for ph, _ in late if ph["send_us"] > 2000 or ph["wake_late_us"] > 2000)
            out.append(f"  {len(late)} came 3 ms+ over the floor: {slow_send} of them left the phone late (slow send or late wake), "
                       f"{len(late) - slow_send} left on time (held up after the phone)")
    if pc is not None and net is not None:
        waits = pair_net(pc, net)
        got = [w for w in waits if w is not None]
        out.append(f"Network arrivals: {len(net)} datagrams; {len(got)} of {len(pc)} PC packets paired")
        if got:
            ms = [w / 1000 for w in got]
            out.append(f"  waited in the PC after Windows got it: p50 {fmt(pct(ms, .5))} p99 {fmt(pct(ms, .99))} "
                       f"max {fmt(max(ms))} ms; {sum(1 for w in got if w >= 1000)} waited 1 ms+")
            late = [(x, w) for x, w in zip(pc, waits) if w is not None and x["extra_us"] > 3000]
            if late:
                inside = sum(1 for x, w in late if w >= x["extra_us"] / 2)
                out.append(f"  {len(late)} came 3 ms+ over the floor: {inside} mostly waited in the PC, "
                           f"{len(late) - inside} mostly came late to the PC")
    return out


def main(argv):
    if len(argv) < 2 or argv[0] not in ("summary", "compare", "ab", "flight"):
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "summary":
        setup, secs = load(rest)
        if setup:
            print("setup: " + " · ".join(f"{k} {v}" for k, v in setup.items()))
        print("\n".join(summary_lines("run", secs)))
    elif cmd == "compare":
        if "--" not in rest:
            print("compare needs: A files -- B files")
            return 2
        i = rest.index("--")
        print("\n".join(compare_lines(rest[:i], rest[i + 1:])))
    elif cmd == "ab":
        print("\n".join(ab_lines(rest)))
    else:
        print("\n".join(flight_lines(rest)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
