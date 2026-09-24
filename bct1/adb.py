import subprocess


class AdbClient:
    def __init__(self, serial: str, runner=subprocess.run):
        serial = (serial or "").strip()
        if not serial:
            raise ValueError("an explicit ADB serial is required")
        self.serial = serial
        self._runner = runner

    def run(self, adb_args, check=True):
        args = ["adb", "-s", self.serial, *list(adb_args)]
        result = self._runner(
            args,
            text=True,
            capture_output=True,
            check=False,
        )
        if check and result.returncode != 0:
            raise subprocess.CalledProcessError(
                result.returncode,
                args,
                output=result.stdout,
                stderr=result.stderr,
            )
        return result

    def shell(self, command: str, check=True):
        result = self.run(["shell", command], check=check)
        stdout = (result.stdout or "").strip()
        if stdout:
            return stdout
        if not check:
            return (result.stderr or "").strip()
        return stdout
