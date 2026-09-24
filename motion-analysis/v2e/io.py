import csv
import hashlib
import json
import math
from pathlib import Path
from typing import Dict, List, Mapping, Sequence

from .models import EvidenceBundle, EvidenceError

SCHEMA_VERSION = "v2e-1"

PRODUCER_REQUIRED = (
    "producer_location.csv",
    "synthetic_motion.csv",
    "producer_meta.json",
)
CONSUMER_REQUIRED = (
    "location_events.csv",
    "step_detector_events.csv",
    "step_counter_events.csv",
    "accel_summary.csv",
    "gyro_summary.csv",
    "runnerprobe_meta.json",
)

HEADERS: Mapping[str, Sequence[str]] = {
    "producer_location.csv": (
        "session_id",
        "provider",
        "publication_elapsed_ns",
        "location_elapsed_ns",
        "latitude",
        "longitude",
        "speed_mps",
        "bearing_deg",
        "accuracy_m",
    ),
    "synthetic_motion.csv": (
        "session_id",
        "step_index",
        "step_elapsed_ns",
        "speed_mps",
        "target_cadence_spm",
        "instantaneous_cadence_spm",
        "interval_ns",
    ),
    "location_events.csv": (
        "session_id",
        "provider",
        "location_elapsed_ns",
        "arrival_elapsed_ns",
        "wall_time_ms",
        "latitude",
        "longitude",
        "speed_mps",
        "bearing_deg",
        "accuracy_m",
        "is_mock",
    ),
    "step_detector_events.csv": (
        "session_id",
        "sensor_timestamp_ns",
        "arrival_elapsed_ns",
        "event_value",
    ),
    "step_counter_events.csv": (
        "session_id",
        "sensor_timestamp_ns",
        "arrival_elapsed_ns",
        "absolute_count",
        "session_delta",
        "discontinuity",
    ),
    "accel_summary.csv": (
        "session_id",
        "window_start_elapsed_ns",
        "window_end_elapsed_ns",
        "event_count",
        "mean_magnitude",
        "min_magnitude",
        "max_magnitude",
    ),
    "gyro_summary.csv": (
        "session_id",
        "window_start_elapsed_ns",
        "window_end_elapsed_ns",
        "event_count",
        "mean_magnitude",
        "min_magnitude",
        "max_magnitude",
    ),
}

NUMERIC_FIELDS = {
    "publication_elapsed_ns",
    "location_elapsed_ns",
    "arrival_elapsed_ns",
    "wall_time_ms",
    "latitude",
    "longitude",
    "speed_mps",
    "bearing_deg",
    "accuracy_m",
    "step_index",
    "step_elapsed_ns",
    "target_cadence_spm",
    "instantaneous_cadence_spm",
    "interval_ns",
    "sensor_timestamp_ns",
    "event_value",
    "absolute_count",
    "session_delta",
    "window_start_elapsed_ns",
    "window_end_elapsed_ns",
    "event_count",
    "mean_magnitude",
    "min_magnitude",
    "max_magnitude",
}


def _read_json(path: Path) -> Dict:
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
    except FileNotFoundError as exc:
        raise EvidenceError(
            "MISSING_REQUIRED_FILE", f"Missing required file: {path.name}"
        ) from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(
            "MALFORMED_JSON", f"Cannot parse JSON: {path.name}"
        ) from exc
    if not isinstance(value, dict):
        raise EvidenceError("MALFORMED_JSON", f"JSON root is not an object: {path.name}")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise EvidenceError(
            "EVIDENCE_FILE_READ_FAILURE", f"Cannot read: {path.name}"
        ) from exc
    return digest.hexdigest()


