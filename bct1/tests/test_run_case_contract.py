import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "aosp-sensor-lab/scripts/run_case.sh"


class RunCaseContractTest(unittest.TestCase):
    def run_script(self, args, env=None):
        merged = os.environ.copy()
        if env:
            merged.update(env)
        return subprocess.run(
            ["bash", str(SCRIPT), *args],
            cwd=ROOT,
            env=merged,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_rejects_unsupported_cadence_before_adb(self):
        result = self.run_script(["SERIAL", "bct1_bad", "170"])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("150, 165, or 180", result.stderr)

    def test_rejects_missing_serial(self):
        result = self.run_script([])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("usage:", result.stderr.lower())

    def test_rejects_reused_session_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, "bct1_repeat").mkdir()
            result = self.run_script(
                ["SERIAL", "bct1_repeat", "165"],
                {"EVIDENCE_ROOT": temp},
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("already exists", result.stderr)

    def test_rejects_non_debuggable_device(self):
        with tempfile.TemporaryDirectory() as temp:
            fake_adb = Path(temp) / "adb"
            fake_adb.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$*\" == *\"get-state\"* ]]; then echo device; exit 0; fi\n"
                "if [[ \"$*\" == *\"getprop ro.debuggable\"* ]]; then echo 0; exit 0; fi\n"
                "exit 0\n",
                encoding="utf-8",
            )
            fake_adb.chmod(fake_adb.stat().st_mode | stat.S_IXUSR)
            result = self.run_script(
                ["SERIAL", "bct1_nodebug", "165"],
                {"ADB_BIN": str(fake_adb), "EVIDENCE_ROOT": str(Path(temp) / "evidence")},
            )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("debuggable", result.stderr)

    def test_script_has_unconditional_sensorservice_cleanup(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("trap cleanup EXIT INT TERM", text)
        self.assertIn("dumpsys sensorservice enable", text)


if __name__ == "__main__":
    unittest.main()
