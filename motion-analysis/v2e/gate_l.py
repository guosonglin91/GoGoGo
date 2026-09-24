import math
from typing import Dict, List

from .models import GateResult, GateStatus


EARTH_RADIUS_M = 6_371_000.0


def _haversine(lat1, lon1, lat2, lon2):
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = (
        math.sin(dp / 2.0) ** 2
        + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    )
    return 2.0 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def _bool(value: str) -> bool:
    return str(value).strip().lower() in ("1", "true", "yes")


def _finite_optional(value: str):
    if value in (None, ""):
        return None
    parsed = float(value)
    return parsed if math.isfinite(parsed) else None


def evaluate_gate_l(
    producer_rows: List[Dict[str, str]],
    consumer_rows: List[Dict[str, str]],
) -> GateResult:
    errors = []
    notes = []
    metrics = {
        "producer_callback_count": len(producer_rows),
        "consumer_callback_count": len(consumer_rows),
    }

    if not producer_rows or not consumer_rows:
        if not producer_rows:
            errors.append("PRODUCER_LOCATION_TRACE_EMPTY")
        if not consumer_rows:
            errors.append("LOCATION_TRACE_EMPTY")
        return GateResult(GateStatus.FAIL, metrics, errors)

    for rows, time_key, code in (
        (producer_rows, "location_elapsed_ns", "PRODUCER_LOCATION_TIME_NON_MONOTONIC"),
        (consumer_rows, "location_elapsed_ns", "LOCATION_TIME_NON_MONOTONIC"),
    ):
        previous = None
        for row in rows:
            current = int(float(row[time_key]))
            if previous is not None and current < previous:
                errors.append(code)
                break
            previous = current

    allowed = {"gps", "network"}
    producer_providers = {r["provider"] for r in producer_rows}
    consumer_providers = {r["provider"] for r in consumer_rows}
    if not producer_providers.issubset(allowed) or not consumer_providers.issubset(allowed):
        errors.append("LOCATION_PROVIDER_INVALID")

    def path_distance(rows):
        total = 0.0
        for previous, current in zip(rows, rows[1:]):
            total += _haversine(
                float(previous["latitude"]),
                float(previous["longitude"]),
                float(current["latitude"]),
                float(current["longitude"]),
            )
        return total

    producer_motion = path_distance(producer_rows)
    consumer_motion = path_distance(consumer_rows)
    metrics["producer_path_distance_m"] = producer_motion
    metrics["consumer_path_distance_m"] = consumer_motion

    if producer_motion <= 1e-3:
        errors.append("PRODUCER_LOCATION_NO_MOTION")
    if consumer_motion <= 1e-3:
        errors.append("LOCATION_NO_MOTION")

    mock_count = sum(1 for r in consumer_rows if _bool(r.get("is_mock", "")))
    metrics["consumer_mock_callback_count"] = mock_count
    if mock_count == 0:
        errors.append("LOCATION_NOT_MOCK_OBSERVED")

    # Correlate by provider + Location.elapsedRealtimeNanos. Android normally
    # preserves this value from publication to consumer callback.
    consumer_index = {}
    for row in consumer_rows:
        key = (row["provider"], int(float(row["location_elapsed_ns"])))
        consumer_index.setdefault(key, row)

    matched = 0
    spatial_errors = []
    arrival_lags_ms = []
    for producer in producer_rows:
        key = (
            producer["provider"],
            int(float(producer["location_elapsed_ns"])),
        )
        consumer = consumer_index.get(key)
        if consumer is None:
            continue
        matched += 1
        spatial_errors.append(
            _haversine(
                float(producer["latitude"]),
                float(producer["longitude"]),
                float(consumer["latitude"]),
                float(consumer["longitude"]),
            )
        )
        publication = int(float(producer["publication_elapsed_ns"]))
        arrival = int(float(consumer["arrival_elapsed_ns"]))
        arrival_lags_ms.append((arrival - publication) / 1_000_000.0)

    metrics["exact_elapsed_time_matches"] = matched
    metrics["producer_to_consumer_match_fraction"] = matched / len(producer_rows)
    metrics["max_spatial_error_m"] = max(spatial_errors) if spatial_errors else None
    metrics["median_spatial_error_m"] = (
        sorted(spatial_errors)[len(spatial_errors) // 2] if spatial_errors else None
    )
    metrics["max_callback_lag_ms"] = max(arrival_lags_ms) if arrival_lags_ms else None

    if matched == 0:
        notes.append(
            "No exact elapsed-realtime correlation was found; V2-E reports this "
            "as a calibration metric rather than inventing a device-independent threshold."
        )

    errors = list(dict.fromkeys(errors))
    return GateResult(
        GateStatus.FAIL if errors else GateStatus.PASS,
        metrics,
        errors,
        notes,
    )
