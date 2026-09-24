import unittest

from create_ground_truth import build_ground_truth
from v2e.models import EvidenceError


class GroundTruthToolTest(unittest.TestCase):
    def setUp(self):
        self.meta = {
            "schema_version": "v2e-1",
            "session_id": "v2e_gt_001",
            "official_start_elapsed_ns": 1_000_000_000,
            "official_end_elapsed_ns": 61_000_000_000,
        }

    def test_builds_interval_locked_ground_truth(self):
        value = build_ground_truth(self.meta, 160, "video", "tripod")
        self.assertEqual("v2e_gt_001", value["session_id"])
        self.assertEqual(160, value["count"])
        self.assertEqual("video", value["method"])
        self.assertEqual(
            self.meta["official_start_elapsed_ns"],
            value["official_start_elapsed_ns"],
        )
        self.assertEqual(
            self.meta["official_end_elapsed_ns"],
            value["official_end_elapsed_ns"],
        )

    def test_rejects_zero_count(self):
        with self.assertRaises(EvidenceError) as ctx:
            build_ground_truth(self.meta, 0, "manual")
        self.assertEqual("INVALID_GROUND_TRUTH", ctx.exception.code)

    def test_rejects_invalid_interval(self):
        broken = dict(self.meta)
        broken["official_end_elapsed_ns"] = broken["official_start_elapsed_ns"]
        with self.assertRaises(EvidenceError) as ctx:
            build_ground_truth(broken, 100, "manual")
        self.assertEqual("OFFICIAL_INTERVAL_INVALID", ctx.exception.code)


if __name__ == "__main__":
    unittest.main()
