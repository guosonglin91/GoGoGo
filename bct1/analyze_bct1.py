#!/usr/bin/env python3
import argparse
import csv
import io
import json
import math
import zipfile
from pathlib import Path, PurePosixPath

if __package__:
    from .manifest import Bct1Manifest, ManifestError
else:
    from manifest import Bct1Manifest, ManifestError


class AnalysisError(ValueError):
    pass


def _read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AnalysisError(f"cannot read JSON: {path}") from exc


def _read_ledger(path):
    try:
        with Path(path).open("r", encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle))
    except OSError as exc:
        raise AnalysisError(f"cannot read ledger: {path}") from exc

    required = {
        "step_index", "offset_ns", "detector_value",
        "counter_value", "target_spm"
    }
    if not rows or not required.issubset(rows[0].keys()):
        raise AnalysisError("synthetic ledger is empty or malformed")

    parsed = []
    previous_offset = -1
    for row in rows:
        try:
            item = {
                "step_index": int(row["step_index"]),
                "offset_ns": int(row["offset_ns"]),
                "detector_value": float(row["detector_value"]),
                "counter_value": int(row["counter_value"]),
                "target_spm": int(row["target_spm"]),
            }
        except (TypeError, ValueError) as exc:
            raise AnalysisError("malformed synthetic ledger row") from exc
        if item["offset_ns"] <= previous_offset:
            raise AnalysisError("synthetic ledger timestamps are not strictly monotonic")
        previous_offset = item["offset_ns"]
        parsed.append(item)
    return parsed


def _safe_zip_member(name):
    if not name or "\\" in name:
        return False
    path = PurePosixPath(name)
    return not path.is_absolute() and ".." not in path.parts


def _runnerprobe_payload(zip_path, session_id):
    root = f"session_{session_id}/"
    names = {
        "detector": root + "step_detector_events.csv",
        "counter": root + "step_counter_events.csv",
        "meta": root + "runnerprobe_meta.json",
    }
    try:
        archive = zipfile.ZipFile(zip_path, "r")
    except (OSError, zipfile.BadZipFile) as exc:
        raise AnalysisError("cannot open RunnerProbe ZIP") from exc

    with archive:
        for info in archive.infolist():
            if not _safe_zip_member(info.filename):
                raise AnalysisError("unsafe RunnerProbe ZIP entry")
        missing = [name for name in names.values() if name not in archive.namelist()]
        if missing:
            raise AnalysisError("RunnerProbe ZIP missing required evidence")
        detector_text = archive.read(names["detector"]).decode("utf-8")
        counter_text = archive.read(names["counter"]).decode("utf-8")
        meta = json.loads(archive.read(names["meta"]).decode("utf-8"))

    if meta.get("session_id") != session_id:
        raise AnalysisError("RunnerProbe session id mismatch")
    if meta.get("finalization_status") != "SUCCESS":
        raise AnalysisError("RunnerProbe evidence not finalized successfully")

    return (
        list(csv.DictReader(io.StringIO(detector_text))),
        list(csv.DictReader(io.StringIO(counter_text))),
        meta,
    )


def _parse_detector(rows, session_id):
    out = []
    for row in rows:
        if row.get("session_id") != session_id:
            raise AnalysisError("detector session id mismatch")
        try:
            out.append({
                "timestamp_ns": int(row["sensor_timestamp_ns"]),
                "value": float(row["event_value"]),
            })
        except (KeyError, TypeError, ValueError) as exc:
            raise AnalysisError("malformed detector row") from exc
    return out


def _parse_counter(rows, session_id):
    out = []
    for row in rows:
        if row.get("session_id") != session_id:
            raise AnalysisError("counter session id mismatch")
        try:
            discontinuity = row["discontinuity"].strip().lower()
            if discontinuity not in ("true", "false"):
                raise ValueError("invalid boolean")
            out.append({
                "timestamp_ns": int(row["sensor_timestamp_ns"]),
                "absolute_count": int(row["absolute_count"]),
                "session_delta": int(row["session_delta"]),
                "discontinuity": discontinuity == "true",
            })
        except (KeyError, TypeError, ValueError) as exc:
            raise AnalysisError("malformed counter row") from exc
    return out


def _strictly_increasing(values):
    return all(b > a for a, b in zip(values, values[1:]))


def _nondecreasing(values):
    return all(b >= a for a, b in zip(values, values[1:]))


def _aut_result(manifest, aut_payload):
    if aut_payload.get("session_id") != manifest.session_id:
        raise AnalysisError("AUT session id mismatch")
    if aut_payload.get("aut_package") != manifest.aut_package:
        raise AnalysisError("AUT package mismatch")

    observation = aut_payload.get("observation")
    if observation != manifest.aut_observation:
        raise AnalysisError("AUT observation does not match manifest")

    if observation != "observed":
        return {
            "observation": observation,
            "stable_cadence_spm": None,
            "pass": False,
        }

    try:
        cadence = float(aut_payload["stable_cadence_spm"])
    except (KeyError, TypeError, ValueError) as exc:
        raise AnalysisError("observed AUT cadence is missing or invalid") from exc

    return {
        "observation": observation,
        "stable_cadence_spm": cadence,
        "pass": abs(cadence - manifest.target_cadence_spm) <= 5.0,
    }


