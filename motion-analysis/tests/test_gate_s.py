import unittest

from v2e.gate_s import evaluate_gate_s
from v2e.models import GateStatus


class GateSTest(unittest.TestCase):
    def setUp(self):
        self.meta = {
            "recorder_status": "SUCCESS",
            "cadence_min_spm": 100.0,
            "cadence_max_spm": 190.0,
            "cadence_intercept_spm": 100.0,
            "cadence_slope_spm_per_mps": 15.0,
            "jitter_fraction": 0.02,
        }

    def row(self, index, time_ns):
        return {
            "session_id": "v2e_test",
            "step_index": str(index),
            "step_elapsed_ns": str(time_ns),
            "speed_mps": "4.0",
            "target_cadence_spm": "160.0",
            "instantaneous_cadence_spm": "160.0",
            "interval_ns": "375000000",
        }

    def test_consistent_trace_passes(self):
        rows = [
            self.row(1, 375_000_000),
            self.row(2, 750_000_000),
            self.row(3, 1_125_000_000),
        ]
        result = evaluate_gate_s(rows, self.meta)
        self.assertEqual(GateStatus.PASS, result.status)

    def test_duplicate_step_index_fails(self):
        rows = [
            self.row(1, 375_000_000),
            self.row(1, 750_000_000),
        ]
        result = evaluate_gate_s(rows, self.meta)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("STEP_INDEX_NON_MONOTONIC", result.error_codes)

    def test_timestamp_delta_must_match_recorded_interval(self):
        rows = [
            self.row(1, 375_000_000),
            self.row(2, 760_000_000),
        ]
        result = evaluate_gate_s(rows, self.meta)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn(
            "STEP_TIME_INTERVAL_MISMATCH",
            result.error_codes,
        )

    def test_small_csv_rounding_error_in_speed_mapping_passes(self):
        row = self.row(1, 315_789_474)
        row["speed_mps"] = "5.99999999999"
        row["target_cadence_spm"] = "189.999999999"
        row["interval_ns"] = "315789474"
        row["instantaneous_cadence_spm"] = str(
            60_000_000_000.0 / 315_789_474
        )
        result = evaluate_gate_s([row], self.meta)
        self.assertEqual(GateStatus.PASS, result.status)

    def test_excess_jitter_fails(self):
        row = self.row(1, 400_000_000)
        row["interval_ns"] = "400000000"
        row["instantaneous_cadence_spm"] = "150.0"
        result = evaluate_gate_s([row], self.meta)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("JITTER_BOUND_EXCEEDED", result.error_codes)


if __name__ == "__main__":
    unittest.main()
