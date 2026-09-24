import csv
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import analyze_exports
from v2e.models import EvidenceError


SESSION_ID = "v2e_e2e_001"


def _write_csv(path, header, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)


def _write_manifest(root, role, names):
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
                "session_id": SESSION_ID,
                "role": role,
                "finalized": True,
                "files": entries,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )


def _zip_session(session_dir, output):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(session_dir.iterdir(), key=lambda p: p.name):
            archive.write(
                path,
                arcname=f"{session_dir.name}/{path.name}",
            )


class AnalyzeExportsTest(unittest.TestCase):
    def make_exports(self, root):
        producer = root / f"session_{SESSION_ID}_producer_source"
        consumer = root / f"session_{SESSION_ID}_consumer_source"
        producer.mkdir()
        consumer.mkdir()

        _write_csv(
            producer / "producer_location.csv",
            [
                "session_id",
                "provider",
                "publication_elapsed_ns",
                "location_elapsed_ns",
                "latitude",
                "longitude",
                "speed_mps",
                "bearing_deg",
                "accuracy_m",
            ],
            [
                [SESSION_ID, "gps", 1_001_000_000, 1_000_000_000, 34.0, 108.0, 4.0, 90.0, 5.0],
                [SESSION_ID, "gps", 2_001_000_000, 2_000_000_000, 34.0, 108.001, 4.0, 90.0, 5.0],
            ],
        )
        _write_csv(
            producer / "synthetic_motion.csv",
            [
                "session_id",
                "step_index",
                "step_elapsed_ns",
                "speed_mps",
                "target_cadence_spm",
                "instantaneous_cadence_spm",
                "interval_ns",
            ],
            [
                [SESSION_ID, 1, 375_000_000, 4.0, 160.0, 160.0, 375_000_000],
                [SESSION_ID, 2, 750_000_000, 4.0, 160.0, 160.0, 375_000_000],
            ],
        )
        (producer / "producer_meta.json").write_text(
            json.dumps(
                {
                    "schema_version": "v2e-1",
                    "session_id": SESSION_ID,
                    "boot_marker": "boot-e2e",
                    "recorder_status": "SUCCESS",
                    "error_codes": [],
                    "cadence_min_spm": 100.0,
                    "cadence_max_spm": 190.0,
                    "cadence_intercept_spm": 100.0,
                    "cadence_slope_spm_per_mps": 15.0,
                    "jitter_fraction": 0.02,
                }
            ),
            encoding="utf-8",
        )
        _write_manifest(
            producer,
            "producer",
            [
                "producer_location.csv",
                "synthetic_motion.csv",
                "producer_meta.json",
            ],
        )

        _write_csv(
            consumer / "location_events.csv",
            [
                "session_id",
                "provider",
                "location_elapsed_ns",
                "arrival_elapsed_ns",
                "wall_time_ms",
                "latitude",
                "longitude",
                "speed_mps",
                "bearing_deg",
                "accuracy_m",
                "is_mock",
            ],
            [
                [SESSION_ID, "gps", 1_000_000_000, 1_002_000_000, 1, 34.0, 108.0, 4.0, 90.0, 5.0, "true"],
                [SESSION_ID, "gps", 2_000_000_000, 2_002_000_000, 2, 34.0, 108.001, 4.0, 90.0, 5.0, "true"],
            ],
        )
        official_start = 10_000_000_000
        official_end = 70_000_000_000
        detector_rows = []
        for i in range(100):
            sensor_ts = official_start + (i + 1) * 500_000_000
            detector_rows.append(
                [
                    SESSION_ID,
                    sensor_ts,
                    sensor_ts + 10_000_000,
                    1.0,
                ]
            )

        _write_csv(
            consumer / "step_detector_events.csv",
            [
                "session_id",
                "sensor_timestamp_ns",
                "arrival_elapsed_ns",
                "event_value",
            ],
            detector_rows,
        )
        _write_csv(
            consumer / "step_counter_events.csv",
            [
                "session_id",
                "sensor_timestamp_ns",
                "arrival_elapsed_ns",
                "absolute_count",
                "session_delta",
                "discontinuity",
            ],
            [
                [
                    SESSION_ID,
                    official_start - 1,
                    official_start,
                    500,
                    0,
                    "false",
                ],
                [
                    SESSION_ID,
                    official_end,
                    official_end + 10_000_000,
                    600,
                    100,
                    "false",
                ],
            ],
        )
        for name in ("accel_summary.csv", "gyro_summary.csv"):
            _write_csv(
                consumer / name,
                [
                    "session_id",
                    "window_start_elapsed_ns",
                    "window_end_elapsed_ns",
                    "event_count",
                    "mean_magnitude",
                    "min_magnitude",
                    "max_magnitude",
                ],
                [],
            )
        (consumer / "runnerprobe_meta.json").write_text(
            json.dumps(
                {
                    "schema_version": "v2e-1",
                    "session_id": SESSION_ID,
                    "boot_marker": "boot-e2e",
                    "finalization_status": "SUCCESS",
                    "error_codes": [],
                    "official_start_elapsed_ns": official_start,
                    "official_end_elapsed_ns": official_end,
                    "detector_name": "fixture-detector",
                    "counter_name": "fixture-counter",
                }
            ),
            encoding="utf-8",
        )
        _write_manifest(
            consumer,
            "consumer",
            [
                "location_events.csv",
                "step_detector_events.csv",
                "step_counter_events.csv",
                "accel_summary.csv",
                "gyro_summary.csv",
                "runnerprobe_meta.json",
            ],
        )

        producer_zip = root / "producer.zip"
        consumer_zip = root / "consumer.zip"

        official_producer = root / f"session_{SESSION_ID}"
        producer.rename(official_producer)
        _zip_session(official_producer, producer_zip)
        official_producer.rename(producer)

        official_consumer = root / f"session_{SESSION_ID}"
        consumer.rename(official_consumer)
        _zip_session(official_consumer, consumer_zip)
        official_consumer.rename(consumer)

        return producer_zip, consumer_zip

    def test_end_to_end_zip_analysis_without_ground_truth(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            producer_zip, consumer_zip = self.make_exports(root)
            output = root / "analysis"

            result = analyze_exports.analyze_exports(
                producer_zip,
                consumer_zip,
                SESSION_ID,
                output,
            )

            summary = json.loads(
                (result / "session_summary.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("PASS", summary["gate_l"]["status"])
            self.assertEqual("NOT_RUN", summary["gate_r"]["status"])
            self.assertEqual("PASS", summary["gate_s"]["status"])
            self.assertTrue((result / "gate_report.txt").is_file())
            self.assertTrue((result / "producer").is_dir())
            self.assertTrue((result / "consumer").is_dir())

    def test_end_to_end_zip_analysis_with_ground_truth(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            producer_zip, consumer_zip = self.make_exports(root)
            output = root / "analysis"

            result = analyze_exports.analyze_exports(
                producer_zip,
                consumer_zip,
                SESSION_ID,
                output,
                ground_truth_count=100,
                ground_truth_method="manual",
                ground_truth_notes="fixture",
            )

            summary = json.loads(
                (result / "session_summary.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("PASS", summary["gate_l"]["status"])
            self.assertEqual("PASS", summary["gate_r"]["status"])
            self.assertEqual("PASS", summary["gate_s"]["status"])
            self.assertTrue(
                (result / "external_ground_truth.json").is_file()
            )

    def test_safe_extract_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "bad.zip"
            with zipfile.ZipFile(
                archive,
                "w",
                zipfile.ZIP_DEFLATED,
            ) as out:
                out.writestr("../escape.txt", "bad")

            with self.assertRaises(EvidenceError) as ctx:
                analyze_exports._safe_extract(
                    archive,
                    root / "extract",
                )
            self.assertEqual("UNSAFE_ZIP_ENTRY", ctx.exception.code)

    def test_ground_truth_arguments_must_be_paired(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            producer_zip, consumer_zip = self.make_exports(root)

            with self.assertRaises(EvidenceError) as ctx:
                analyze_exports.analyze_exports(
                    producer_zip,
                    consumer_zip,
                    SESSION_ID,
                    root / "analysis",
                    ground_truth_count=120,
                )
            self.assertEqual(
                "GROUND_TRUTH_ARGUMENT_MISMATCH",
                ctx.exception.code,
            )


if __name__ == "__main__":
    unittest.main()
