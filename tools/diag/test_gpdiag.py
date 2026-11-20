"""Tests for gpdiag.py on small made-up saves and flight records: python -m unittest discover tools/diag"""
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gpdiag  # noqa: E402

SEC_NEW = ("rx 1000 moving 900 lost 0 late 0 maxlate 0.4 lat 1.4/2.0 floor 0.75 rtt 1.50/1.80 over 0.1/0.6 late3 2 "
           "jitter 0.3 inj 1000 px 300 maxstep 4 handoff 2.4 slow 3 stall 0.6/0 heldup air 0 pc 0 phone 0")
SEC_OLD = "rx 50 moving 0 lost 1 late 0 maxlate 0.0 lat 1.2/1.3 jitter 0.1 inj 50 px 0 maxstep 0 handoff 0.3"


def save(lines, setup=None):
    f = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
    f.write("# GyroPhone diagnostics, side pc\n")
    if setup:
        f.write("# setup: " + setup + "\n")
    for i, text in enumerate(lines):
        f.write(f"{1_700_000_000_000 + i * 1000}\tpc\tlink\t{i + 1:10.3f} sec           {text}\n")
    f.close()
    return f.name


class GpDiagTest(unittest.TestCase):
    def test_sec_lines_of_both_builds_are_read(self):
        d = gpdiag.parse_sec(SEC_NEW)
        self.assertEqual((d["rx"], d["floor"], d["over_p99"], d["late3"], d["slow"], d["stall_max"]), (1000, 0.75, 0.6, 2, 3, 0.6))
        self.assertEqual((d["rtt_min"], d["rtt_typ"]), (1.5, 1.8))
        old = gpdiag.parse_sec(SEC_OLD)
        self.assertNotIn("floor", old)
        self.assertEqual((old["rx"], old["lost"], old["late"]), (50, 1, 0))

    def test_setup_is_read_and_compared(self):
        s = gpdiag.parse_setup("path Wired (USB) · pc suspects none · phone usb adapter Realtek RTL8156 (2.5 Gb) 0bda:8156 · "
                               "phone battery 80% · phone battery saver off · phone charging yes")
        self.assertEqual(s["pc suspects"], "none")
        self.assertEqual(s["phone usb adapter"], "Realtek RTL8156 (2.5 Gb) 0bda:8156")
        self.assertEqual(s["phone battery saver"], "off")
        self.assertEqual(s["phone charging"], "yes")
        t = dict(s, **{"phone battery": "60%", "pc suspects": "OBS"})
        self.assertEqual(gpdiag.setup_diff(s, t), [("pc suspects", "none", "OBS")])

    def test_rates_are_packet_weighted_and_split(self):
        secs = [gpdiag.parse_sec(SEC_NEW), gpdiag.parse_sec(SEC_OLD)]
        t = gpdiag.totals(secs)
        self.assertEqual(t["packets"], 1050)
        self.assertAlmostEqual(gpdiag.per10k(t["slow"], t["packets"]), 3 * 10_000 / 1050)
        self.assertTrue(gpdiag.is_moving(secs[0]))
        self.assertFalse(gpdiag.is_moving(secs[1]))

    def test_compare_says_no_clear_difference_for_the_same_run(self):
        a = save([SEC_NEW] * 300, "path Router · pc adapter X")
        b = save([SEC_NEW] * 300, "path Router · pc adapter Y")
        try:
            out = gpdiag.compare_lines([a], [b])
            self.assertIn("  pc adapter: A X  |  B Y", out)
            self.assertTrue(any("slow hand-offs" in l and "no clear difference" in l for l in out), out)
        finally:
            os.unlink(a)
            os.unlink(b)

    def test_compare_finds_a_clear_difference(self):
        quiet = SEC_NEW.replace("slow 3", "slow 0")
        a = save([quiet] * 600)
        b = save([SEC_NEW.replace("slow 3", "slow 30")] * 600)
        try:
            out = gpdiag.compare_lines([a], [b])
            self.assertTrue(any("slow hand-offs" in l and "A lower" in l for l in out), out)
        finally:
            os.unlink(a)
            os.unlink(b)

    def test_flight_records_are_read_and_paired(self):
        d = tempfile.mkdtemp()
        pc = os.path.join(d, "pc-flight.bin")
        ph = os.path.join(d, "phone-flight.bin")
        with open(pc, "wb") as f:
            f.write(b"GPFLIGHT" + struct.pack("<iiqqq", 1, 32, 3, 0, 0))
            for i, extra in enumerate((100, 4000, 200)):
                f.write(struct.pack("<qIiiiiI", 10_000 + i * 1000, 5_000 + i * 1000, extra, 300, 0, 100, 33))
        with open(ph, "wb") as f:
            f.write(b"GPFLPHON" + struct.pack("<iiqqq", 1, 32, 3, 0, 0))
            for i in range(3):
                f.write(struct.pack("<qIIiiiI", 9_000 + i * 1000, 7 + i, 5_000 + i * 1000, 1500, 50, 3000 if i == 1 else 0, 8))
        out = gpdiag.flight_lines([pc, ph])
        self.assertTrue(out[0].startswith("PC: 3 packets"), out)
        self.assertIn("Paired by the phone time they carry: 3 of 3 PC packets", out)
        self.assertTrue(any("1 came 3 ms+ over the floor: 1 of them left the phone late" in l for l in out), out)


if __name__ == "__main__":
    unittest.main()
