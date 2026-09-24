# V2-E Implementation Status

**Date:** 2026-09-24  
**Branch:** `route-playback-v1`  
**Automated acceptance baseline:** `b23cfc8ba27f8c06c4e9e836bb79ba8a9e9f074c`  
**GitHub Actions run:** `35983288796`  
**Automated status:** PASS  
**Physical-device status:** PENDING USER ACCEPTANCE

## 1. Status summary

V2-E automated implementation is complete through Phase 5.

The current automated baseline passed, in one workflow run:

- GoGoGo JVM unit tests;
- RunnerProbe JVM unit tests;
- V2-E Python analyzer tests;
- GoGoGo debug APK assembly;
- RunnerProbe debug APK assembly;
- APK signature verification for both applications;
- upload of both APK artifacts;
- upload of the V2-E analysis toolkit.

This does **not** constitute physical Gate-L / Gate-R / Gate-S acceptance. Those gates remain pending the stock-phone procedure in `docs/v2e/acceptance-procedure.md`.

## 2. Phase ledger

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Evidence schema, session identity, manifest integrity, finalization contract | COMPLETE |
| 1 | RunnerProbe cadence / Step Counter pure domain logic | COMPLETE |
| 2 | RunnerProbe Android recording service, lifecycle, export | COMPLETE |
| 3 | Producer location evidence, synthetic motion model, producer export | COMPLETE |
| 4 | Host analyzer, Gate-L / Gate-R / Gate-S, merged report | COMPLETE |
| 5 | CI tests, dual APK build, signatures, artifacts/toolkit | COMPLETE |
| 6 | Stock-phone physical acceptance | PENDING |

## 3. Frozen automated evidence contract

Schema:

```text
v2e-1
```

Session ID:

```text
[A-Za-z0-9_-]{1,48}
```

A session ID is single-use per application evidence store. Reusing an existing `session_<id>` directory is rejected.

Producer and consumer evidence must:

- have a finalized `evidence_manifest.json`;
- pass byte-size and SHA-256 verification;
- use the same evidence session ID;
- report the same device profile;
- report the same boot marker;
- report the same build source commit SHA.

Mixed-build, mixed-device, mixed-boot, unfinalized, malformed, or tampered evidence is rejected before gate evaluation.

## 4. Gate implementation status

### Gate-L

Implemented:

- independent RunnerProbe LocationManager observation;
- producer publication trace;
- provider-aware monotonicity;
- provider-aware path-motion measurement;
- coordinate/value validation;
- Android mock-location observation;
- producer/consumer elapsed-time correlation metrics;
- spatial error and callback-lag metrics.

No device-independent lag/spatial tolerance has been invented. Device-specific calibration remains a physical acceptance task.

### Gate-R

Implemented:

- explicit official interval;
- Step Detector timestamps;
- Step Counter baseline/delta/discontinuity;
- >=60 s official-session requirement;
- external manual/video ground truth;
- <=3% PASS, >3–5% WARN, >5% FAIL classification;
- Detector/Counter cross-check;
- lifecycle/batching evidence handling.

### Gate-S

Implemented:

- deterministic speed-driven synthetic model;
- monotonic step index/time;
- positive interval checks;
- timestamp-delta / interval consistency;
- cadence / interval mathematical consistency;
- configured cadence bounds;
- bounded jitter;
- speed / target-cadence consistency;
- producer recorder/model error propagation.

Gate-S remains producer-internal evidence and is not exposed as Android hardware sensor events.

## 5. Accepted CI artifacts

From GitHub Actions run `35983288796`:

| Artifact | Artifact ID | Size (bytes) | SHA-256 artifact digest |
| --- | ---: | ---: | --- |
| `route-playback-v1-debug-apk` | `10800159701` | 17,591,084 | `341673867b4a1cdc327ae3391f0b4f76bf9397d38f504323a84b5da0c14c6b73` |
| `runnerprobe-v2e-debug-apk` | `10800538837` | 4,191,942 | `571f3c7cf21db6c7be3f8d8fa729884f33d42dc7d56735709e3e6025aaf517c1` |
| `v2e-analysis-toolkit` | `10801211635` | 31,973 | `1b47a8329d528766ea75d8278f113e507ea657f8f98c38904ba163380aa07172` |

The workflow also verified both debug APK signatures before artifact upload.

## 6. Host-side final workflow

Finalized producer and consumer ZIPs can be analyzed directly without manual extraction:

```bash
python motion-analysis/analyze_exports.py \
  --producer-zip gogogo_producer_<session>.zip \
  --consumer-zip runnerprobe_<session>.zip \
  --session-id <session> \
  --output-dir analysis
```

For the official Gate-R session:

```bash
python motion-analysis/analyze_exports.py \
  --producer-zip gogogo_producer_<session>.zip \
  --consumer-zip runnerprobe_<session>.zip \
  --session-id <session> \
  --ground-truth-count <count> \
  --ground-truth-method manual \
  --output-dir analysis
```

The full export-to-report path has automated coverage.

## 7. Remaining physical acceptance

The user performs Phase 6 on the target stock, non-rooted Android phone.

Required final evidence:

1. both APKs from the same accepted CI run install and launch;
2. package/process separation is confirmed;
3. Gate-L producer-to-RunnerProbe location evidence is captured;
4. one >=60 s Gate-R session is captured with manual/video external ground truth;
5. Gate-S producer trace is captured;
6. foreground/background/screen-off lifecycle evidence is captured;
7. both application sessions finalize with valid manifests;
8. `analyze_exports.py` generates reproducible `session_summary.json` and `gate_report.txt`.

Until those physical results exist, V2-E status is:

```text
AUTOMATED_IMPLEMENTATION = PASS
DEVICE_HARNESS_READINESS = READY_FOR_ACCEPTANCE
PHYSICAL_GATE_ACCEPTANCE = PENDING
```
