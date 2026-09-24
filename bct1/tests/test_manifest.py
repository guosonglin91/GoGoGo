import unittest

from bct1.manifest import Bct1Manifest, ManifestError


def valid_manifest():
    return {
        "schema_version": "bct1-1",
        "session_id": "bct1_165_20260924_230000",
        "execution_path": "aosp_emulator",
        "target_cadence_spm": 165,
        "duration_s": 90,
        "official_start_s": 30,
        "official_end_s": 90,
        "android_build_fingerprint": "aosp/test/fingerprint",
        "runnerprobe_package": "com.zcshou.runnerprobe",
        "runnerprobe_source_commit": "abc123",
        "aut_package": "example.running.app",
        "aut_version": "1.0",
        "aut_observation": "observed",
    }


class ManifestTest(unittest.TestCase):
    def test_valid_manifest_is_accepted(self):
        manifest = Bct1Manifest.from_dict(valid_manifest())
        self.assertEqual(165, manifest.target_cadence_spm)
        self.assertEqual("aosp_emulator", manifest.execution_path)

    def test_unknown_execution_path_is_rejected(self):
        data = valid_manifest()
        data["execution_path"] = "mystery"
        with self.assertRaises(ManifestError):
            Bct1Manifest.from_dict(data)

    def test_formal_window_is_frozen(self):
        data = valid_manifest()
        data["official_start_s"] = 31
        with self.assertRaises(ManifestError):
            Bct1Manifest.from_dict(data)

    def test_unknown_aut_observation_is_rejected(self):
        data = valid_manifest()
        data["aut_observation"] = "maybe"
        with self.assertRaises(ManifestError):
            Bct1Manifest.from_dict(data)


if __name__ == "__main__":
    unittest.main()
