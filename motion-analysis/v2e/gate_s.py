import math
from typing import Dict, List

from .models import GateResult, GateStatus


def _f(row: Dict[str, str], key: str) -> float:
    return float(row[key])


def _i(row: Dict[str, str], key: str) -> int:
    return int(float(row[key]))


def evaluate_gate_s(
    rows: List[Dict[str, str]],
    producer_meta: Dict,
) -> GateResult:
    errors = []
    metrics = {"step_count": len(rows)}

    if producer_meta.get("recorder_status") not in (None, "", "SUCCESS"):
        errors.append("PRODUCER_RECORDER_FAILURE")

    reported_errors = [
        str(code)
        for code in producer_meta.get("error_codes", [])
        if str(code)
    ]
    if reported_errors:
        errors.append("PRODUCER_REPORTED_ERROR")
        errors.extend(reported_errors)

    if not rows:
        errors.append("SYNTHETIC_TRACE_EMPTY")
        return GateResult(GateStatus.FAIL, metrics, errors)

    min_cadence = float(producer_meta.get("cadence_min_spm", 100.0))
    max_cadence = float(producer_meta.get("cadence_max_spm", 190.0))
    intercept = float(producer_meta.get("cadence_intercept_spm", 100.0))
    slope = float(producer_meta.get("cadence_slope_spm_per_mps", 15.0))
    jitter_bound = float(producer_meta.get("jitter_fraction", 0.02))

    previous_index = None
    previous_time = None
    max_jitter = 0.0
    max_cadence_error = 0.0
    max_mapping_error = 0.0
    max_mapping_relative_error = 0.0

    for row in rows:
        index = _i(row, "step_index")
        time_ns = _i(row, "step_elapsed_ns")
        interval_ns = _i(row, "interval_ns")
        speed = _f(row, "speed_mps")
        target = _f(row, "target_cadence_spm")
        instantaneous = _f(row, "instantaneous_cadence_spm")

        if previous_index is not None and index != previous_index + 1:
            errors.append("STEP_INDEX_NON_MONOTONIC")
        if previous_time is not None:
            if time_ns <= previous_time:
                errors.append("STEP_TIME_NON_MONOTONIC")
            elif interval_ns > 0 and time_ns - previous_time != interval_ns:
                errors.append("STEP_TIME_INTERVAL_MISMATCH")
        if interval_ns <= 0:
            errors.append("INVALID_STEP_INTERVAL")
        else:
            expected_instantaneous = 60_000_000_000.0 / interval_ns
            relative = abs(instantaneous - expected_instantaneous) / expected_instantaneous
            max_cadence_error = max(max_cadence_error, relative)
            if relative > 1e-6:
                errors.append("CADENCE_INTERVAL_MISMATCH")

            base_interval = 60_000_000_000.0 / target if target > 0 else math.inf
            jitter = abs(interval_ns - base_interval) / base_interval
            max_jitter = max(max_jitter, jitter)
            if jitter > jitter_bound + 1e-9:
                errors.append("JITTER_BOUND_EXCEEDED")

        if target < min_cadence - 1e-9 or target > max_cadence + 1e-9:
            errors.append("TARGET_CADENCE_OUT_OF_RANGE")

        expected_target = min(max(intercept + slope * speed, min_cadence), max_cadence)
        mapping_error = abs(target - expected_target)
        mapping_relative_error = mapping_error / max(1.0, abs(expected_target))
        max_mapping_error = max(max_mapping_error, mapping_error)
        max_mapping_relative_error = max(
            max_mapping_relative_error,
            mapping_relative_error,
        )
        if mapping_relative_error > 1e-6:
            errors.append("SPEED_CADENCE_MISMATCH")

        previous_index = index
        previous_time = time_ns

    errors = list(dict.fromkeys(errors))
    metrics.update(
        {
            "first_step_index": _i(rows[0], "step_index"),
            "last_step_index": _i(rows[-1], "step_index"),
            "max_relative_cadence_interval_error": max_cadence_error,
            "max_relative_jitter": max_jitter,
            "configured_jitter_bound": jitter_bound,
            "max_target_mapping_error_spm": max_mapping_error,
            "max_relative_target_mapping_error": max_mapping_relative_error,
        }
    )

    return GateResult(
        GateStatus.FAIL if errors else GateStatus.PASS,
        metrics,
        errors,
    )
