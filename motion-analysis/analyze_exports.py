#!/usr/bin/env python3
import argparse
import json
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

import analyze_session
from create_ground_truth import build_ground_truth
from v2e.models import EvidenceError


def _safe_extract(zip_path, destination):
    zip_path = Path(zip_path)
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    destination_root = destination.resolve()

    try:
        archive = zipfile.ZipFile(zip_path, "r")
    except (OSError, zipfile.BadZipFile) as exc:
        raise EvidenceError(
            "MALFORMED_EXPORT_ZIP",
            f"Cannot open export ZIP: {zip_path}",
        ) from exc

    with archive:
        for info in archive.infolist():
            raw_name = info.filename
            if not raw_name or "\\" in raw_name:
                raise EvidenceError(
                    "UNSAFE_ZIP_ENTRY",
                    f"Unsafe ZIP entry: {raw_name}",
                )

            relative = PurePosixPath(raw_name)
            if relative.is_absolute() or ".." in relative.parts:
                raise EvidenceError(
                    "UNSAFE_ZIP_ENTRY",
                    f"Unsafe ZIP entry: {raw_name}",
                )

            mode = info.external_attr >> 16
            if mode and stat.S_ISLNK(mode):
                raise EvidenceError(
                    "UNSAFE_ZIP_ENTRY",
                    f"Symlink ZIP entry is not allowed: {raw_name}",
                )

            target = destination.joinpath(*relative.parts)
            resolved_target = target.resolve()
            if (
                resolved_target != destination_root
                and destination_root not in resolved_target.parents
            ):
                raise EvidenceError(
                    "UNSAFE_ZIP_ENTRY",
                    f"ZIP entry escapes extraction root: {raw_name}",
                )

            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                with archive.open(info, "r") as source, target.open("wb") as sink:
                    shutil.copyfileobj(source, sink)
            except OSError as exc:
                raise EvidenceError(
                    "EXPORT_ZIP_READ_FAILURE",
                    f"Cannot extract ZIP entry: {raw_name}",
                ) from exc


def _session_dir(extracted_root, session_id):
    root = Path(extracted_root)
    expected = root / f"session_{session_id}"
    if not expected.is_dir():
        raise EvidenceError(
            "EXPORT_SESSION_ROOT_MISSING",
            f"Expected session root not found: {expected.name}",
        )

    unexpected = [
        child.name
        for child in root.iterdir()
        if child.name != expected.name
    ]
    if unexpected:
        raise EvidenceError(
            "EXPORT_ZIP_LAYOUT_INVALID",
            "Export ZIP must contain exactly one session root",
        )
    return expected


def _create_ground_truth_file(consumer_dir, session_id, count, method, notes, root):
    meta_path = Path(consumer_dir) / "runnerprobe_meta.json"
    try:
        with meta_path.open("r", encoding="utf-8") as handle:
            metadata = json.load(handle)
    except FileNotFoundError as exc:
        raise EvidenceError(
            "MISSING_REQUIRED_FILE",
            "RunnerProbe metadata is missing",
        ) from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceError(
            "MALFORMED_JSON",
            "RunnerProbe metadata cannot be parsed",
        ) from exc

    if metadata.get("session_id") != session_id:
        raise EvidenceError(
            "SESSION_ID_MISMATCH",
            "RunnerProbe metadata session ID mismatch",
        )

    ground_truth = build_ground_truth(
        metadata,
        count=count,
        method=method,
        notes=notes,
    )
    output = Path(root) / "external_ground_truth.json"
    output.write_text(
        json.dumps(
            ground_truth,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    return output


def analyze_exports(
    producer_zip,
    consumer_zip,
    session_id,
    output_dir,
    ground_truth_count=None,
    ground_truth_method=None,
    ground_truth_notes="",
):
    if (ground_truth_count is None) != (ground_truth_method is None):
        raise EvidenceError(
            "GROUND_TRUTH_ARGUMENT_MISMATCH",
            "Ground-truth count and method must be supplied together",
        )

    with tempfile.TemporaryDirectory(prefix="v2e_exports_") as temp:
        temp_root = Path(temp)
        producer_extract = temp_root / "producer"
        consumer_extract = temp_root / "consumer"

        _safe_extract(producer_zip, producer_extract)
        _safe_extract(consumer_zip, consumer_extract)

        producer_dir = _session_dir(producer_extract, session_id)
        consumer_dir = _session_dir(consumer_extract, session_id)

        argv = [
            "--producer-dir",
            str(producer_dir),
            "--consumer-dir",
            str(consumer_dir),
            "--session-id",
            session_id,
            "--output-dir",
            str(output_dir),
        ]

        if ground_truth_count is not None:
            ground_truth_path = _create_ground_truth_file(
                consumer_dir,
                session_id,
                ground_truth_count,
                ground_truth_method,
                ground_truth_notes,
                temp_root,
            )
            argv.extend(["--ground-truth", str(ground_truth_path)])

        exit_code = analyze_session.main(argv)
        if exit_code != 0:
            raise EvidenceError(
                "ANALYSIS_FAILED",
                f"Analyzer exited with code {exit_code}",
            )

    return Path(output_dir) / f"session_{session_id}"


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Analyze finalized GoGoGo producer and RunnerProbe export ZIPs "
            "without manual extraction."
        )
    )
    parser.add_argument("--producer-zip", required=True)
    parser.add_argument("--consumer-zip", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--ground-truth-count", type=int)
    parser.add_argument(
        "--ground-truth-method",
        choices=("manual", "video"),
    )
    parser.add_argument("--ground-truth-notes", default="")
    args = parser.parse_args(argv)

    try:
        result = analyze_exports(
            producer_zip=args.producer_zip,
            consumer_zip=args.consumer_zip,
            session_id=args.session_id,
            output_dir=args.output_dir,
            ground_truth_count=args.ground_truth_count,
            ground_truth_method=args.ground_truth_method,
            ground_truth_notes=args.ground_truth_notes,
        )
        print(result)
        return 0
    except EvidenceError as exc:
        print(f"{exc.code}: {exc.message}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
