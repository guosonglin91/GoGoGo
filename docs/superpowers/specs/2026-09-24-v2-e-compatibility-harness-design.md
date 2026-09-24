# V2-E Android Motion Compatibility Harness — Design Spec

**Date:** 2026-09-24  
**Branch:** `route-playback-v1`  
**Status:** Design approved for specification review  
**Scope:** GoGoGo motion-test architecture on a physical, stock, non-rooted Android phone.

## 1. Goal

V2-E turns the current route/cadence prototype into a reproducible Android motion-data compatibility harness.

The goal is not to make an existing third-party app accept synthetic step data. The goal is to verify three distinct data paths with explicit evidence:

1. **Gate-L — Location compatibility:** synthetic route output is exposed through Android location APIs and observed by an independent consumer.
2. **Gate-R — Real sensor recording:** real walking/running is observed through Android motion sensors and compared with an external ground truth.
3. **Gate-S — Synthetic motion-model validity:** internally generated cadence/step timing is validated as a deterministic test-model output.

These three domains must remain separate. A synthetic cadence value is never treated as a real `SensorManager` hardware event.

## 2. Frozen Constraints

- Test device: physical Android phone.
- OS: stock vendor Android.
- Privilege level: non-root.
- Producer and consumer are separate Android applications.
- RunnerProbe consumes only standard Android APIs.
- No Binder, shared preferences, shared database, or cadence-carrying broadcast from GoGoGo to RunnerProbe.
- No third-party app adaptation or sensor-event injection.
- Real and synthetic step measurements are separate measurement domains.

## 3. System Architecture

```text
GoGoGo Producer
├── RoutePlayer
│   └── Location Model
│       ├── latitude
│       ├── longitude
│       ├── speed
│       └── bearing
├── HumanMotionModel
│   ├── targetCadence
│   ├── instantaneousCadence
│   ├── syntheticStepTimestamp
│   └── syntheticStepCount
└── SyntheticMotionRecorder
        │
        └──────────── Gate-S

Android LocationManager
        │
        └──────────── RunnerProbe ─── Gate-L

Physical human motion
        │
Android SensorManager
├── TYPE_STEP_DETECTOR
├── TYPE_STEP_COUNTER
├── TYPE_ACCELEROMETER
└── TYPE_GYROSCOPE
        │
        └──────────── RunnerProbe ─── Gate-R
```

## 4. Repository Layout

The existing GoGoGo repository remains a single repository with two applications and shared analysis code.

```text
GoGoGo/
├── app/                         # GoGoGo Producer
├── runnerprobe/                 # independent Android Consumer APK
├── motion-analysis/             # host-side/session analysis utilities
└── docs/superpowers/specs/
```

RunnerProbe must use a different `applicationId` and process from GoGoGo.

No runtime data-sharing library may expose producer cadence or synthetic step events to RunnerProbe.

## 5. Producer Design

### 5.1 RoutePlayer

RoutePlayer remains responsible for route progression and the location state needed by the existing location test path.

Required output fields:

- monotonic timestamp
- latitude
- longitude
- speed
- bearing

### 5.2 HumanMotionModel

V2-E uses a deliberately small model.

Input:

- current route speed
- configured nominal cadence parameters

Output:

- target cadence
- instantaneous cadence
- synthetic step timestamp
- monotonically increasing synthetic step count

The first implementation shall use speed-driven cadence with small bounded timing variation. It shall not model fatigue, slope, left/right asymmetry, biomechanics, or individual gait profiles.

The model must not assume a perfectly fixed inter-step interval such as exactly 375 ms at 160 spm.

### 5.3 SyntheticMotionRecorder

Synthetic events are recorded for Gate-S and post-session comparison only. They are not published as Android hardware sensor events.

## 6. RunnerProbe Design

RunnerProbe is an independent consumer application.

### 6.1 Standard Android inputs

RunnerProbe records:

- `LocationManager.GPS_PROVIDER`
- `LocationManager.NETWORK_PROVIDER`
- `Sensor.TYPE_STEP_DETECTOR`
- `Sensor.TYPE_STEP_COUNTER`
- `Sensor.TYPE_ACCELEROMETER` for diagnostics
- `Sensor.TYPE_GYROSCOPE` for diagnostics

Accelerometer and gyroscope are diagnostic channels and do not directly determine the core PASS/FAIL result.

### 6.2 Isolation Rules

RunnerProbe must not:

- bind to a GoGoGo service for cadence data;
- read GoGoGo preferences or databases;
- receive synthetic step counts through broadcasts;
- call GoGoGo-internal APIs;
- read the producer trace during a live session.

Post-session comparison of producer and consumer traces is allowed in the analysis layer.

## 7. Cadence Computation

`TYPE_STEP_DETECTOR` is the primary real-time cadence source.

Two rolling values are maintained:

- **5 s cadence:** diagnostic only;
- **15 s cadence:** official cadence used by Gate-R.

State machine:

```text
< 4 step events
    WARMING_UP

>= 4 step events
    PROVISIONAL

window coverage >= 10 s
    VALID

window coverage >= 15 s
    GATE_ELIGIBLE
```

The UI must display the state explicitly. It must not use `0.0 spm` as a generic substitute for warm-up, sensor inactivity, or missing events.

## 8. Step Counter Cross-Check

`TYPE_STEP_COUNTER` is used for accumulated-step consistency, not primary real-time cadence.

For an official session of at least 60 seconds:

```text
abs(DetectorCount - CounterDelta) <= max(2 steps, 3%)
```