def verify_manifest(root: Path, expected_role: str, session_id: str) -> Dict:
    manifest_path = root / "evidence_manifest.json"
    manifest = _read_json(manifest_path)

    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError(
            "UNSUPPORTED_SCHEMA_VERSION",
            f"Unsupported schema in {manifest_path}",
        )
    if manifest.get("session_id") != session_id:
        raise EvidenceError(
            "SESSION_ID_MISMATCH",
            f"Manifest session ID mismatch in {manifest_path}",
        )
    if manifest.get("role") != expected_role:
        raise EvidenceError(
            "INVALID_MANIFEST_ROLE",
            f"Expected {expected_role} manifest role",
        )
    if manifest.get("finalized") is not True:
        raise EvidenceError(
            "SESSION_NOT_FINALIZED",
            f"Session is not finalized: {root}",
        )

    entries = manifest.get("files")
    if not isinstance(entries, list):
        raise EvidenceError("MALFORMED_MANIFEST", "Manifest files must be a list")

    seen = set()
    for item in entries:
        if not isinstance(item, dict):
            raise EvidenceError("MALFORMED_MANIFEST", "Manifest entry is not an object")
        name = item.get("name")
        if (
            not isinstance(name, str)
            or not name
            or "/" in name
            or "\\" in name
            or ".." in name
        ):
            raise EvidenceError("INVALID_EVIDENCE_FILE_NAME", f"Unsafe evidence name: {name}")
        if name in seen:
            raise EvidenceError("DUPLICATE_EVIDENCE_FILE", f"Duplicate entry: {name}")
        seen.add(name)

        path = root / name
        if not path.is_file():
            raise EvidenceError("EVIDENCE_FILE_MISSING", f"Missing evidence file: {name}")

        expected_bytes = item.get("bytes")
        if not isinstance(expected_bytes, int) or expected_bytes < 0:
            raise EvidenceError("MALFORMED_MANIFEST", f"Invalid byte count: {name}")
        if path.stat().st_size != expected_bytes:
            raise EvidenceError("EVIDENCE_SIZE_MISMATCH", f"Size mismatch: {name}")

        expected_hash = item.get("sha256")
        if (
            not isinstance(expected_hash, str)
            or len(expected_hash) != 64
            or any(c not in "0123456789abcdefABCDEF" for c in expected_hash)
        ):
            raise EvidenceError("MALFORMED_MANIFEST", f"Invalid SHA-256: {name}")
        if _sha256(path).lower() != expected_hash.lower():
            raise EvidenceError("EVIDENCE_HASH_MISMATCH", f"SHA-256 mismatch: {name}")

    required = PRODUCER_REQUIRED if expected_role == "producer" else CONSUMER_REQUIRED
    missing = [name for name in required if name not in seen]
    if missing:
        raise EvidenceError(
            "MISSING_REQUIRED_FILE",
            "Manifest omits required file(s): " + ", ".join(missing),
        )
    return manifest


def read_csv(path: Path, session_id: str) -> List[Dict[str, str]]:
    expected = HEADERS.get(path.name)
    if expected is None:
        raise EvidenceError("UNKNOWN_EVIDENCE_FILE", f"No CSV contract for {path.name}")

    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            fieldnames = reader.fieldnames
            if fieldnames is None:
                raise EvidenceError("MALFORMED_CSV", f"Missing header: {path.name}")
            if len(fieldnames) != len(set(fieldnames)):
                raise EvidenceError("DUPLICATE_CSV_HEADER", f"Duplicate header: {path.name}")
            if tuple(fieldnames) != tuple(expected):
                raise EvidenceError(
                    "CSV_HEADER_MISMATCH",
                    f"Unexpected header in {path.name}: {fieldnames}",
                )
            rows = []
            for row_number, row in enumerate(reader, start=2):
                if row.get("session_id") != session_id:
                    raise EvidenceError(
                        "SESSION_ID_MISMATCH",
                        f"{path.name}:{row_number} session ID mismatch",
                    )
                for key in NUMERIC_FIELDS:
                    value = row.get(key)
                    if value is None or value == "":
                        continue
                    try:
                        parsed = float(value)
                    except ValueError as exc:
                        raise EvidenceError(
                            "INVALID_NUMERIC_VALUE",
                            f"{path.name}:{row_number} invalid {key}",
                        ) from exc
                    if not math.isfinite(parsed):
                        raise EvidenceError(
                            "NONFINITE_NUMERIC_VALUE",
                            f"{path.name}:{row_number} non-finite {key}",
                        )
                rows.append(dict(row))
            return rows
    except EvidenceError:
        raise
    except FileNotFoundError as exc:
        raise EvidenceError("MISSING_REQUIRED_FILE", f"Missing {path.name}") from exc
    except (OSError, csv.Error) as exc:
        raise EvidenceError("MALFORMED_CSV", f"Cannot parse {path.name}") from exc


def _metadata_error(code: str, file_name: str, field: str, message: str):
    raise EvidenceError(code, f"{file_name}.{field}: {message}")


def _require_string(meta: Dict, key: str, file_name: str, allow_empty=False) -> str:
    if key not in meta:
        _metadata_error("METADATA_FIELD_MISSING", file_name, key, "missing")
    value = meta.get(key)
    if not isinstance(value, str) or (not allow_empty and not value):
        _metadata_error("METADATA_VALUE_INVALID", file_name, key, "expected string")
    return value


