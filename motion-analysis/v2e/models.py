from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List


class GateStatus(str, Enum):
    PASS = "PASS"
    WARN = "WARN"
    FAIL = "FAIL"
    NOT_RUN = "NOT_RUN"


class EvidenceError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class GateResult:
    status: GateStatus
    metrics: Dict[str, Any] = field(default_factory=dict)
    error_codes: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "status": self.status.value,
            "metrics": self.metrics,
            "error_codes": list(self.error_codes),
            "notes": list(self.notes),
        }


@dataclass(frozen=True)
class EvidenceBundle:
    session_id: str
    producer_dir: str
    consumer_dir: str
    producer_meta: Dict[str, Any]
    consumer_meta: Dict[str, Any]
    producer_location: List[Dict[str, str]]
    synthetic_motion: List[Dict[str, str]]
    consumer_location: List[Dict[str, str]]
    step_detector: List[Dict[str, str]]
    step_counter: List[Dict[str, str]]
    accel_summary: List[Dict[str, str]]
    gyro_summary: List[Dict[str, str]]
