# V2-F BCT-1 Third-Party Cadence Baseline — Design Spec

**Date:** 2026-09-24  
**Branch:** `route-playback-v1`  
**Status:** Design frozen; awaiting user review  
**Depends on:** V2-E compatibility harness at `444426e`

## 1. Purpose

BCT-1 is the first baseline compatibility test for V2-F.

Its single question is:

> Can a controlled Android test environment generate synthetic step events with standard `TYPE_STEP_DETECTOR` and `TYPE_STEP_COUNTER` semantics, deliver them through the Android sensor stack, and have an unmodified target running application observe a realistic cadence?

The first milestone optimizes for the fastest credible end-to-end result. Teaching material and GitHub documentation are produced after the path is proven.

BCT-1 does **not** attempt to bypass a target application's authenticity checks, anti-cheat logic, integrity controls, account restrictions, or remote-service validation. The target application is treated as a black-box consumer. Testing is isolated from real accounts, rankings, grades, competitions, or production result submission.

## 2. Frozen Decisions

### 2.1 Physical-device starting point

Primary physical device:

- OPPO Find X8
- stock ColorOS
- no root
- no bootloader unlock
- no custom ROM
- no persistent `/system` or vendor modification

Allowed host-side tooling:

- Developer Options
- USB debugging
- ADB
- `dumpsys sensorservice`
- `logcat`
- read-only sensor enumeration
- reversible Android sensor test/injection modes when the stock platform exposes them
- reboot to restore normal state

The earlier APK-only preference is superseded for BCT-1 by the decision to prioritize third-party compatibility. A PC/ADB host helper is permitted when required.

### 2.2 Target application

The first AUT is the actual third-party running application the user wants to study.

BCT-1 does not require a separate known-good third-party consumer before this AUT.

RunnerProbe remains the reference observer and is used to distinguish:

- Android sensor-path success; from
- AUT-specific compatibility.

### 2.3 Initial cadence source

The first experiment does not use the speed-to-cadence model.

It uses fixed deterministic cadence inputs:

- 150 spm
- 165 spm
- 180 spm

The minimum-success experiment uses **165 spm first**.

Only after the 165 spm path succeeds does BCT-1 expand to the full 150/165/180 matrix.

### 2.4 Session duration

Each formal cadence case lasts 90 seconds.

- warm-up/stabilization: first 30 seconds
- official comparison window: final 60 seconds

This gives downstream applications enough time for cadence smoothing, batching, and display stabilization.

### 2.5 Required Android sensor semantics

Both system sensor types are required:

- `Sensor.TYPE_STEP_DETECTOR`
- `Sensor.TYPE_STEP_COUNTER`

The first milestone does not accept a one-channel-only result as full BCT-1 PASS.

Detector semantics:

- one logical detector event per synthetic step;
- event value equivalent to the Android step-detector convention;
- strictly increasing monotonic event timestamps.

Counter semantics:

- cumulative count increases by one per synthetic step;
- count remains monotonic during a boot/session;
- session comparison uses the counter delta rather than treating the absolute value as a per-session count.

## 3. Architecture

### 3.1 Desired end-to-end path

```text
Fixed Cadence Test Case
150 / 165 / 180 spm
          |
          v
Synthetic Step Timeline
          |
          v
Android System Step Path
STEP_DETECTOR + STEP_COUNTER
          |
          v
SensorService / SensorManager
          |
          +--------------------+
          |                    |
          v                    v
RunnerProbe                AUT
Reference Observer         Unmodified Target App
```

RunnerProbe and AUT must consume through normal Android sensor-facing behavior. BCT-1 must not add an AUT-specific private IPC bridge or modify the AUT package.

### 3.2 Two execution paths

BCT-1 has one fast stock-device probe and one deterministic fallback.

```text
OPPO Find X8 / stock ColorOS
          |
          v
Phase 0 Capability Gate
          |
     +----+----+
     |         |
   PASS    FAIL/PARTIAL
     |         |
     v         v
Stock test   AOSP Emulator
path         fallback
     |         |
     +----+----+
          |
          v
165 spm baseline
          |
          v
RunnerProbe + AUT
          |
          v
150 / 165 / 180 matrix
```

Primary fallback: **AOSP Android Emulator / AVD**.

Cuttlefish is not part of BCT-1's critical path. It may be introduced later if the emulator path cannot reproduce a required system/HAL behavior.

## 4. Phase 0 — OPPO Find X8 Capability Gate

### 4.1 Goal

