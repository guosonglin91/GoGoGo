#!/usr/bin/env python3
import argparse
import json
import signal
from pathlib import Path

if __package__:
    from .adb import AdbClient
else:
    from adb import AdbClient


def parse_sensorservice_dump(text: str):
    lines = (text or "").splitlines()
    detector_lines = []
    counter_lines = []
    for line in lines:
        lowered = line.lower()
        if "android.sensor.step_detector" in lowered or "type=18" in lowered:
            detector_lines.append(line.strip())
        if "android.sensor.step_counter" in lowered or "type=19" in lowered:
            counter_lines.append(line.strip())
    return {
        "step_detector_present": bool(detector_lines),
        "step_counter_present": bool(counter_lines),
        "step_detector_lines": detector_lines,
        "step_counter_lines": counter_lines,
    }


def classify_probe(step_detector_present: bool,
                   step_counter_present: bool,
                   injection_available: bool):
    if not injection_available:
        return "UNAVAILABLE"
    if step_detector_present and step_counter_present:
        return "CANDIDATE"
    return "PARTIAL"


def collect_stock_probe(client):
    model = client.shell("getprop ro.product.model")
    fingerprint = client.shell("getprop ro.build.fingerprint")
    android_release = client.shell("getprop ro.build.version.release")
    sdk = client.shell("getprop ro.build.version.sdk")
    sensorservice = client.shell("dumpsys sensorservice")
    features = client.shell("pm list features", check=False)
    parsed = parse_sensorservice_dump(sensorservice)
    return {
        "schema_version": "bct1-phase0-1",
        "model": model,
        "build_fingerprint": fingerprint,
        "android_release": android_release,
        "sdk": sdk,
        "sensorservice": sensorservice,
        "features": features,
        **parsed,
    }


def _looks_rejected(text: str):
    lowered = (text or "").lower()
    return any(token in lowered for token in (
        "permission denied", "denied", "unknown command", "not supported",
        "unsupported", "securityexception",
    ))


def _looks_injection_mode(text: str):
    lowered = (text or "").lower()
    return "data_injection" in lowered or "data injection" in lowered


def attempt_reversible_injection_mode_probe(client, package_name: str):
    package_name = (package_name or "").strip()
    if not package_name:
        raise ValueError("package name is required")

    result = {
        "cleanup_attempted": False,
        "before": "",
        "command_output": "",
        "during": "",
        "cleanup_output": "",
        "after": "",
        "injection_available": False,
    }
    try:
        result["before"] = client.shell("dumpsys sensorservice")
        result["command_output"] = client.shell(
            f"dumpsys sensorservice data_injection {package_name}",
            check=False,
        )
        result["during"] = client.shell("dumpsys sensorservice", check=False)
        result["injection_available"] = (
            not _looks_rejected(result["command_output"])
            and _looks_injection_mode(result["during"])
        )
        return result
    finally:
        result["cleanup_attempted"] = True
        result["cleanup_output"] = client.shell(
            "dumpsys sensorservice enable", check=False
        )
        result["after"] = client.shell("dumpsys sensorservice", check=False)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Probe stock Android step-sensor and SensorService test capability"
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument(
        "--package-name", default="com.zcshou.runnerprobe",
        help="package used for the reversible SensorService injection-mode probe",
    )
    args = parser.parse_args(argv)

    client = AdbClient(args.serial)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    previous_sigint = signal.getsignal(signal.SIGINT)
    signal.signal(signal.SIGINT, signal.default_int_handler)
    try:
        report = collect_stock_probe(client)
        active = attempt_reversible_injection_mode_probe(client, args.package_name)
    finally:
        signal.signal(signal.SIGINT, previous_sigint)

    report["injection_probe"] = active
    report["classification"] = classify_probe(
        report["step_detector_present"],
        report["step_counter_present"],
        active["injection_available"],
    )

    (output_dir / "sensorservice.txt").write_text(
        report["sensorservice"] + "\n", encoding="utf-8"
    )
    (output_dir / "device_props.json").write_text(
        json.dumps({
            "model": report["model"],
            "build_fingerprint": report["build_fingerprint"],
            "android_release": report["android_release"],
            "sdk": report["sdk"],
        }, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    serializable = dict(report)
    serializable.pop("sensorservice", None)
    (output_dir / "phase0_probe.json").write_text(
        json.dumps(serializable, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(report["classification"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