def analyze_bct1(session_dir, runnerprobe_zip, aut_observation):
    session_dir = Path(session_dir)
    manifest = Bct1Manifest.from_dict(_read_json(session_dir / "system_run.json"))
    ledger = _read_ledger(session_dir / "synthetic_step_timeline.csv")
    aut_payload = _read_json(aut_observation)

    if any(row["target_spm"] != manifest.target_cadence_spm for row in ledger):
        raise AnalysisError("ledger cadence does not match manifest")

    detector_rows, counter_rows, runner_meta = _runnerprobe_payload(
        runnerprobe_zip, manifest.session_id
    )
    detector = _parse_detector(detector_rows, manifest.session_id)
    counter = _parse_counter(counter_rows, manifest.session_id)

    errors = []
    missing = []
    if not detector:
        missing.append("STEP_DETECTOR")
    positive_counter = [row for row in counter if row["session_delta"] > 0]
    if not positive_counter:
        missing.append("STEP_COUNTER")

    if detector and not _strictly_increasing(
        [row["timestamp_ns"] for row in detector]
    ):
        errors.append("DETECTOR_TIMESTAMP_NON_MONOTONIC")
    if counter and not _strictly_increasing(
        [row["timestamp_ns"] for row in counter]
    ):
        errors.append("COUNTER_TIMESTAMP_NON_MONOTONIC")
    if counter and not _nondecreasing(
        [row["absolute_count"] for row in counter]
    ):
        errors.append("COUNTER_NON_MONOTONIC")
    if any(row["discontinuity"] for row in counter):
        errors.append("COUNTER_DISCONTINUITY")

    detector_count = 0
    counter_delta = 0
    detector_spm = 0.0
    base_elapsed_ns = None

    if detector and not missing:
        base_elapsed_ns = detector[0]["timestamp_ns"] - ledger[0]["offset_ns"]
        start_ns = base_elapsed_ns + manifest.official_start_s * 1_000_000_000
        end_ns = base_elapsed_ns + manifest.official_end_s * 1_000_000_000

        official_detector = [
            row for row in detector
            if start_ns <= row["timestamp_ns"] < end_ns
        ]
        official_counter = [
            row for row in counter
            if start_ns <= row["timestamp_ns"] < end_ns
            and row["session_delta"] > 0
        ]

        detector_count = len(official_detector)
        detector_spm = (
            detector_count * 60.0
            / (manifest.official_end_s - manifest.official_start_s)
        )

        if official_counter:
            counter_delta = (
                official_counter[-1]["absolute_count"]
                - official_counter[0]["absolute_count"]
                + 1
            )

        if abs(detector_spm - manifest.target_cadence_spm) > 2.0:
            errors.append("REFERENCE_CADENCE_OUT_OF_TOLERANCE")

        allowed_difference = max(2, math.ceil(0.03 * detector_count))
        if abs(detector_count - counter_delta) > allowed_difference:
            errors.append("DETECTOR_COUNTER_MISMATCH")

    android_pass = not missing and not errors
    if missing:
        android_status = "PARTIAL"
    elif errors:
        android_status = "FAIL"
    else:
        android_status = "PASS"

    aut = _aut_result(manifest, aut_payload)

    if android_status == "FAIL":
        classification = "FAIL"
    elif android_status == "PARTIAL":
        classification = "PARTIAL"
    elif aut["observation"] in ("not_observed", "environment_unsupported"):
        classification = "ANDROID_PASS_AUT_NOT_OBSERVED"
    elif aut["pass"]:
        classification = "PASS"
    else:
        classification = "PARTIAL"

    report = {
        "schema_version": "bct1-report-1",
        "session_id": manifest.session_id,
        "execution_path": manifest.execution_path,
        "target_cadence_spm": manifest.target_cadence_spm,
        "classification": classification,
        "android_reference": {
            "status": android_status,
            "pass": android_pass,
            "detector_count": detector_count,
            "detector_cadence_spm": detector_spm,
            "counter_delta": counter_delta,
            "missing_channels": missing,
            "errors": errors,
            "inferred_stream_base_elapsed_ns": base_elapsed_ns,
            "runnerprobe_source_commit": runner_meta.get("source_commit_sha", ""),
        },
        "aut": aut,
    }

    (session_dir / "bct1_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    lines = [
        f"BCT-1 session: {manifest.session_id}",
        f"Execution path: {manifest.execution_path}",
        f"Target cadence: {manifest.target_cadence_spm} spm",
        f"Android reference: {android_status}",
        f"Detector cadence: {detector_spm:.3f} spm",
        f"Counter delta: {counter_delta}",
        f"AUT observation: {aut['observation']}",
        f"Classification: {classification}",
    ]
    if missing:
        lines.append("Missing channels: " + ", ".join(missing))
    if errors:
        lines.append("Errors: " + ", ".join(errors))
    (session_dir / "bct1_report.txt").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description="Analyze one V2-F BCT-1 session")
    parser.add_argument("--session-dir", required=True)
    parser.add_argument("--runnerprobe-zip", required=True)
    parser.add_argument("--aut-observation", required=True)
    args = parser.parse_args(argv)
    try:
        report = analyze_bct1(
            args.session_dir, args.runnerprobe_zip, args.aut_observation
        )
    except (AnalysisError, ManifestError) as exc:
        print(f"BCT1_ANALYSIS_ERROR: {exc}")
        return 2
    print(report["classification"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