Determine quickly whether the stock Find X8 exposes a usable, reversible Android system test path capable of delivering synthetic step events to an ordinary `SensorManager` consumer.

Phase 0 must not become a vendor-ROM reverse-engineering project.

### 4.2 Allowed observations

Collect:

- Android version/build fingerprint;
- available step-related sensors;
- sensor names/vendors/handles/reporting modes where visible;
- `dumpsys sensorservice` state;
- relevant `logcat` evidence;
- whether a documented/system-exposed sensor test/injection mode is available;
- whether the system can be restored to normal without persistent modification.

### 4.3 PASS

The Find X8 path is PASS only when RunnerProbe, through its existing standard `SensorManager` callbacks, actually observes the synthetic step stream for both detector and counter semantics.

Merely entering an injection mode or observing a service flag is not PASS.

### 4.4 PARTIAL

PARTIAL includes cases such as:

- a test mode is visible but step events do not reach RunnerProbe;
- one of detector/counter works but the other does not;
- events reach a lower system layer but are not delivered to the app;
- the vendor sensor stack exposes an incompatible or incomplete test path.

A PARTIAL result receives one minimal reproducibility check. If it remains PARTIAL, the project switches to the AOSP Emulator fallback.

### 4.5 FAIL

FAIL includes:

- no usable reversible stock test path;
- required privileges are unavailable;
- vendor HAL/service rejects the test mode;
- satisfying the path would require root, bootloader unlock, ROM flashing, persistent system modification, or target-app modification.

On FAIL, do not continue probing vendor-specific internals. Move to the fallback.

## 5. AOSP Emulator Fallback

### 5.1 Goal

Provide a controlled Android environment in which synthetic detector/counter events can traverse the Android sensor framework and reach ordinary consumer applications.

The fallback is the primary path to an unambiguous first success if the stock OPPO path fails.

### 5.2 Requirements

The fallback environment must provide:

- a reproducible AOSP emulator image;
- deterministic synthetic step-event generation;
- standard sensor enumeration visible to consumers;
- detector and counter semantics;
- standard `SensorManager` delivery;
- RunnerProbe installability;
- AUT installability when the AUT supports the emulator ABI/system environment;
- host-side evidence capture.

### 5.3 AUT compatibility caveat

If RunnerProbe receives correct system events but the target AUT does not display or use cadence, classify the result as:

```text
Android sensor path = PASS
Target AUT compatibility = NOT OBSERVED
```

Do not reinterpret that result as sensor-path failure.

The AUT may use another data source, including accelerometer-derived algorithms, health-platform APIs, vendor services, BLE accessories, or sensor fusion. BCT-1 does not reverse-engineer or bypass such choices.

## 6. Test Cases

### 6.1 Milestone M1 — 165 spm

Input:

- target cadence: 165 spm
- duration: 90 s
- official window: final 60 s

Expected synthetic interval center:

```text
60 / 165 = 0.363636... s per step
```

The event generator may use deterministic sub-millisecond rounding, but the accumulated timeline must not drift materially over the official window.

### 6.2 Milestone M2 — three-cadence matrix

After M1 succeeds:

| Case | Target cadence | Duration | Official window |
|---|---:|---:|---:|
| BCT1-150 | 150 spm | 90 s | final 60 s |
| BCT1-165 | 165 spm | 90 s | final 60 s |
| BCT1-180 | 180 spm | 90 s | final 60 s |

Every case uses a fresh evidence/session identifier.

## 7. Acceptance Criteria

### 7.1 Android reference layer

RunnerProbe must receive both channels.

For the final 60-second window:

- detector-derived cadence error: <= 2 spm from target;
- counter delta must be consistent with the detector count within the existing V2-E counter-consistency rules;
- timestamps must be monotonic;
- no unexplained counter reset or discontinuity;
- the evidence package must finalize successfully.

### 7.2 AUT layer

For full target-AUT compatibility PASS:

- the unmodified AUT visibly consumes/displays cadence or an equivalent step-rate result;
- stable-window cadence differs from target by <= 5 spm;
- no AUT code, package contents, internal storage, or private IPC path is modified to achieve the observation.

If the Android reference layer passes and AUT output is absent, classify AUT compatibility as NOT OBSERVED rather than forcing a PASS/FAIL explanation of the AUT's internals.

### 7.3 BCT-1 overall classifications

