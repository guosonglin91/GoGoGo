import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from v2e.io import load_evidence
from v2e.models import EvidenceError


PRODUCER_FILES = {
    "producer_location.csv":
        "session_id,provider,publication_elapsed_ns,location_elapsed_ns,latitude,longitude,speed_mps,bearing_deg,accuracy_m\n"
        "v2e_io,gps,2,1,34.0,108.0,3.0,90.0,5.0\n",
    "synthetic_motion.csv":
        "session_id,step_index,step_elapsed_ns,speed_mps,target_cadence_spm,instantaneous_cadence_spm,interval_ns\n"
        "v2e_io,1,10,4.0,160.0,160.0,375000000\n",
}
CONSUMER_FILES = {
    "location_events.csv":
        "session_id,provider,location_elapsed_ns,arrival_elapsed_ns,wall_time_ms,latitude,longitude,speed_mps,bearing_deg,accuracy_m,is_mock\n"
        "v2e_io,gps,1,3,4,34.0,108.0,3.0,90.0,5.0,true\n",
    "step_detector_events.csv":
        "session_id,sensor_timestamp_ns,arrival_elapsed_ns,event_value\n",
    "step_counter_events.csv":
        "session_id,sensor_timestamp_ns,arrival_elapsed_ns,absolute_count,session_delta,discontinuity\n",
    "accel_summary.csv":
        "session_id,window_start_elapsed_ns,window_end_elapsed_ns,event_count,mean_magnitude,min_magnitude,max_magnitude\n",
    "gyro_summary.csv":
        "session_id,window_start_elapsed_ns,window_end_elapsed_ns,event_count,mean_magnitude,min_magnitude,max_magnitude\n",
}


def write_manifest(root, role, names):
    entries = []
    for name in names:
        path = root / name
        data = path.read_bytes()
        entries.append(
            {
                "name": name,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            }
        )
    (root / "evidence_manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "v2e-1",
                "session_id": "v2e_io",
                "role": role,
                "finalized": True,
                "files": entries,
            }
        ),
        encoding="utf-8",
    )


class IoTest(unittest.TestCase):
    def make_fixture(self, producer_boot="boot", consumer_boot="boot"):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        producer = root / "producer"
        consumer = root / "consumer"
        producer.mkdir()
        consumer.mkdir()

        for name, text in PRODUCER_FILES.items():
            (producer / name).write_text(text, encoding="utf-8")
        (producer / "producer_meta.json").write_text(
            json.dumps(
                {
                    "schema_version": "v2e-1",
                    "session_id": "v2e_io",
                    "route_session_id": 1,
                    "model_seed": 7,
                    "movement_threshold_mps": 0.5,
                    "cadence_intercept_spm": 100.0,
                    "cadence_slope_spm_per_mps": 15.0,
                    "cadence_min_spm": 100.0,
                    "cadence_max_spm": 190.0,
                    "jitter_fraction": 0.02,
                    "catch_up_cap": 64,
                    "start_elapsed_ns": 1,
                    "end_elapsed_ns": 100,
                    "app_version": "1.12.3",
                    "source_commit_sha": "fixture",
                    "device_model": "fixture-device",
                    "android_release": "fixture-release",
                    "api_level": 32,
                    "boot_marker": producer_boot,
                    "recorder_status": "SUCCESS",
                    "error_codes": [],
                }
            ),
            encoding="utf-8",
        )
        write_manifest(
            producer,
            "producer",
            list(PRODUCER_FILES) + ["producer_meta.json"],
        )

        for name, text in CONSUMER_FILES.items():
            (consumer / name).write_text(text, encoding="utf-8")
        (consumer / "runnerprobe_meta.json").write_text(
            json.dumps(
                {
                    "schema_version": "v2e-1",
                    "session_id": "v2e_io",
                    "app_version": "0.1.0",
                    "source_commit_sha": "fixture",
                    "device_model": "fixture-device",
                    "android_release": "fixture-release",
                    "api_level": 32,
                    "start_elapsed_ns": 1,
                    "end_elapsed_ns": 100,
                    "official_start_elapsed_ns": -1,
                    "official_end_elapsed_ns": -1,
                    "boot_marker": consumer_boot,
                    "finalization_status": "SUCCESS",
                    "permission_state": "fixture",
                    "detector_name": "",
                    "detector_vendor": "",
                    "detector_version": -1,
                    "detector_wake_up": false,
                    "detector_reporting_mode": -1,
                    "counter_name": "",
                    "counter_vendor": "",
                    "counter_version": -1,
                    "counter_wake_up": false,
                    "counter_reporting_mode": -1,
                    "lifecycle_events": [],
                    "error_codes": [],
                }
            ),
            encoding="utf-8",
        )
        write_manifest(
            consumer,
            "consumer",
            list(CONSUMER_FILES) + ["runnerprobe_meta.json"],
        )
        return temp, producer, consumer

    def test_loads_valid_finalized_evidence(self):
        temp, producer, consumer = self.make_fixture()
        self.addCleanup(temp.cleanup)
        bundle = load_evidence(producer, consumer, "v2e_io")
        self.assertEqual("v2e_io", bundle.session_id)
        self.assertEqual(1, len(bundle.synthetic_motion))

    def test_tamper_is_rejected_before_loading(self):
        temp, producer, consumer = self.make_fixture()
        self.addCleanup(temp.cleanup)
        with (producer / "synthetic_motion.csv").open("a", encoding="utf-8") as handle:
            handle.write("tamper\n")
        with self.assertRaises(EvidenceError) as ctx:
            load_evidence(producer, consumer, "v2e_io")
        self.assertIn(
            ctx.exception.code,
            ("EVIDENCE_SIZE_MISMATCH", "EVIDENCE_HASH_MISMATCH"),
        )

    def test_missing_required_metadata_is_rejected(self):
        temp, producer, consumer = self.make_fixture()
        self.addCleanup(temp.cleanup)

        meta_path = producer / "producer_meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        del meta["boot_marker"]
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        write_manifest(
            producer,
            "producer",
            list(PRODUCER_FILES) + ["producer_meta.json"],
        )

        with self.assertRaises(EvidenceError) as ctx:
            load_evidence(producer, consumer, "v2e_io")
        self.assertEqual("METADATA_FIELD_MISSING", ctx.exception.code)

    def test_clock_domain_mismatch_is_rejected(self):
        temp, producer, consumer = self.make_fixture("boot-a", "boot-b")
        self.addCleanup(temp.cleanup)
        with self.assertRaises(EvidenceError) as ctx:
            load_evidence(producer, consumer, "v2e_io")
        self.assertEqual("CLOCK_DOMAIN_MISMATCH", ctx.exception.code)


if __name__ == "__main__":
    unittest.main()
