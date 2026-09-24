import unittest

from v2e.gate_r import (
    classify_step_error,
    detector_counter_agree,
    evaluate_gate_r,
)
from v2e.models import GateStatus


class GateRTest(unittest.TestCase):
    def test_ground_truth_boundaries(self):
        self.assertEqual(GateStatus.PASS, classify_step_error(97, 100))
        self.assertEqual(GateStatus.WARN, classify_step_error(96, 100))
        self.assertEqual(GateStatus.WARN, classify_step_error(95, 100))
        self.assertEqual(GateStatus.FAIL, classify_step_error(94, 100))

    def test_detector_counter_tolerance(self):
        self.assertTrue(detector_counter_agree(20, 18))
        self.assertFalse(detector_counter_agree(20, 17))
        self.assertTrue(detector_counter_agree(200, 194))
        self.assertFalse(detector_counter_agree(200, 193))

    def test_valid_sixty_second_session_passes(self):
        start = 1_000_000_000
        end = start + 60_000_000_000
        detector = []
        for i in range(100):
            timestamp = start + 100_000_000 + i * 590_000_000
            detector.append(
                {
                    "session_id": "v2e_test",
                    "sensor_timestamp_ns": str(timestamp),
                    "arrival_elapsed_ns": str(timestamp + 5_000_000),
                    "event_value": "1.0",
                }
            )

        counter = [
            {
                "session_id": "v2e_test",
                "sensor_timestamp_ns": str(start - 100_000_000),
                "arrival_elapsed_ns": str(start - 90_000_000),
                "absolute_count": "500",
                "session_delta": "0",
                "discontinuity": "false",
            },
            {
                "session_id": "v2e_test",
                "sensor_timestamp_ns": str(end),
                "arrival_elapsed_ns": str(end + 10_000_000),
                "absolute_count": "600",
                "session_delta": "100",
                "discontinuity": "false",
            },
        ]
        metadata = {
            "official_start_elapsed_ns": start,
            "official_end_elapsed_ns": end,
            "detector_name": "detector",
            "counter_name": "counter",
            "error_codes": [],
        }
        result = evaluate_gate_r(
            detector,
            counter,
            metadata,
            {"count": 100},
        )
        self.assertEqual(GateStatus.PASS, result.status)
        self.assertEqual(100, result.metrics["detector_steps"])
        self.assertEqual(100, result.metrics["counter_delta"])

    def test_short_session_fails(self):
        metadata = {
            "official_start_elapsed_ns": 0,
            "official_end_elapsed_ns": 30_000_000_000,
            "detector_name": "detector",
            "counter_name": "counter",
            "error_codes": [],
        }
        detector = [
            {
                "session_id": "v2e_test",
                "sensor_timestamp_ns": "1000000000",
                "arrival_elapsed_ns": "1000000000",
                "event_value": "1",
            }
        ]
        counter = [
            {
                "session_id": "v2e_test",
                "sensor_timestamp_ns": "0",
                "arrival_elapsed_ns": "0",
                "absolute_count": "10",
                "session_delta": "0",
                "discontinuity": "false",
            },
            {
                "session_id": "v2e_test",
                "sensor_timestamp_ns": "30000000000",
                "arrival_elapsed_ns": "30000000000",
                "absolute_count": "11",
                "session_delta": "1",
                "discontinuity": "false",
            },
        ]
        result = evaluate_gate_r(detector, counter, metadata, {"count": 1})
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("SESSION_TOO_SHORT", result.error_codes)


if __name__ == "__main__":
    unittest.main()
