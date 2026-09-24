from dataclasses import dataclass

NS_PER_MINUTE = 60_000_000_000
FORMAL_CADENCES = {150, 165, 180}


@dataclass(frozen=True)
class StepSample:
    index: int
    offset_ns: int
    detector_value: float
    counter_value: int


def build_timeline(cadence_spm: int, duration_s: int = 90,
                   counter_baseline: int = 10_000):
    if cadence_spm <= 0 or duration_s <= 0 or counter_baseline < 0:
        raise ValueError("invalid cadence timeline arguments")

    end_ns = duration_s * 1_000_000_000
    out = []
    index = 1
    while True:
        offset_ns = round(index * NS_PER_MINUTE / cadence_spm)
        if offset_ns >= end_ns:
            break
        out.append(
            StepSample(
                index=index,
                offset_ns=offset_ns,
                detector_value=1.0,
                counter_value=counter_baseline + index,
            )
        )
        index += 1
    return out


def samples_in_window(samples, start_ns: int, end_ns: int):
    if start_ns < 0 or end_ns <= start_ns:
        raise ValueError("invalid window")
    return [
        sample for sample in samples
        if start_ns <= sample.offset_ns < end_ns
    ]
