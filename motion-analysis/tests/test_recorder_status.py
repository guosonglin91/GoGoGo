import unittest

from v2e.gate_l import evaluate_gate_l
from v2e.gate_r import evaluate_gate_r
from v2e.models import GateStatus


class RecorderStatusGateTest(unittest.TestCase):
    def test_gate_l_fails_on_producer_recorder_failure(self):
        producer = [
            {
                "session_id": "v2e",
                "provider": "gps",
                "publication_elapsed_ns": "2",
                "location_elapsed_ns": "1",
                "latitude": "34.0",
                "longitude": "108.0",
                "speed_mps": "3",
                "bearing_deg": "90",
                "accuracy_m": "5",
            },
            {
                "session_id": "v2e",
                "provider": "gps",
                "publication_elapsed_ns": "4",
                "location_elapsed_ns": "3",
                "latitude": "34.0",
                "longitude": "108.001",
                "speed_mps": "3",
                "bearing_deg": "90",
                "accuracy_m": "5",
            },
        ]
        consumer = [
            {
                "session_id": "v2e",
                "provider": "gps",
                "location_elapsed_ns": "1",
                "arrival_elapsed_ns": "3",
                "wall_time_ms": "1",
                "latitude": "34.0",
                "longitude": "108.0",
                "speed_mps": "3",
                "bearing_deg": "90",
                "accuracy_m": "5",
                "is_mock": "true",
            },
            {
                "session_id": "v2e",
                "provider": "gps",
                "location_elapsed_ns": "3",
                "arrival_elapsed_ns": "5",
                "wall_time_ms": "2",
                "latitude": "34.0",
                "longitude": "108.001",
                "speed_mps": "3",
                "bearing_deg": "90",
                "accuracy_m": "5",
                "is_mock": "true",
            },
        ]
        result = evaluate_gate_l(
            producer,
            consumer,
            {"recorder_status": "TRACE_WRITE_FAILURE"},
            {"finalization_status": "SUCCESS"},
        )
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("PRODUCER_RECORDER_FAILURE", result.error_codes)

    def test_gate_r_fails_on_consumer_recorder_failure(self):
        start = 1_000_000_000
        end = start + 60_000_000_000
        detector = [
            {
                "sensor_timestamp_ns": str(start + 1_000_000_000),
                "arrival_elapsed_ns": str(start + 1_001_000_000),
                "event_value": "1",
            }
        ]
        counter = [
            {
                "sensor_timestamp_ns": str(start - 1),
                "arrival_elapsed_ns": str(start - 1),
                "absolute_count": "10",
                "session_delta": "0",
                "discontinuity": "false",
            },
            {
                "sensor_timestamp_ns": str(end),
                "arrival_elapsed_ns": str(end),
                "absolute_count": "11",
                "session_delta": "1",
                "discontinuity": "false",
            },
        ]
        metadata = {
            "official_start_elapsed_ns": start,
            "official_end_elapsed_ns": end,
            "detector_name": "detector",
            "counter_name": "counter",
            "finalization_status": "TRACE_WRITE_FAILURE",
            "error_codes": [],
        }
        result = evaluate_gate_r(detector, counter, metadata, {"count": 1})
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("CONSUMER_RECORDER_FAILURE", result.error_codes)


if __name__ == "__main__":
    unittest.main()