def _require_int(
    meta: Dict,
    key: str,
    file_name: str,
    minimum=None,
) -> int:
    if key not in meta:
        _metadata_error("METADATA_FIELD_MISSING", file_name, key, "missing")
    value = meta.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        _metadata_error("METADATA_VALUE_INVALID", file_name, key, "expected integer")
    if minimum is not None and value < minimum:
        _metadata_error(
            "METADATA_VALUE_INVALID",
            file_name,
            key,
            f"must be >= {minimum}",
        )
    return value


def _require_number(
    meta: Dict,
    key: str,
    file_name: str,
    minimum=None,
) -> float:
    if key not in meta:
        _metadata_error("METADATA_FIELD_MISSING", file_name, key, "missing")
    value = meta.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _metadata_error("METADATA_VALUE_INVALID", file_name, key, "expected number")
    value = float(value)
    if not math.isfinite(value):
        _metadata_error("METADATA_VALUE_INVALID", file_name, key, "must be finite")
    if minimum is not None and value < minimum:
        _metadata_error(
            "METADATA_VALUE_INVALID",
            file_name,
            key,
            f"must be >= {minimum}",
        )
    return value


def _require_string_list(meta: Dict, key: str, file_name: str) -> List[str]:
    if key not in meta:
        _metadata_error("METADATA_FIELD_MISSING", file_name, key, "missing")
    value = meta.get(key)
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        _metadata_error(
            "METADATA_VALUE_INVALID",
            file_name,
            key,
            "expected list of strings",
        )
    return value


def _validate_metadata(
    meta: Dict,
    session_id: str,
    file_name: str,
    role: str,
) -> None:
    if meta.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError(
            "UNSUPPORTED_SCHEMA_VERSION", f"Unsupported schema in {file_name}"
        )
    if meta.get("session_id") != session_id:
        raise EvidenceError(
            "SESSION_ID_MISMATCH", f"Metadata session ID mismatch in {file_name}"
        )

    for key in (
        "app_version",
        "source_commit_sha",
        "device_model",
        "android_release",
        "boot_marker",
    ):
        _require_string(meta, key, file_name)

    api_level = _require_int(meta, "api_level", file_name, minimum=1)
    start_ns = _require_int(meta, "start_elapsed_ns", file_name, minimum=0)
    end_ns = _require_int(meta, "end_elapsed_ns", file_name, minimum=0)
    if end_ns < start_ns:
        _metadata_error(
            "METADATA_VALUE_INVALID",
            file_name,
            "end_elapsed_ns",
            "must be >= start_elapsed_ns",
        )
    _require_string_list(meta, "error_codes", file_name)

    if role == "producer":
        _require_int(meta, "route_session_id", file_name, minimum=1)
        _require_int(meta, "model_seed", file_name)
        _require_string(meta, "recorder_status", file_name)

        movement_threshold = _require_number(
            meta,
            "movement_threshold_mps",
            file_name,
            minimum=0.0,
        )
        min_cadence = _require_number(
            meta,
            "cadence_min_spm",
            file_name,
            minimum=0.0,
        )
        max_cadence = _require_number(
            meta,
            "cadence_max_spm",
            file_name,
            minimum=0.0,
        )
        _require_number(meta, "cadence_intercept_spm", file_name)
        _require_number(meta, "cadence_slope_spm_per_mps", file_name)
        jitter = _require_number(
            meta,
            "jitter_fraction",
            file_name,
            minimum=0.0,
        )
        _require_int(meta, "catch_up_cap", file_name, minimum=1)

        if movement_threshold < 0.0:
            _metadata_error(
                "METADATA_VALUE_INVALID",
                file_name,
                "movement_threshold_mps",
                "must be non-negative",
            )
        if min_cadence <= 0.0 or max_cadence < min_cadence:
            _metadata_error(
                "METADATA_VALUE_INVALID",
                file_name,
                "cadence_min_spm",
                "invalid cadence bounds",
            )
        if jitter >= 1.0:
            _metadata_error(
                "METADATA_VALUE_INVALID",
                file_name,
                "jitter_fraction",
                "must be < 1.0",
            )
    elif role == "consumer":
        _require_string(meta, "finalization_status", file_name)
        _require_string(meta, "permission_state", file_name)
        _require_string_list(meta, "lifecycle_events", file_name)

        official_start = _require_int(
            meta,
            "official_start_elapsed_ns",
            file_name,
            minimum=-1,
        )
        official_end = _require_int(
            meta,
            "official_end_elapsed_ns",
            file_name,
            minimum=-1,
        )
        if (official_start == -1) != (official_end == -1):
            _metadata_error(
                "METADATA_VALUE_INVALID",
                file_name,
                "official_start_elapsed_ns",
                "official interval must be entirely unset or entirely set",
            )
        if official_start >= 0 and official_end <= official_start:
            _metadata_error(
                "METADATA_VALUE_INVALID",
                file_name,
                "official_end_elapsed_ns",
                "must be > official_start_elapsed_ns",
            )

        for key in (
            "detector_name",
            "detector_vendor",
            "counter_name",
            "counter_vendor",
        ):
            _require_string(meta, key, file_name, allow_empty=True)

        for key in (
            "detector_version",
            "detector_reporting_mode",
            "counter_version",
            "counter_reporting_mode",
        ):
            _require_int(meta, key, file_name, minimum=-1)

        for key in ("detector_wake_up", "counter_wake_up"):
            if key not in meta:
                _metadata_error("METADATA_FIELD_MISSING", file_name, key, "missing")
            if not isinstance(meta.get(key), bool):
                _metadata_error(
                    "METADATA_VALUE_INVALID",
                    file_name,
                    key,
                    "expected boolean",
                )
    else:
        raise EvidenceError(
            "INVALID_METADATA_ROLE",
            f"Unknown metadata role: {role}",
        )

    # Keep api_level referenced so type/range validation is explicit above.
    _ = api_level