```text
PASS
  Android reference layer PASS
  AND target AUT compatibility PASS

ANDROID_PASS_AUT_NOT_OBSERVED
  Android reference layer PASS
  AND target AUT does not expose/consume the tested cadence path

PARTIAL
  system path is incomplete or only one required sensor semantic is available

FAIL
  Android reference layer cannot receive both required step channels
  in the chosen controlled environment
```

## 8. Evidence Contract

Each formal run must retain:

1. **Synthetic input ledger**
   - session id
   - target cadence
   - generated step index
   - generated monotonic timestamp
   - cumulative counter value

2. **RunnerProbe evidence ZIP**
   - detector events
   - counter events
   - cadence windows
   - metadata
   - manifest and hashes

3. **System-side evidence**
   - relevant ADB command transcript
   - `dumpsys sensorservice` excerpts
   - selected `logcat` excerpts
   - device/build fingerprint

4. **AUT observation**
   - screen recording or screenshots covering the stable official window
   - AUT package/version metadata when available

5. **Session manifest**
   - execution path: `findx8_stock` or `aosp_emulator`
   - Android build
   - test cadence
   - duration
   - official-window bounds
   - RunnerProbe result
   - AUT observation result

The evidence package must make it possible to reconstruct:

```text
what was generated
-> what Android delivered
-> what RunnerProbe observed
-> what the unmodified AUT displayed
```

## 9. Isolation and Research Boundaries

BCT-1 is an interoperability and sensor-consumption experiment.

During AUT testing:

- do not use a real account when avoidable;
- do not upload synthetic exercise results to production services;
- do not submit results to rankings, grades, competitions, rewards, or eligibility systems;
- do not tune the event stream to evade authenticity or anti-cheat detection;
- do not modify or patch the AUT;
- do not treat an AUT rejection as a prompt to bypass its controls.

Network should be disabled where the AUT can perform the local cadence observation offline. If network is required only for installation or startup, separate that setup step from the isolated sensor experiment.

## 10. Out of Scope for BCT-1

The following are explicitly deferred:

- speed-to-cadence biomechanics model;
- individualized cadence coaching;
- cadence recommendations;
- fatigue/slope/asymmetry models;
- Lab Policy Gate;
- second-round restriction-effect testing;
- Cuttlefish;
- broad multi-app compatibility matrix;
- vendor-ROM reverse engineering;
- anti-cheat or authenticity bypass;
- production data submission.

## 11. Implementation Boundaries

BCT-1 should be decomposed into the smallest independently testable units:

1. stock-device capability probe;
2. deterministic cadence timeline;
3. controlled Android system sensor path;
4. RunnerProbe reference validation;
5. target AUT observation protocol;
6. evidence bundling and report generation.

The current V2-E evidence/session machinery should be reused where it fits rather than duplicated.

## 12. Completion Definition

BCT-1 is complete when either:

### Full success

At least the 165 spm case, and then the 150/165/180 matrix, shows:

```text
Synthetic Step Timeline
-> Android system step events
-> RunnerProbe reference observation
-> unmodified target AUT cadence observation
```

within the acceptance tolerances.

### Valid compatibility finding

The Android system path is independently proven by RunnerProbe, but the target AUT does not consume or expose that path. In that case BCT-1 may close as `ANDROID_PASS_AUT_NOT_OBSERVED` with complete evidence, without attempting target-specific bypasses.

## 13. Decision Summary

Frozen design decisions:

- Q1: start with a physical phone conceptually, using the user's OPPO Find X8.
- Q2: use the actual target running app as the AUT.
- Q3: fixed cadence first, not speed-derived cadence.
- Q4: 90-second cases; final 60 seconds official.
- Q5: RunnerProbe <= 2 spm, AUT <= 5 spm.
- Q6: isolated/local testing; no production result submission.
- Q7: current device is OPPO Find X8.
- Q8: no unlock/flash/root.
- Q9-Q13: installation/testing conveniences allowed; cumulative counter semantics preserved; first round uses one actual AUT.
- Q14/Q15: stock capability probe permitted; stock limitations accepted.
- Q17: third-party compatibility takes priority over APK-only execution; PC/ADB helper allowed.
- Q18: fallback to AOSP Emulator.
- Q19: stock PASS requires RunnerProbe receiving real system-delivered synthetic events.
- Q20: ADB/read-only/reversible system test operations allowed.
- Q21: PARTIAL receives one minimal retry, then fallback.
- Q22: 165 spm first, then 150/165/180.
- Q23: detector and counter both required.
- Q24: retain full evidence chain.
- Q25: AOSP Emulator is the fallback.
- Q26: first third-party AUT is the actual target application.
