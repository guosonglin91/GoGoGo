import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class AospAssetsTest(unittest.TestCase):
    def test_patch_registers_both_step_sensor_types(self):
        text = (ROOT / "aosp-sensor-lab/patches/0001-v2f-register-step-test-sensors.patch").read_text(
            encoding="utf-8"
        )
        for token in (
            "SENSOR_TYPE_STEP_DETECTOR",
            "SENSOR_TYPE_STEP_COUNTER",
            "V2F Virtual Step Detector",
            "V2F Virtual Step Counter",
        ):
            with self.subTest(token=token):
                self.assertIn(token, text)

    def test_injector_uses_lab_only_hal_bypass_path(self):
        text = (
            ROOT
            / "aosp-sensor-lab/injector/src/com/zcshou/v2finjector/InjectionService.java"
        ).read_text(encoding="utf-8")
        for token in (
            "HAL_BYPASS_REPLAY_DATA_INJECTION",
            "injectSensorData",
            "TYPE_STEP_DETECTOR",
            "TYPE_STEP_COUNTER",
            "Build.IS_DEBUGGABLE",
        ):
            with self.subTest(token=token):
                self.assertIn(token, text)

    def test_prepare_script_pins_expected_aosp_branch_and_checks_patch(self):
        text = (ROOT / "aosp-sensor-lab/scripts/prepare_aosp.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("aosp-android-latest-release", text)
        self.assertIn("git apply --check", text)

    def test_build_script_uses_sdk_phone_x86_64(self):
        text = (ROOT / "aosp-sensor-lab/scripts/build_avd.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("sdk_phone_x86_64", text)


if __name__ == "__main__":
    unittest.main()
