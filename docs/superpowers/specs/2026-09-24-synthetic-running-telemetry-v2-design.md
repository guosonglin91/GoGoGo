# V2 Synthetic Running Telemetry Design

Date: 2026-09-24  
Branch: `route-playback-v1`  
Baseline: V2-A Location Monitor at commit `e87c9719acfea2c23b247a2c5a1fb52b2c4c9f24`

## 1. Purpose

V2 extends GoGoGo from route playback and mock-location diagnostics into a deterministic synthetic sports-telemetry QA framework.

The system is intended for:
- testing software we control;
- validating data-flow, timing, consistency, pause/resume, loop/lap, and consumer reconnect behavior;
- exercising authorized sandbox/test integrations.

It is not designed to bypass integrity checks or falsify records in third-party production fitness, attendance, or grading systems.

## 2. Frozen Product Decisions

- Telemetry mode: **Synthetic Telemetry**.
- Scope: **full running telemetry**, not cadence-only display.
- Primary kinematic variable: **route-derived speed**.
- Cadence model: **profile-driven with light stochastic perturbation**.
- Step model: **discrete StepEvent generation**.
- Producer architecture: **RoutePlayer -> RunningTelemetryEngine -> TelemetrySnapshot**.
- Consumer architecture: **separate RunningTestApp APK**.
- Producer/consumer transport: **explicit Bound Service / Binder interface**.
- Test interface: **debug/test-only**, protected by signature permission.
- Pause behavior: **freeze route progress, synthetic clock, step generation, distance and counters**.
- Resume behavior: **continue from frozen state without catch-up step bursts**.
- Loop behavior: **route and lap state reset; session totals continue accumulating**.
- Internal timebase: **`SystemClock.elapsedRealtimeNanos()`**.
- Human-readable/log wall time: **`System.currentTimeMillis()`**.
- Telemetry logging: **CSV session samples plus discrete event records**.

## 3. System Architecture

```text
RoutePlayer
    |
    v
RunningTelemetryEngine
    |-- SyntheticClock
    |-- CadenceProfile
    |-- StepScheduler
    |-- DistanceIntegrator
    |-- LapController
    |-- ConsistencyValidator
    |
    v
TelemetrySnapshot
    |
    v
TelemetryService
    |
  Binder
    |
    v
RunningTestApp
    |-- Map
    |-- Live position
    |-- Speed
    |-- Cadence
    |-- Step count
    |-- Distance
    |-- Elapsed time
    |-- Lap status
    '-- Diagnostics
```

## 4. Data Model

### 4.1 TelemetrySnapshot

Each snapshot shall expose at least:

```java
long monotonicTimeNs;
long wallClockTimeMs;

double latitude;
double longitude;

float speedMps;
float bearingDeg;

float cadenceSpm;
float stepLengthM;

long totalSteps;
double totalDistanceM;

int lapIndex;
double lapDistanceM;
long lapSteps;

TelemetryState state;
```

`TelemetryState` shall support at minimum:
- `IDLE`
- `RUNNING`
- `PAUSED`
- `STOPPED`

### 4.2 Discrete Events

At minimum:
- `STEP`
- `PAUSE`
- `RESUME`
- `LAP`
- `STOP`

Each event shall include monotonic timestamp and session-relative context.

## 5. Synthetic Running Model

### 5.1 Speed

Speed is derived from route progression and remains the primary kinematic variable.

### 5.2 Cadence

Default target cadence: **160 spm**.

Recommended test envelope:
- target cadence: **120-200 spm**;
- light perturbation: approximately **+/- 2 spm**;
- ramp-rate limit: **<= 10 spm per 10 s**;
- near-zero route speed: cadence converges to **0 spm** and StepScheduler stops.

The default dynamic profile may contain:
- startup ramp;
- stable cruise;
- acceleration phase;
- deceleration phase.

The perturbation source must be seedable so test runs can be reproduced exactly.

### 5.3 Step Scheduling

Cadence is converted to discrete events.

For target cadence `c`:

```text
stepIntervalMs = 60000 / c
```

Example:

```text
160 spm -> 375 ms/step
```

`totalSteps` increases only when a StepEvent is emitted.

### 5.4 Step Length and Consistency

Step length is derived from route speed and cadence:

```text
stepLengthM = speedMps * 60 / cadenceSpm
```

when cadence is non-zero.

