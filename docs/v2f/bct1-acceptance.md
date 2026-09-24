# V2-F BCT-1 Acceptance Procedure

## Objective

Prove the fixed-cadence system path before introducing a speed-to-cadence model.

```text
165 spm -> Android step sensors -> RunnerProbe -> target AUT
then
150 / 165 / 180 spm matrix
```

## AOSP fallback run

Prerequisites:

- debuggable AOSP AVD;
- `V2F Virtual Step Detector` and `V2F Virtual Step Counter` visible in `dumpsys sensorservice`;
- V2fSensorInjector installed as a platform privileged app;
- RunnerProbe installed;
- target AUT installed if compatible with the AVD;
- isolated observation only, with no production result submission.

Find the ADB serial:

```bash
adb devices
```

Run the first case:

```bash
AUT_PACKAGE=<target.package> \
aosp-sensor-lab/scripts/run_case.sh \
  <ADB_SERIAL> \
  bct1_165_YYYYMMDD_HHMMSS \
  165
```

The host script validates the debuggable image and both lab sensors, emits a Step Counter baseline, waits for RunnerProbe to be recording, runs the 90-second stream, captures system evidence, records the AUT observation, and restores SensorService NORMAL via an exit trap.

After the stream, stop/finalize RunnerProbe and export:

```text
runnerprobe_<SESSION_ID>.zip
```

Analyze:

```bash
python bct1/analyze_bct1.py \
  --session-dir evidence/bct1/<SESSION_ID> \
  --runnerprobe-zip /path/to/runnerprobe_<SESSION_ID>.zip \
  --aut-observation evidence/bct1/<SESSION_ID>/aut_observation.json
```

Do not continue to the three-cadence matrix until the Android reference layer is PASS.

## Matrix

Repeat with a fresh session ID at 150, 165, and 180 spm. Formal comparison is the half-open final-minute window `[30 s, 90 s)`.

## Classifications

- `PASS`: Android reference path passes and the AUT reports cadence within ±5 spm.
- `ANDROID_PASS_AUT_NOT_OBSERVED`: RunnerProbe proves the system path while the AUT does not expose/consume it.
- `PARTIAL`: a required channel is missing, or visible AUT cadence is outside the frozen tolerance.
- `FAIL`: Android reference data is invalid, discontinuous, or outside ±2 spm.

A `NOT_OBSERVED` result is an interoperability finding, not a prompt to alter or bypass the AUT.
