import json
import shutil
from pathlib import Path
from typing import Dict, Optional

from .models import EvidenceBundle, GateResult


def write_report(
    output_root,
    bundle: EvidenceBundle,
    gate_l: GateResult,
    gate_r: GateResult,
    gate_s: GateResult,
    ground_truth_path: Optional[str] = None,
) -> Path:
    root = Path(output_root) / f"session_{bundle.session_id}"
    producer_dst = root / "producer"
    consumer_dst = root / "consumer"

    if producer_dst.exists():
        shutil.rmtree(producer_dst)
    if consumer_dst.exists():
        shutil.rmtree(consumer_dst)

    root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(bundle.producer_dir, producer_dst)
    shutil.copytree(bundle.consumer_dir, consumer_dst)

    if ground_truth_path:
        shutil.copy2(ground_truth_path, root / "external_ground_truth.json")

    summary: Dict = {
        "schema_version": "v2e-1",
        "session_id": bundle.session_id,
        "gate_l": gate_l.to_dict(),
        "gate_r": gate_r.to_dict(),
        "gate_s": gate_s.to_dict(),
        "producer_meta": bundle.producer_meta,
        "runnerprobe_meta": bundle.consumer_meta,
    }

    with (root / "session_summary.json").open("w", encoding="utf-8") as handle:
        json.dump(summary, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")

    lines = [
        f"V2-E session: {bundle.session_id}",
        f"Gate-L: {gate_l.status.value}",
        f"Gate-R: {gate_r.status.value}",
        f"Gate-S: {gate_s.status.value}",
        "",
    ]
    for name, result in (("Gate-L", gate_l), ("Gate-R", gate_r), ("Gate-S", gate_s)):
        lines.append(f"[{name}]")
        if result.error_codes:
            lines.append("errors: " + ", ".join(result.error_codes))
        for key in sorted(result.metrics):
            lines.append(f"{key}: {result.metrics[key]}")
        for note in result.notes:
            lines.append("note: " + note)
        lines.append("")

    (root / "gate_report.txt").write_text(
        "\n".join(lines),
        encoding="utf-8",
    )
    return root
