#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from typing import Dict

from v2e.models import EvidenceError

SCHEMA_VERSION = "v2e-1"


def build_ground_truth(
    runnerprobe_meta: Dict,
    count: int,
    method: str,
    notes: str = "",
) -> Dict:
    if runnerprobe_meta.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceError(
            "UNSUPPORTED_SCHEMA_VERSION",
            "RunnerProbe metadata schema is not v2e-1",
        )

    session_id = runnerprobe_meta.get("session_id")
    start_ns = runnerprobe_meta.get("official_start_elapsed_ns")
    end_ns = runnerprobe_meta.get("official_end_elapsed_ns")

    if not isinstance(session_id, str) or not session_id:
        raise EvidenceError("SESSION_ID_MISSING", "RunnerProbe session ID is missing")
    if not isinstance(start_ns, int) or not isinstance(end_ns, int) or end_ns <= start_ns:
        raise EvidenceError(
            "OFFICIAL_INTERVAL_INVALID",
            "RunnerProbe official interval is missing or invalid",
        )
    if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
        raise EvidenceError("INVALID_GROUND_TRUTH", "Step count must be integer > 0")
    if method not in ("manual", "video"):
        raise EvidenceError(
            "INVALID_GROUND_TRUTH",
            "Ground-truth method must be manual or video",
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "session_id": session_id,
        "count": count,
        "method": method,
        "official_start_elapsed_ns": start_ns,
        "official_end_elapsed_ns": end_ns,
        "notes": notes or "",
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Create external_ground_truth.json from finalized RunnerProbe metadata."
    )
    parser.add_argument("--runnerprobe-meta", required=True)
    parser.add_argument("--count", type=int, required=True)
    parser.add_argument("--method", choices=("manual", "video"), required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    try:
        with Path(args.runnerprobe_meta).open("r", encoding="utf-8") as handle:
            metadata = json.load(handle)
        result = build_ground_truth(
            metadata,
            args.count,
            args.method,
            args.notes,
        )
    except (OSError, json.JSONDecodeError) as exc:
        print(f"MALFORMED_JSON: {exc}")
        return 2
    except EvidenceError as exc:
        print(f"{exc.code}: {exc.message}")
        return 2

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
