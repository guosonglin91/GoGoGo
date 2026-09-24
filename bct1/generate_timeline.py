#!/usr/bin/env python3
import argparse
import csv
import json
from pathlib import Path

from bct1.timeline import FORMAL_CADENCES, build_timeline, samples_in_window


def main(argv=None):
    parser = argparse.ArgumentParser(description="Generate a BCT-1 synthetic step timeline")
    parser.add_argument("--cadence", type=int, required=True)
    parser.add_argument("--duration", type=int, default=90)
    parser.add_argument("--counter-baseline", type=int, default=10_000)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)

    if args.cadence not in FORMAL_CADENCES:
        parser.error(
            "formal BCT-1 cadence must be one of: "
            + ", ".join(str(value) for value in sorted(FORMAL_CADENCES))
        )

    samples = build_timeline(
        cadence_spm=args.cadence,
        duration_s=args.duration,
        counter_baseline=args.counter_baseline,
    )

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    csv_path = output_dir / "synthetic_step_timeline.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "step_index",
            "offset_ns",
            "detector_value",
            "counter_value",
            "target_spm",
        ])
        for sample in samples:
            writer.writerow([
                sample.index,
                sample.offset_ns,
                f"{sample.detector_value:.1f}",
                sample.counter_value,
                args.cadence,
            ])

    official_start_ns = 30_000_000_000
    official_end_ns = min(args.duration, 90) * 1_000_000_000
    official_samples = (
        samples_in_window(samples, official_start_ns, official_end_ns)
        if official_end_ns > official_start_ns else []
    )

    summary = {
        "schema_version": "bct1-1",
        "target_cadence_spm": args.cadence,
        "duration_s": args.duration,
        "counter_baseline": args.counter_baseline,
        "sample_count": len(samples),
        "official_start_s": 30,
        "official_end_s": min(args.duration, 90),
        "official_window_count": len(official_samples),
    }
    (output_dir / "synthetic_step_timeline.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
