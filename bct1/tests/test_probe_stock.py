import unittest

from bct1.probe_stock import (
    attempt_reversible_injection_mode_probe,
    classify_probe,
    parse_sensorservice_dump,
)


FIXTURE_BOTH_STEP_SENSORS = """
Sensor List:
0x0000002a) Step Detector | OPPO | type=18 | android.sensor.step_detector
0x0000002b) Step Counter | OPPO | type=19 | android.sensor.step_counter
"""

FIXTURE_DETECTOR_ONLY = """
Sensor List:
0x0000002a) Step Detector | OPPO | type=18 | android.sensor.step_detector
"""


class FakeAdb:
    def __init__(self, responses=None):
        self.responses = responses or {}
        self.calls = []

    def shell(self, command, check=True):
        self.calls.append((command, check))
        value = self.responses.get(command, "")
        if isinstance(value, Exception):
            raise value
        return value


class StockProbeTest(unittest.TestCase):
    def test_probe_detects_both_step_sensor_types(self):
        report = parse_sensorservice_dump(FIXTURE_BOTH_STEP_SENSORS)
        self.assertTrue(report["step_detector_present"])
        self.assertTrue(report["step_counter_present"])

    def test_probe_marks_missing_counter(self):
        report = parse_sensorservice_dump(FIXTURE_DETECTOR_ONLY)
        self.assertTrue(report["step_detector_present"])
        self.assertFalse(report["step_counter_present"])

    def test_classification_requires_both_step_sensors_for_candidate(self):
        self.assertEqual("CANDIDATE", classify_probe(True, True, True))
        self.assertEqual("PARTIAL", classify_probe(True, False, True))
        self.assertEqual("UNAVAILABLE", classify_probe(True, True, False))

    def test_active_probe_always_restores_normal_mode(self):
        adb = FakeAdb({
            "dumpsys sensorservice": "NORMAL",
            "dumpsys sensorservice data_injection com.zcshou.runnerprobe": "enabled",
            "dumpsys sensorservice enable": "normal",
        })
        result = attempt_reversible_injection_mode_probe(
            adb, "com.zcshou.runnerprobe"
        )
        self.assertTrue(result["cleanup_attempted"])
        self.assertEqual("dumpsys sensorservice enable", adb.calls[-2][0])
        self.assertEqual("dumpsys sensorservice", adb.calls[-1][0])

    def test_active_probe_restores_normal_mode_after_failure(self):
        class FailingAdb(FakeAdb):
            def shell(self, command, check=True):
                self.calls.append((command, check))
                if "data_injection" in command:
                    raise RuntimeError("denied")
                return "NORMAL"

        adb = FailingAdb()
        with self.assertRaises(RuntimeError):
            attempt_reversible_injection_mode_probe(
                adb, "com.zcshou.runnerprobe"
            )
        commands = [command for command, _ in adb.calls]
        self.assertIn("dumpsys sensorservice enable", commands)
        self.assertEqual("dumpsys sensorservice", commands[-1])


if __name__ == "__main__":
    unittest.main()
