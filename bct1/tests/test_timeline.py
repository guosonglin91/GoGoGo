import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from bct1.timeline import build_timeline, samples_in_window


class TimelineTest(unittest.TestCase):
    def test_165_spm_has_exact_official_window_count(self):
        samples = build_timeline(165, 90, 10_000)
        official = samples_in_window(samples, 30_000_000_000, 90_000_000_000)
        self.assertEqual(165, len(official))

    def test_supported_matrix_has_expected_60_second_counts(self):
        for cadence in (150, 165, 180):
            with self.subTest(cadence=cadence):
                samples = build_timeline(cadence, 90, 10_000)
                official = samples_in_window(samples, 30_000_000_000, 90_000_000_000)
                self.assertEqual(cadence, len(official))

    def test_detector_and_counter_semantics(self):
        samples = build_timeline(165, 90, 10_000)
        self.assertTrue(all(sample.detector_value == 1.0 for sample in samples))
        self.assertEqual([10_001, 10_002, 10_003],
                         [sample.counter_value for sample in samples[:3]])
        self.assertTrue(all(
            b.offset_ns > a.offset_ns for a, b in zip(samples, samples[1:])
        ))

    def test_invalid_arguments_are_rejected(self):
        for args in ((0, 90, 10_000), (-1, 90, 10_000),
                     (165, 0, 10_000), (165, -1, 10_000),
                     (165, 90, -1)):
            with self.subTest(args=args):
                with self.assertRaises(ValueError):
                    build_timeline(*args)

    def test_cli_rejects_unsupported_formal_cadence(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(
                [
                    sys.executable,
                    "bct1/generate_timeline.py",
                    "--cadence", "170",
                    "--duration", "90",
                    "--counter-baseline", "10000",
                    "--output-dir", temp,
                ],
                text=True,
                capture_output=True,
                check=False,
            )
        self.assertNotEqual(0, result.returncode)

    def test_cli_writes_official_count(self):
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(
                [
                    sys.executable,
                    "bct1/generate_timeline.py",
                    "--cadence", "165",
                    "--duration", "90",
                    "--counter-baseline", "10000",
                    "--output-dir", temp,
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            summary = json.loads(
                (Path(temp) / "synthetic_step_timeline.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(165, summary["official_window_count"])


if __name__ == "__main__":
    unittest.main()
