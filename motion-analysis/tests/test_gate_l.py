import unittest

from v2e.gate_l import evaluate_gate_l
from v2e.models import GateStatus


def producer(provider, elapsed, lat, lon):
    return {
        "session_id": "v2e_test",
        "provider": provider,
        "publication_elapsed_ns": str(elapsed + 1_000_000),
        "location_elapsed_ns": str(elapsed),
        "latitude": str(lat),
        "longitude": str(lon),
        "speed_mps": "3.0",
        "bearing_deg": "90.0",
        "accuracy_m": "5.0",
    }


def consumer(provider, elapsed, lat, lon, mock="true"):
    return {
        "session_id": "v2e_test",
        "provider": provider,
        "location_elapsed_ns": str(elapsed),
        "arrival_elapsed_ns": str(elapsed + 2_000_000),
        "wall_time_ms": "1",
        "latitude": str(lat),
        "longitude": str(lon),
        "speed_mps": "3.0",
        "bearing_deg": "90.0",
        "accuracy_m": "5.0",
        "is_mock": mock,
    }


class GateLTest(unittest.TestCase):
    def test_closed_loop_motion_passes(self):
        points = [
            (1_000_000_000, 34.0, 108.0),
            (2_000_000_000, 34.0, 108.001),
            (3_000_000_000, 34.0, 108.0),
        ]
        p = [producer("gps", *row) for row in points]
        c = [consumer("gps", *row) for row in points]

        result = evaluate_gate_l(p, c)
        self.assertEqual(GateStatus.PASS, result.status)
        self.assertGreater(result.metrics["producer_path_distance_m"], 1.0)
        self.assertEqual(3, result.metrics["exact_elapsed_time_matches"])

    def test_no_mock_observation_fails(self):
        p = [
            producer("gps", 1, 34.0, 108.0),
            producer("gps", 2, 34.0, 108.001),
        ]
        c = [
            consumer("gps", 1, 34.0, 108.0, "false"),
            consumer("gps", 2, 34.0, 108.001, "false"),
        ]
        result = evaluate_gate_l(p, c)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("LOCATION_NOT_MOCK_OBSERVED", result.error_codes)


if __name__ == "__main__":
    unittest.main()
