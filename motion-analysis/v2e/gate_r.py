import math
from typing import Dict, List

from .models import EvidenceError, GateResult, GateStatus


def classify_step_error(observed: int, ground_truth: int) -> GateStatus:
    if ground_truth <= 0:
        raise EvidenceError("INVALID_GROUND_TRUTH", "Ground truth must be > 0")
    error = abs(observed - ground_truth) / ground_truth
    if error <= 0.03:
        return GateStatus.PASS
    if error <= 0.05:
        return GateStatus.WARN
    return GateStatus.FAIL


def detector_counter_agree(detector_count: int, counter_delta: int) -> bool:
    tolerance = max(2, math.ceil(0.03 * detector_count))
    return abs(detector_count - counter_delta) <= tolerance


def _worse(a: GateStatus, b: GateStatus) -> GateStatus:
    severity = {
        GateStatus.NOT_RUN: 0,
        GateStatus.PASS: 1,
        GateStatus.WARN: 2,
        GateStatus.FAIL: 3,
    }
    return a if severity[a] >= severity[b] else b


def evaluate_gate_r(
    detector_rows: List[Dict[str, str]],
    counter_rows: List[Dict[str, str]],
    metadata: Dict,
    ground_truth: Dict,
) -> GateResult:
    start_ns = metadata.get("official_start_elapsed_ns")
    end_ns = metadata.get("official_end_elapsed_ns")
    if not isinstance(start_ns, int) or not isinstance(end_ns, int):
        raise EvidenceError("OFFICIAL_INTERVAL_MISSING", "Official Gate-R interval missing")
    if end_ns <= start_ns:
        raise EvidenceError("OFFICIAL_INTERVAL_INVALID", "Official Gate-R interval invalid")

    duration_s = (end_ns - start_ns) / 1_000_000_000.0
    gt = ground_truth.get("count")
    if not isinstance(gt, int) or isinstance(gt, bool) or gt <= 0:
        raise EvidenceError("INVALID_GROUND_TRUTH", "Ground truth must be integer > 0")

    errors = []
    notes = []
    status = GateStatus.PASS

    if metadata.get("finalization_status") not in (None, "", "SUCCESS"):
        errors.append("CONSUMER_RECORDER_FAILURE")
    if "SESSION_INTERRUPTED" in metadata.get("error_codes", []):
        errors.append("SESSION_INTERRUPTED")

    detector_name = str(metadata.get("detector_name") or "")
    counter_name = str(metadata.get("counter_name") or "")
    if not detector_name:
        errors.append("STEP_DETECTOR_ABSENT")
    if not counter_name:
        errors.append("STEP_COUNTER_ABSENT")

    if duration_s < 60.0:
        errors.append("SESSION_TOO_SHORT")

    detector_official = []
    previous_detector_ts = None
    max_detector_arrival_lag_ms = 0.0
    for row in detector_rows:
        ts = int(float(row["sensor_timestamp_ns"]))
        if previous_detector_ts is not None and ts <= previous_detector_ts:
            errors.append("NON_MONOTONIC_STEP_TIME")
        previous_detector_ts = ts
        if start_ns <= ts <= end_ns:
            detector_official.append(row)
            arrival = int(float(row["arrival_elapsed_ns"]))
            max_detector_arrival_lag_ms = max(
                max_detector_arrival_lag_ms,
                (arrival - ts) / 1_000_000.0,
            )

    detector_count = len(detector_official)
    if gt > 0 and detector_name and detector_count == 0:
        errors.append("SENSOR_PRESENT_NO_EVENTS")

    discontinuity = any(
        str(row.get("discontinuity", "")).lower() in ("1", "true", "yes")
        for row in counter_rows
    )
    if discontinuity:
        errors.append("COUNTER_DISCONTINUITY")

    previous_counter_ts = None
    for row in counter_rows:
        ts = int(float(row["sensor_timestamp_ns"]))
        if previous_counter_ts is not None and ts <= previous_counter_ts:
            errors.append("COUNTER_TIME_NON_MONOTONIC")
            break
        previous_counter_ts = ts

    sorted_counter = sorted(
        counter_rows,
        key=lambda r: int(float(r["sensor_timestamp_ns"])),
    )

    baseline = None
    end_value = None
    for row in sorted_counter:
        ts = int(float(row["sensor_timestamp_ns"]))
        absolute = int(float(row["absolute_count"]))
        if ts <= start_ns:
            baseline = absolute
        if ts <= end_ns:
            end_value = absolute

    counter_delta = None
    if baseline is not None and end_value is not None:
        counter_delta = end_value - baseline
        if counter_delta < 0:
            errors.append("COUNTER_DISCONTINUITY")
    else:
        errors.append("COUNTER_BASELINE_UNAVAILABLE")

    detector_accuracy = classify_step_error(detector_count, gt)
    status = _worse(status, detector_accuracy)

    counter_accuracy = GateStatus.FAIL
    if counter_delta is not None and counter_delta >= 0:
        counter_accuracy = classify_step_error(counter_delta, gt)
        status = _worse(status, counter_accuracy)
        if not detector_counter_agree(detector_count, counter_delta):
            errors.append("DETECTOR_COUNTER_DISAGREE")

    if errors:
        hard = {
            "STEP_DETECTOR_ABSENT",
            "STEP_COUNTER_ABSENT",
            "SESSION_TOO_SHORT",
            "SENSOR_PRESENT_NO_EVENTS",
            "COUNTER_DISCONTINUITY",
            "NON_MONOTONIC_STEP_TIME",
            "COUNTER_TIME_NON_MONOTONIC",
            "COUNTER_BASELINE_UNAVAILABLE",
            "DETECTOR_COUNTER_DISAGREE",
            "CONSUMER_RECORDER_FAILURE",
            "SESSION_INTERRUPTED",
        }
        if any(code in hard for code in errors):
            status = GateStatus.FAIL

    if "CALLBACK_BATCHING" in metadata.get("error_codes", []):
        notes.append("Device reported callback batching.")
        if status == GateStatus.PASS:
            status = GateStatus.WARN

    metrics = {
        "duration_seconds": duration_s,
        "ground_truth_steps": gt,
        "detector_steps": detector_count,
        "counter_delta": counter_delta,
        "detector_ground_truth_relative_error": abs(detector_count - gt) / gt,
        "counter_ground_truth_relative_error": (
            abs(counter_delta - gt) / gt
            if counter_delta is not None and counter_delta >= 0
            else None
        ),
        "detector_accuracy_status": detector_accuracy.value,
        "counter_accuracy_status": counter_accuracy.value,
        "detector_counter_agree": (
            detector_counter_agree(detector_count, counter_delta)
            if counter_delta is not None and counter_delta >= 0
            else False
        ),
        "max_detector_arrival_lag_ms": max_detector_arrival_lag_ms,
    }

    return GateResult(
        status,
        metrics,
        list(dict.fromkeys(errors)),
        notes,
    )