The model must enforce internal consistency among:
- route distance;
- speed;
- cadence;
- step length;
- total step count;
- timestamps.

## 6. Clock Semantics

All simulation calculations use `elapsedRealtimeNanos()` to guarantee monotonic behavior.

Wall-clock time is carried only for display/log correlation.

Pause behavior:
- freeze synthetic elapsed time;
- freeze route progress;
- freeze distance integrator;
- freeze StepScheduler;
- freeze lap and session counters.

Resume behavior:
- continue from the frozen state;
- never emit events for the paused interval;
- never create a catch-up burst.

## 7. Loop and Lap Semantics

When route loop is enabled and a lap completes:

Reset:
- route progress;
- lap distance;
- lap steps.

Continue accumulating:
- total distance;
- total steps;
- total elapsed time.

Increment:
- `lapIndex`.

## 8. Producer Service Boundary

Synthetic telemetry shall be exposed through a dedicated test service.

Requirements:
- explicit binding only;
- signature-level permission;
- debug/test build availability;
- disabled or absent from production release builds;
- no dependency on UI activities;
- consumer reconnect must not reset the producer session.

Recommended conceptual API:

```text
bind()
getLatestSnapshot()
registerListener()
unregisterListener()
getSessionState()
```

The consumer must not read producer internals directly.

## 9. RunningTestApp

RunningTestApp is a separate APK used solely as a controlled consumer.

Required UI:
- map;
- current position;
- speed;
- cadence;
- total steps;
- total distance;
- elapsed time;
- current lap;
- diagnostic status.

It shall not imitate any specific third-party campus-running application.

### 9.1 Diagnostics

At minimum:
- timestamp monotonicity;
- speed/cadence/step-length consistency;
- step-interval jitter;
- distance continuity;
- producer connection state;
- reconnect recovery.

Each diagnostic reports `PASS`, `WARN`, or `FAIL`.

## 10. Logging

Each test session shall produce a CSV sample log containing:

```text
timestamp_monotonic_ns
timestamp_wall_ms
latitude
longitude
speed_mps
bearing_deg
cadence_spm
step_length_m
total_steps
total_distance_m
lap_index
lap_distance_m
lap_steps
state
```

A separate event log shall contain:
- STEP
- PAUSE
- RESUME
- LAP
- STOP

Both logs must include a session identifier and deterministic random seed where applicable.

## 11. Acceptance Criteria

The V2 implementation is accepted only when the following are verified:

| Criterion | PASS threshold |
|---|---|
| Timestamp monotonicity | No backward time |
| Route-distance error | < 1% |
| Step-count error | <= 1 step |
| Mean-cadence error | < 1 spm |
| Speed/cadence/step-length consistency error | < 2% |
| Pause/resume | No drift and no catch-up events |
| Loop/lap handling | Correct lap reset + session accumulation |
| Consumer reconnect | Restores current session without reset |
| CSV telemetry log | Complete and parseable |
| Event log | Complete and ordered |

## 12. Version Breakdown

### V2-A - Location Monitor
Status: **complete**

Purpose:
- verify `ServiceGo -> Android LocationManager -> observer` system-location chain.

### V2-B - RunningTelemetryEngine Core
Implement:
- data model;
- synthetic clock;
- cadence profile;
- StepScheduler;
- distance and lap state;
- pause/resume semantics;
- deterministic seed support;
- consistency validator.

### V2-C - TelemetryService
Implement:
- debug-only bound service;
- signature permission;
- listener/snapshot API;
- reconnect behavior.

### V2-D - RunningTestApp
Implement:
- separate consumer APK;
- Binder client;
- live telemetry display;
- diagnostics;
- CSV/event export.

### V2-E - Integration Verification
Verify:
- route playback feeds telemetry;
- telemetry remains internally consistent;
- pause/resume;
- loop/lap;
- reconnect;
- acceptance thresholds.

## 13. Non-Goals

V2 explicitly does not include:
- production third-party app reverse engineering;
- anti-detection bypasses;
- integrity-check evasion;
- production record falsification;
- private or undocumented third-party protocol emulation.

## 14. Design Constraints

- Preserve current Route Playback V1 behavior.
- Preserve V2-A Location Monitor.
- Do not couple telemetry generation to Activity lifecycle.
- Keep simulation deterministic under a fixed seed.
- Prefer small, testable classes with explicit interfaces.
- No new third-party dependency unless it materially reduces complexity and is approved before implementation.
