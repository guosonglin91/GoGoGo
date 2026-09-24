# V2-E Master Implementation Plan — Phase 0 to Physical Acceptance

**Date:** 2026-09-24  
**Branch:** `route-playback-v1`  
**Status:** Execution plan  
**Normative specs:**
- `docs/superpowers/specs/2026-09-24-v2-e-compatibility-harness-design.md`
- `docs/superpowers/specs/2026-09-24-v2-e-hardening-decisions.md`

## 0. Execution rule

This plan is executed in dependency order. Each task must end with:

1. automated tests for the changed contract;
2. a clean build for affected modules;
3. a Git commit with a narrow message;
4. CI green before the next physical-device acceptance milestone.

No task may create a runtime channel carrying synthetic cadence/step data from GoGoGo to RunnerProbe.

---

# BIG TASK A — Phase 0 Evidence Contract Bootstrap

## A1. Add independent RunnerProbe application shell

**Goal:** create a separately installable Android app/process with no GoGoGo runtime dependency.

**Files**
- modify `settings.gradle`
- create `runnerprobe/build.gradle`
- create `runnerprobe/src/main/AndroidManifest.xml`
- create `runnerprobe/src/main/java/com/zcshou/runnerprobe/MainActivity.java`
- create `runnerprobe/src/main/java/com/zcshou/runnerprobe/BuildContract.java`
- create minimal resources and tests

**Frozen contract**
- applicationId: `com.zcshou.runnerprobe`
- compileSdk: 32
- minSdk: 27
- targetSdk: 32
- Java: 11
- no producer binding, shared preferences, DB, broadcast cadence channel, or direct GoGoGo API use

**Acceptance**
- `:runnerprobe:testDebugUnitTest` PASS
- `:runnerprobe:assembleDebug` PASS
- independent APK exists

## A2. Freeze schema/session validation

**Goal:** all later evidence shares one machine-readable contract.

**Classes**
- `EvidenceSchema`
- `SessionId`
- `EvidenceValidationException`

**Rules**
- schema version exactly `v2e-1`
- session ID regex `[A-Za-z0-9_-]{1,48}`
- unknown schema rejected as `UNSUPPORTED_SCHEMA_VERSION`
- unsafe session IDs rejected as `INVALID_SESSION_ID`

**Acceptance**
- exact boundary tests for empty, length 49, path traversal, slash/backslash, valid max length

## A3. Add manifest integrity model

**Goal:** no partial directory can be mistaken for finalized evidence.

**Classes**
- `EvidenceFileEntry`
- `EvidenceManifest`

**Rules**
- role must be `producer` or `consumer`
- `finalized=true` required for verification
- duplicate file entries rejected
- file name must be a final payload basename, never a path traversal
- bytes and SHA-256 must match disk
- manifest verification occurs before any gate evaluation

**Error codes**
- `SESSION_NOT_FINALIZED`
- `EVIDENCE_FILE_MISSING`
- `EVIDENCE_SIZE_MISMATCH`
- `EVIDENCE_HASH_MISMATCH`
- `INVALID_MANIFEST_ROLE`
- `DUPLICATE_EVIDENCE_FILE`

**Acceptance**
- fixture file verifies
- tampered bytes fail
- truncated file fails
- missing file fails
- finalized=false fails

---

# BIG TASK B — Phase 1 RunnerProbe Domain Engine

## B1. CadenceTracker

**Input:** monotonic Step Detector timestamps.

**Output**
- 5 s diagnostic cadence
- 15 s official cadence
- state: WARMING_UP / PROVISIONAL / VALID / GATE_ELIGIBLE

**Rules**
- non-monotonic timestamps rejected
- fewer than two events in a window -> NaN, never 0.0
- cadence formula: `(n-1) * 60e9 / (last-first)`
- session step span >=10 s -> VALID
- session step span >=15 s -> GATE_ELIGIBLE

**Acceptance**
- stable 120 spm stream -> ~120 spm
- exact state transitions covered
- duplicate/decreasing timestamp rejected

## B2. StepCounterTracker

**Input:** absolute Step Counter value + sensor timestamp.

**Rules**
- first reading defines baseline
- session delta never negative
- decreasing absolute count creates discontinuity and resets baseline
- discontinuity remains observable in result/metadata

**Acceptance**
- monotonic sequence correct
- reset/discontinuity test correct

---

# BIG TASK C — Phase 2 RunnerProbe Recording Service

## C1. Permission and capability preflight

Record:
- fine/coarse location permission
- activity-recognition permission on API 29+
- GPS/NETWORK provider availability
- Step Detector presence
- Step Counter presence
- accelerometer/gyroscope presence and descriptors

Do not convert missing permission/sensor into zero values.

## C2. Armed recording state machine

```text
IDLE -> ARMING -> READY -> RECORDING -> FINALIZING -> COMPLETE / ERROR
```

READY requires:
- required permissions granted
- sensor presence resolved
- Step Counter baseline obtained when available

Official Gate-R interval starts only after READY and explicit start.

## C3. Foreground MotionRecordingService

