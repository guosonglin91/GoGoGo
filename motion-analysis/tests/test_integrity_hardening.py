import unittest

from v2e.gate_l import evaluate_gate_l
from v2e.gate_r import evaluate_gate_r
from v2e.gate_s import evaluate_gate_s
from v2e.models import GateStatus


class AnalyzerIntegrityHardeningTest(unittest.TestCase):
    def test_gate_l_checks_monotonicity_per_provider(self):
        producer = [
            self.p("gps", 100, 101, 108.0),
            self.p("network", 90, 102, 108.0),
            self.p("gps", 200, 201, 108.001),
            self.p("network", 190, 202, 108.001),
        ]
        consumer = [
            self.c("gps", 100, 108.0),
            self.c("network", 90, 108.0),
            self.c("gps", 200, 108.001),
            self.c("network", 190, 108.001),
        ]
        result = evaluate_gate_l(producer, consumer)
        self.assertEqual(GateStatus.PASS, result.status)

    def test_gate_l_does_not_treat_cross_provider_offset_as_motion(self):
        producer = [
            self.p("gps", 100, 101, 108.0),
            self.p("network", 110, 111, 109.0),
            self.p("gps", 200, 201, 108.0),
            self.p("network", 210, 211, 109.0),
        ]
        consumer = [
            self.c("gps", 100, 108.0),
            self.c("network", 110, 109.0),
            self.c("gps", 200, 108.0),
            self.c("network", 210, 109.0),
        ]

        result = evaluate_gate_l(producer, consumer)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("PRODUCER_LOCATION_NO_MOTION", result.error_codes)
        self.assertIn("LOCATION_NO_MOTION", result.error_codes)

    def test_gate_l_rejects_invalid_coordinates(self):
        producer = [
            self.p("gps", 100, 101, 108.0),
            self.p("gps", 200, 201, 108.001),
        ]
        consumer = [
            self.c("gps", 100, 108.0),
            self.c("gps", 200, 108.001),
        ]
        consumer[0]["latitude"] = "999"
        result = evaluate_gate_l(producer, consumer)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("LOCATION_VALUE_INVALID", result.error_codes)

    def test_gate_s_fails_when_producer_reports_model_error(self):
        rows = [{
            "step_index": "1",
            "step_elapsed_ns": "375000000",
            "speed_mps": "4",
            "target_cadence_spm": "160",
            "instantaneous_cadence_spm": "160",
            "interval_ns": "375000000",
        }]
        meta = {
            "recorder_status": "SUCCESS",
            "error_codes": ["BACKLOG_CAPPED"],
            "cadence_min_spm": 100,
            "cadence_max_spm": 190,
            "cadence_intercept_spm": 100,
            "cadence_slope_spm_per_mps": 15,
            "jitter_fraction": 0.02,
        }
        result = evaluate_gate_s(rows, meta)
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("BACKLOG_CAPPED", result.error_codes)

    def test_gate_r_detects_raw_counter_reordering(self):
        start = 1_000
        end = start + 60_000_000_000
        detector = [{
            "sensor_timestamp_ns": str(start + 1),
            "arrival_elapsed_ns": str(start + 2),
            "event_value": "1",
        }]
        counter = [
            {
                "sensor_timestamp_ns": str(start - 1),
                "arrival_elapsed_ns": str(start),
                "absolute_count": "5",
                "session_delta": "0",
                "discontinuity": "false",
            },
            {
                "sensor_timestamp_ns": str(end),
                "arrival_elapsed_ns": str(end),
                "absolute_count": "6",
                "session_delta": "1",
                "discontinuity": "false",
            },
            {
                "sensor_timestamp_ns": str(end - 1),
                "arrival_elapsed_ns": str(end),
                "absolute_count": "6",
                "session_delta": "1",
                "discontinuity": "false",
            },
        ]
        meta = {
            "official_start_elapsed_ns": start,
            "official_end_elapsed_ns": end,
            "detector_name": "d",
            "counter_name": "c",
            "finalization_status": "SUCCESS",
            "error_codes": [],
        }
        result = evaluate_gate_r(detector, counter, meta, {"count": 1})
        self.assertEqual(GateStatus.FAIL, result.status)
        self.assertIn("COUNTER_TIME_NON_MONOTONIC", result.error_codes)

    @staticmethod
    def p(provider, elapsed, publication, lon):
        return {
            "provider": provider,
            "location_elapsed_ns": str(elapsed),
            "publication_elapsed_ns": str(publication),
            "latitude": "34.0",
            "longitude": str(lon),
            "speed_mps": "3",
            "bearing_deg": "90",
            "accuracy_m": "5",
        }

    @staticmethod
    def c(provider, elapsed, lon):
        return {
            "provider": provider,
            "location_elapsed_ns": str(elapsed),
            "arrival_elapsed_ns": str(elapsed + 5),
            "latitude": "34.0",
            "longitude": str(lon),
            "speed_mps": "3",
            "bearing_deg": "90",
            "accuracy_m": "5",
            "is_mock": "true",
        }


if __name__ == "__main__":
    unittest.main()
