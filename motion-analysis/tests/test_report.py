import json
import tempfile
import unittest
from pathlib import Path

from v2e.models import EvidenceBundle, GateResult, GateStatus
from v2e.report import write_report


class ReportTest(unittest.TestCase):
    def test_writes_canonical_merged_session(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            producer = root / "producer_source"
            consumer = root / "consumer_source"
            producer.mkdir()
            consumer.mkdir()
            (producer / "producer_meta.json").write_text("{}", encoding="utf-8")
            (consumer / "runnerprobe_meta.json").write_text("{}", encoding="utf-8")

            bundle = EvidenceBundle(
                session_id="v2e_report",
                producer_dir=str(producer),
                consumer_dir=str(consumer),
                producer_meta={"schema_version": "v2e-1"},
                consumer_meta={"schema_version": "v2e-1"},
                producer_location=[],
                synthetic_motion=[],
                consumer_location=[],
                step_detector=[],
                step_counter=[],
                accel_summary=[],
                gyro_summary=[],
            )
            result = GateResult(GateStatus.PASS, {"x": 1}, [], [])
            not_run = GateResult(GateStatus.NOT_RUN, {}, [], [])

            out = write_report(
                root / "analysis",
                bundle,
                result,
                not_run,
                result,
            )
            self.assertTrue((out / "producer").is_dir())
            self.assertTrue((out / "consumer").is_dir())
            self.assertTrue((out / "session_summary.json").is_file())
            self.assertTrue((out / "gate_report.txt").is_file())

            summary = json.loads(
                (out / "session_summary.json").read_text(encoding="utf-8")
            )
            self.assertEqual("PASS", summary["gate_l"]["status"])
            self.assertEqual("NOT_RUN", summary["gate_r"]["status"])
            self.assertEqual("PASS", summary["gate_s"]["status"])


if __name__ == "__main__":
    unittest.main()