The service owns:
- LocationManager callbacks
- SensorManager callbacks
- WakeLock only when justified by active recording
- session writers
- lifecycle/session state

Activity owns only:
- permission UX
- session ID
- start/stop controls
- live status
- export/share

## C4. Trace writer

Files:
- `location_events.csv`
- `step_detector_events.csv`
- `step_counter_events.csv`
- `accel_summary.csv`
- `gyro_summary.csv`
- `runnerprobe_meta.json`
- `evidence_manifest.json`

All live writes use `.partial`.

Final manifest is written last.

## C5. Lifecycle evidence

Record:
- foreground
- background
- screen off
- screen on
- foreground restore
- permission changes/errors

No lifecycle transition may silently terminate or overwrite an active session.

---

# BIG TASK D — Phase 3 Producer Evidence

## D1. Evidence session identity through RoutePlan

- user-visible V2-E session ID
- validated before route start
- existing callers remain compatible
- session ID carries identity only, not synthetic cadence

## D2. producer_location.csv

Write at ServiceGo publication point.

Columns:
`session_id,provider,publication_elapsed_ns,location_elapsed_ns,latitude,longitude,speed_mps,bearing_deg,accuracy_m`

This file is required for Gate-L correlation.

## D3. HumanMotionModel

Pure Java, deterministic fixed seed.

Initial model:
- movement threshold 0.5 m/s
- target cadence = clamp(100 + 15*speedMps, 100, 190)
- jitter <= ±2%
- no events below threshold
- NaN/Infinity/negative speed rejected
- no pause catch-up burst
- bounded catch-up loop

## D4. SyntheticMotionRecorder

Files:
- `synthetic_motion.csv`
- `producer_meta.json`
- `evidence_manifest.json`

Recorder failure must not stop location playback.

---

# BIG TASK E — Phase 4 Host Analyzer

## E1. Strict loader

Reject:
- unsupported schema
- missing final manifest
- hash/size mismatch
- malformed CSV/JSON
- session ID mismatch
- clock-domain mismatch

## E2. Gate-L evaluator

Evaluate:
- producer publication non-empty
- consumer observation non-empty
- monotonic timestamps
- supported providers
- coordinate movement
- mock/test flag observed
- post-session producer/consumer spatial/time correlation

Report calibration metrics without inventing device-independent numeric thresholds before stock-device calibration.

## E3. Gate-R evaluator

Official 60 s run.

Rules:
- ground truth integer > 0
- detector/counter agreement:
  `abs(detector-counter) <= max(2, ceil(0.03*detector))`
- ground truth:
  - <=3% PASS
  - >3% and <=5% WARN
  - >5% FAIL
- counter discontinuity -> FAIL
- confirmed motion + zero detector events -> FAIL
- callback batching with preserved sensor timestamps/counts -> at most WARN

## E4. Gate-S evaluator

Require:
- step index +1
- monotonic step timestamps
- positive intervals
- cadence/interval consistency
- configured cadence bounds
- jitter bounds
- non-empty moving trace

## E5. Report generation

Generate:
- `session_summary.json`
- `gate_report.txt`

Keep Gate-L / Gate-R / Gate-S independent.

---

# BIG TASK F — Phase 5 CI Contract

CI order:

1. `:app:testDebugUnitTest`
2. `:runnerprobe:testDebugUnitTest`
3. Python analyzer tests
4. `:app:assembleDebug`
5. `:runnerprobe:assembleDebug`
6. upload GoGoGo APK
7. upload RunnerProbe APK

CI green means automated integrity only.

---

# BIG TASK G — Phase 6 Physical Stock-Phone Acceptance

This phase is executed by the user on the target phone.

## G1. Install acceptance

- install current GoGoGo debug APK
- install RunnerProbe debug APK
- confirm independent package/process
- grant required permissions

## G2. Gate-L calibration

- start matched session ID
- run a short synthetic route
- export producer + consumer evidence
- verify producer/consumer traces correlate
- record device-specific callback lag/spatial error distribution

## G3. Gate-R official 60 s run

- arm RunnerProbe to READY
- start external manual/video count simultaneously with official interval
- exercise foreground/background/screen-off lifecycle if scheduled
- export evidence and ground truth
- run analyzer

## G4. Gate-S

- export producer synthetic trace
- run analyzer consistency checks

## G5. Completion criteria

V2-E is complete only when:
- CI is green;
- both APKs install;
- evidence sessions finalize with valid manifests;
- Gate-L has target-device evidence;
- Gate-R has at least one valid >=60 s external-ground-truth session;
- Gate-S trace passes consistency checks;
- lifecycle evidence is represented without silent session loss.

---

# Commit strategy

Use narrow commits:

1. `feat: bootstrap RunnerProbe and evidence contract`
2. `feat: add RunnerProbe cadence domain`
3. `feat: add RunnerProbe session recording`
4. `feat: add producer V2-E evidence`
5. `feat: add V2-E host analyzer`
6. `ci: build both V2-E applications`

If a task fails CI, fix that task before proceeding to the next dependency stage.