def load_evidence(producer_dir, consumer_dir, session_id: str) -> EvidenceBundle:
    producer = Path(producer_dir)
    consumer = Path(consumer_dir)

    verify_manifest(producer, "producer", session_id)
    verify_manifest(consumer, "consumer", session_id)

    producer_meta = _read_json(producer / "producer_meta.json")
    consumer_meta = _read_json(consumer / "runnerprobe_meta.json")
    _validate_metadata(
        producer_meta,
        session_id,
        "producer_meta.json",
        "producer",
    )
    _validate_metadata(
        consumer_meta,
        session_id,
        "runnerprobe_meta.json",
        "consumer",
    )

    producer_boot = str(producer_meta.get("boot_marker") or "")
    consumer_boot = str(consumer_meta.get("boot_marker") or "")
    if producer_boot != consumer_boot:
        raise EvidenceError(
            "CLOCK_DOMAIN_MISMATCH",
            "Producer and consumer evidence are from different device boots",
        )

    producer_device = (
        producer_meta.get("device_model"),
        producer_meta.get("android_release"),
        producer_meta.get("api_level"),
    )
    consumer_device = (
        consumer_meta.get("device_model"),
        consumer_meta.get("android_release"),
        consumer_meta.get("api_level"),
    )
    if producer_device != consumer_device:
        raise EvidenceError(
            "DEVICE_METADATA_MISMATCH",
            "Producer and consumer evidence are not from the same Android device profile",
        )

    if producer_meta.get("source_commit_sha") != consumer_meta.get("source_commit_sha"):
        raise EvidenceError(
            "BUILD_SOURCE_MISMATCH",
            "Producer and consumer evidence were built from different source commits",
        )

    return EvidenceBundle(
        session_id=session_id,
        producer_dir=str(producer),
        consumer_dir=str(consumer),
        producer_meta=producer_meta,
        consumer_meta=consumer_meta,
        producer_location=read_csv(producer / "producer_location.csv", session_id),
        synthetic_motion=read_csv(producer / "synthetic_motion.csv", session_id),
        consumer_location=read_csv(consumer / "location_events.csv", session_id),
        step_detector=read_csv(consumer / "step_detector_events.csv", session_id),
        step_counter=read_csv(consumer / "step_counter_events.csv", session_id),
        accel_summary=read_csv(consumer / "accel_summary.csv", session_id),
        gyro_summary=read_csv(consumer / "gyro_summary.csv", session_id),
    )


def load_ground_truth(path, session_id: str, start_ns: int, end_ns: int) -> Dict:
    data = _read_json(Path(path))
    if data.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError("UNSUPPORTED_SCHEMA_VERSION", "Ground truth schema mismatch")
    if data.get("session_id") != session_id:
        raise EvidenceError("SESSION_ID_MISMATCH", "Ground truth session ID mismatch")
    if data.get("official_start_elapsed_ns") != start_ns:
        raise EvidenceError("GROUND_TRUTH_INTERVAL_MISMATCH", "Ground truth start mismatch")
    if data.get("official_end_elapsed_ns") != end_ns:
        raise EvidenceError("GROUND_TRUTH_INTERVAL_MISMATCH", "Ground truth end mismatch")
    count = data.get("count")
    if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
        raise EvidenceError("INVALID_GROUND_TRUTH", "Ground truth count must be integer > 0")
    if data.get("method") not in ("manual", "video"):
        raise EvidenceError("INVALID_GROUND_TRUTH", "Ground truth method must be manual or video")
    return data
