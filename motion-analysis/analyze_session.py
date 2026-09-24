#!/usr/bin/env python3
import argparse
import sys
from pathlib import Path

from v2e.gate_l import evaluate_gate_l
from v2e.gate_r import evaluate_gate_r
from v2e.gate_s import evaluate_gate_s
from v2e.io import load_evidence, load_ground_truth
from v2e.models import EvidenceError, GateResult, GateStatus
from v2e.report import write_report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Analyze a finalized V2-E producer/RunnerProbe session."
    )
    parser.add_argument("--producer-dir", required=True)
    parser.add_argument("--consumer-dir", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--ground-truth")
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(argv)

    try:
        bundle = load_evidence(
            args.producer_dir,
            args.consumer_dir,
            args.session_id,
        )
        gate_l = evaluate_gate_l(
            bundle.producer_location,
            bundle.consumer_location,
            bundle.producer_meta,
            bundle.consumer_meta,
        )
        gate_s = evaluate_gate_s(
            bundle.synthetic_motion,
            bundle.producer_meta,
        )

        if args.ground_truth:
            start_ns = bundle.consumer_meta.get("official_start_elapsed_ns")
            end_ns = bundle.consumer_meta.get("official_end_elapsed_ns")
            if not isinstance(start_ns, int) or not isinstance(end_ns, int):
                raise EvidenceError(
                    "OFFICIAL_INTERVAL_MISSING",
                    "RunnerProbe metadata lacks official interval",
                )
            ground_truth = load_ground_truth(
                args.ground_truth,
                args.session_id,
                start_ns,
                end_ns,
            )
            gate_r = evaluate_gate_r(
                bundle.step_detector,
                bundle.step_counter,
                bundle.consumer_meta,
                ground_truth,
            )
        else:
            gate_r = GateResult(
                GateStatus.NOT_RUN,
                {},
                [],
                ["No external manual/video ground truth supplied."],
            )

        output = write_report(
            args.output_dir,
            bundle,
            gate_l,
            gate_r,
            gate_s,
            args.ground_truth,
        )
        print(output)
        return 0
    except EvidenceError as exc:
        print(f"{exc.code}: {exc.message}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