The counter must also be monotonic within the session and must not show unexplained resets.

## 9. Ground Truth

For Gate-R, Android sensors cannot be their own ground truth.

The external reference is manual counting or video-assisted counting of the same 60-second test interval.

The comparison set is:

- external ground-truth step count;
- RunnerProbe Step Detector count;
- RunnerProbe Step Counter session delta;
- official 15-second rolling cadence trace.

## 10. Lifecycle Recording

Gate-R must exercise:

```text
FOREGROUND
→ BACKGROUND
→ SCREEN_OFF
→ SCREEN_ON
→ FOREGROUND_RESTORE
```

For sensor events, RunnerProbe records both:

- `SensorEvent.timestamp`;
- app-side arrival time based on elapsed realtime.

This permits batching/delivery latency to be distinguished from missing events.

A delayed callback is not automatically a failure if event timestamps and accumulated counts remain consistent.

## 11. Test Durations

- **Quick diagnostic:** 20–30 seconds.
- **Official Gate session:** at least 60 seconds.
- **Optional endurance session:** 3–5 minutes; not required for V2-E completion.

## 12. Evidence Format

Each session produces:

```text
session_<id>/
├── location_events.csv
├── step_detector_events.csv
├── step_counter_events.csv
├── accel_summary.csv
├── gyro_summary.csv
├── synthetic_motion.csv
├── session_summary.json
└── gate_report.txt
```

The session identifier must be unique and stable across all artifacts from the same run.

CSV records must use monotonic time where available. Wall-clock time may be stored as metadata but must not be the primary interval clock.

## 13. Gate Definitions

### Gate-L — Location Compatibility

**Input:** GoGoGo synthetic route.  
**Observation:** RunnerProbe through standard Android location APIs.

PASS requires:

- location callbacks are received;
- timestamps progress monotonically;
- coordinates update with route motion;
- speed and bearing fields are present when available;
- no direct producer-to-consumer data channel is used.

### Gate-R — Real Motion Sensors

**Input:** actual physical walking/running.  
**Observation:** RunnerProbe through `SensorManager`.

Core rules:

- Step Detector supplies event timestamps for cadence.
- Step Counter supplies accumulated consistency.
- external manual/video count supplies ground truth.
- official cadence is based on the 15-second rolling window.
- lifecycle transitions must not cause unexplained session loss.

Step-count accuracy classification against external ground truth:

- **PASS:** absolute relative error <= 3%;
- **WARN:** > 3% and <= 5%;
- **FAIL:** > 5%.

Additional conditions:

- required sensor absent: FAIL for the corresponding sensor gate;
- confirmed physical walking/running with no step events for the full session: FAIL;
- batching or delayed delivery with preserved event/count consistency: WARN unless another hard criterion fails.

### Gate-S — Synthetic Motion Model

**Input:** HumanMotionModel.

PASS requires:

- synthetic step count is monotonically increasing;
- generated step timestamps are monotonic;
- cadence and step intervals are mathematically consistent;
- bounded variation stays within configured limits;
- speed/cadence relationship remains internally consistent;
- recorder output is complete for the session.

Gate-S does not claim that the synthetic trace is a real Android hardware sensor stream.

## 14. PASS / WARN / FAIL Aggregation

Each gate receives its own status. V2-E must not collapse all three domains into one opaque pass value.

`session_summary.json` stores at least:

- Gate-L status and metrics;
- Gate-R status and metrics;
- Gate-S status and metrics;
- device/sensor metadata;
- test duration;
- lifecycle transitions;
- ground-truth value when Gate-R is executed.

A final session-level summary may report the three statuses together, but no failed gate may be hidden by another passing gate.

## 15. Error Handling

The implementation must distinguish these conditions explicitly:

- permission denied;
- provider unavailable;
- sensor absent;
- sensor present but no events;
- warm-up not complete;
- callback batching;
- session interrupted;
- counter reset or discontinuity;
- trace-file write failure.

Each condition must have a machine-readable code in `session_summary.json` and a human-readable explanation in `gate_report.txt`.

## 16. Verification Strategy

Implementation shall use test-driven development for the model and evaluator.

Minimum automated coverage:

- cadence-window calculations;
- warm-up state transitions;
- Step Detector / Step Counter tolerance logic;
- monotonic timestamp/count validation;
- PASS/WARN/FAIL boundaries;
- Gate-S interval/cadence consistency;
- report generation from a fixed fixture session.

Device tests then verify Android callback behavior and lifecycle handling.

## 17. Explicit Non-Goals

V2-E will not implement:

- root or Magisk dependencies;
- custom AOSP or sensor HAL modifications;
- third-party application adaptation;
- injection of synthetic hardware sensor events into other applications;
- anti-cheat bypass behavior;
- complex biomechanical gait simulation;
- fatigue, slope, or left/right foot asymmetry.

## 18. Completion Criteria

V2-E is complete when, on the target stock non-root Android phone:

1. RunnerProbe independently records the Gate-L location stream.
2. RunnerProbe records real Step Detector and Step Counter data during physical movement.
3. Real cadence state transitions correctly from WARMING_UP to GATE_ELIGIBLE.
4. A 60-second Gate-R session produces the required event traces and external-ground-truth comparison.
5. HumanMotionModel produces a recorded Gate-S trace that passes its consistency checks.
6. All required session files are exported.
7. GateEvaluator produces reproducible PASS/WARN/FAIL reports from those traces.
8. Foreground/background/screen-off lifecycle behavior is represented in the evidence and does not silently discard the session.
