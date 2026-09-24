import subprocess
import unittest

from bct1.adb import AdbClient


class FakeCompleted:
    def __init__(self, stdout="", stderr="", returncode=0):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode


class AdbClientTest(unittest.TestCase):
    def test_adb_requires_explicit_serial(self):
        with self.assertRaises(ValueError):
            AdbClient("")

    def test_shell_always_targets_explicit_serial(self):
        calls = []

        def runner(args, **kwargs):
            calls.append((args, kwargs))
            return FakeCompleted(stdout="ok\n")

        client = AdbClient("ABC123", runner=runner)
        self.assertEqual("ok", client.shell("getprop ro.product.model"))
        self.assertEqual(
            ["adb", "-s", "ABC123", "shell", "getprop ro.product.model"],
            calls[0][0],
        )

    def test_nonzero_command_raises_when_check_enabled(self):
        def runner(args, **kwargs):
            return FakeCompleted(stderr="denied", returncode=1)

        client = AdbClient("ABC123", runner=runner)
        with self.assertRaises(subprocess.CalledProcessError):
            client.shell("dumpsys sensorservice")


if __name__ == "__main__":
    unittest.main()
