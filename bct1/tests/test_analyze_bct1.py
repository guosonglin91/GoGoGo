import csv
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from bct1.analyze_bct1 import analyze_bct1


SESSION = "bct1_165_20260924_230000"
TARGET = 165
NS_PER_MINUTE = 60_000_000_000


def event_offset(index, cadence=TARGET):
    return round(index * NS_PER_MINUTE / cadence)


def write_ledger(root, cadence=TARGET):
    path = Path(root) / "synthetic_step_timeline.csv"
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "step_index", "offset_ns", "detector_value",
            "counter_value", "target_spm"
        ])
        index = 1
        while True:
            offset = event_offset(index, cadence)
            if offset >= 90_000_000_000:
                break
            writer.writerow([index, offset, "1.0", 10000 + index, cadence])
            index += 1
    return path


def write_manifest(root, observation="observed"):
    payload = {
        "schema_version": "bct1-1",
        "session_id": SESSION,
        "execution_path": "aosp_emulator",
        "target_cadence_spm": TARGET,
        "duration_s": 90,
        "official_start_s": 30,
        "official_end_s": 90,
        "android_build_fingerprint": "aosp/test/fingerprint",
        "runnerprobe_package": "com.zcshou.runnerprobe",
        "runnerprobe_source_commit": "abc123",
        "aut_package": "example.running.app",
        "aut_version": "1.0",
        "aut_observation": observation,
    }
    path = Path(root) / "system_run.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def write_aut(root, cadence=165.0, observation="observed"):
    path = Path(root) / "aut_observation.json"
    path.write_text(json.dumps({
        "session_id": SESSION,
        "aut_package": "example.running.app",
        "aut_version": "1.0",
        "observation": observation,
        "stable_cadence_spm": cadence if observation == "observed" else None,
        "evidence_file": "aut_screen_recording.mp4",
        "notes": "",
    }), encoding="utf-8")
    return path


def write_runnerprobe_zip(root, *,
                          detector_indices=None,
                          counter_indices=None,
                          discontinuity=False):
    if detector_indices is None:
        detector_indices = list(range(1, 248))
    if counter_indices is None:
        counter_indices = list(range(1, 248))

    base = 1_000_000_000_000
    zip_path = Path(root) / "runnerprobe.zip"
    folder = f"session_{SESSION}/"

    detector_rows = [
        "session_id,sensor_timestamp_ns,arrival_elapsed_ns,event_value"
    ]
    for index in detector_indices:
        ts = base + event_offset(index)
        detector_rows.append(f"{SESSION},{ts},{ts + 1000000},1.000000000")

    counter_rows = [
        "session_id,sensor_timestamp_ns,arrival_elapsed_ns,absolute_count,session_delta,discontinuity"
    ]
    counter_rows.append(
        f"{SESSION},{base - 1000000},{base - 500000},10000,0,false"
    )
    for index in counter_indices:
        ts = base + event_offset(index)
        is_bad = discontinuity and index == counter_indices[len(counter_indices)//2]
        counter_rows.append(
            f"{SESSION},{ts},{ts + 1000000},{10000 + index},{index},{str(is_bad).lower()}"
        )

    meta = {
        "schema_version": "v2e-1",
        "session_id": SESSION,
        "finalization_status": "SUCCESS",
        "source_commit_sha": "abc123",
    }

    with zipfile.ZipFile(zip_path, "w") as archive:
        archive.writestr(folder + "step_detector_events.csv", "\n".join(detector_rows) + "\n")
        archive.writestr(folder + "step_counter_events.csv", "\n".join(counter_rows) + "\n")
        archive.writestr(folder + "runnerprobe_meta.json", json.dumps(meta))
    return zip_path


class AnalyzeBct1Test(unittest.TestCase):
    def run_case(self, *, aut_cadence=165.0, observation="observed",
                 detector_indices=None, counter_indices=None,
                 discontinuity=False):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        write_manifest(root, observation=observation)
        write_ledger(root)
        aut = write_aut(root, cadence=aut_cadence, observation=observation)
        runner = write_runnerprobe_zip(
            root,
            detector_indices=detector_indices,
            counter_indices=counter_indices,
            discontinuity=discontinuity,
        )
        return analyze_bct1(root, runner, aut)

    def test_android_pass_when_165_spm_reference_is_within_two_spm(self):
        report = self.run_case()
        self.assertTrue(report["android_reference"]["pass"])
        self.assertEqual(165, report["android_reference"]["detector_count"])
        self.assertEqual("PASS", report["classification"])

    def test_counter_discontinuity_is_fail(self):
        report = self.run_case(discontinuity=True)
        self.assertEqual("FAIL", report["classification"])
        self.assertIn("COUNTER_DISCONTINUITY", report["android_reference"]["errors"])

    def test_missing_counter_channel_is_partial(self):
        report = self.run_case(counter_indices=[])
        self.assertEqual("PARTIAL", report["classification"])

    def test_aut_absent_does_not_demote_android_reference_pass(self):
        report = self.run_case(observation="not_observed")
        self.assertTrue(report["android_reference"]["pass"])
        self.assertEqual("ANDROID_PASS_AUT_NOT_OBSERVED", report["classification"])

    def test_aut_171_for_target_165_is_not_aut_pass(self):
        report = self.run_case(aut_cadence=171.0)
        self.assertTrue(report["android_reference"]["pass"])
        self.assertFalse(report["aut"]["pass"])
        self.assertEqual("PARTIAL", report["classification"])

    def test_aut_170_for_target_165_is_aut_pass(self):
        report = self.run_case(aut_cadence=170.0)
        self.assertTrue(report["aut"]["pass"])
        self.assertEqual("PASS", report["classification"])

    def test_only_events_in_half_open_official_window_count(self):
        # Remove one event just before 30 s; official cadence must remain unchanged.
        all_indices = list(range(1, 248))
        before_30 = max(i for i in all_indices if event_offset(i) < 30_000_000_000)
        detector = [i for i in all_indices if i != before_30]
        counter = [i for i in all_indices if i != before_30]
        report = self.run_case(
            detector_indices=detector,
            counter_indices=counter,
        )
        self.assertEqual(165, report["android_reference"]["detector_count"])
        self.assertTrue(report["android_reference"]["pass"])


if __name__ == "__main__":
    unittest.main()
