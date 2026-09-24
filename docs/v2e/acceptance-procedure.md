# V2-E Stock-Phone Acceptance Procedure

**Date:** 2026-09-24  
**Scope:** independent Android compatibility harness only.  
**Producer:** GoGoGo (`com.zcshou.gogogo`)  
**Consumer:** RunnerProbe (`com.zcshou.runnerprobe`)  
**Evidence schema:** `v2e-1`

This procedure validates the V2-E harness on a stock, non-rooted Android phone. It does not use or adapt any third-party running application.

## 1. Acceptance prerequisites

Use the two APK artifacts built from the same successful GitHub Actions run:

- `route-playback-v1-debug-apk`
- `runnerprobe-v2e-debug-apk`

Confirm both packages are installed independently.

Optional ADB check:

```bash
adb shell pm list packages | grep -E 'com\.zcshou\.(gogogo|runnerprobe)'
```

Expected:

```text
package:com.zcshou.gogogo
package:com.zcshou.runnerprobe
```

For Gate-L system mock-location testing, Android Developer Options must select GoGoGo as the device's mock-location app.

RunnerProbe itself is only a consumer. It must never be selected as the mock-location provider.

## 2. Session-ID rule

Every paired producer/consumer test uses one identical evidence session ID.

Recommended format:

```text
v2e_yyyyMMdd_HHmmss
```

The identifier must match:

```text
[A-Za-z0-9_-]{1,48}
```

Do not reuse an ID from an earlier session.

## 3. Quick install/smoke acceptance

### 3.1 RunnerProbe launch

Open RunnerProbe.

Expected UI includes:

- Session ID
- Arm Session
- Begin Official Recording
- Stop & Finalize Session
- Share Finalized Evidence ZIP
- Recording state
- Cadence state
- 5 s / 15 s cadence
- Step Detector / Step Counter status
- provider / latitude / longitude / speed / bearing / isMock
- lifecycle state
- last error

The app must not display a fabricated `0.0 spm` while cadence is unavailable. It should display an explicit warm-up/no-data state.

### 3.2 GoGoGo launch

Open GoGoGo → Route Playback.

Expected V2-E controls include:

- V2-E Session ID
- route import
- speed
- loop
- start / pause / stop
- producer evidence export

Do not begin physical Gate acceptance until both apps launch without crash.

## 4. Gate-L — independent Android location compatibility

### Purpose

Validate:

```text
GoGoGo Producer
  -> Android LocationManager
  -> RunnerProbe
```

RunnerProbe must observe location only through standard Android APIs.

### Procedure

1. In RunnerProbe, enter a fresh V2-E session ID.
2. Grant location and physical-activity permissions as requested.
3. Tap **Arm Session**.
4. Wait for RunnerProbe to reach `READY`.
5. For a Gate-L-only calibration, do not need external step ground truth.
6. In GoGoGo Route Playback, enter the exact same V2-E session ID.
7. Import the independent test route.
8. Start route playback.
9. Let the route run for approximately 20–30 seconds.
10. Confirm RunnerProbe location fields update while the route moves.
11. Stop GoGoGo route playback.
12. Stop & finalize RunnerProbe.
13. Export/share the producer evidence ZIP from GoGoGo.
14. Export/share the RunnerProbe evidence ZIP.

### Required observations

RunnerProbe should show:

- provider `gps` and/or `network`;
- changing coordinates;
- finite speed/bearing when Android supplies them;
- `isMock=true` for the synthetic route stream;
- no trace-write/finalization error.

### Evidence files

Producer ZIP must contain:

```text
session_<id>/
  producer_location.csv
  synthetic_motion.csv
  producer_meta.json
  evidence_manifest.json
```

RunnerProbe ZIP must contain:

```text
session_<id>/
  location_events.csv
  step_detector_events.csv
  step_counter_events.csv
  accel_summary.csv
  gyro_summary.csv
  runnerprobe_meta.json
  evidence_manifest.json
```

A session without final `evidence_manifest.json` is incomplete and must not be accepted.

## 5. Gate-R — real motion-sensor acceptance

### Purpose

Validate real phone sensor behavior independently from synthetic motion.

Gate-R uses:

- Android Step Detector;
- Android Step Counter;
- external manual/video step count.

Android sensor counts are not the ground truth.

### Official 60-second procedure

1. Use a fresh session ID.
2. Open RunnerProbe.
3. Enter the session ID and tap **Arm Session**.
4. Wait for `READY`.
5. Prepare manual or video-assisted step counting.
6. Tap **Begin Official Recording**.
7. Start the external count at the same test cue.
8. Physically walk/run normally with the phone carried in the intended position.
9. Continue for at least 60 seconds.
10. Stop at the same cue and record the external total.
11. Tap **Stop & Finalize Session**.
12. Export the RunnerProbe ZIP.

Required official metadata:

- `official_start_elapsed_ns`
- `official_end_elapsed_ns`
- Step Detector sensor descriptor
- Step Counter sensor descriptor
- finalization status
- lifecycle events
- error codes

### Gate-R accuracy classification

Against external ground truth:

```text
relative error <= 3%       PASS
3% < relative error <= 5% WARN
relative error > 5%        FAIL
```

Detector/Counter cross-check:

```text
abs(detector_count - counter_delta)
    <= max(2, ceil(0.03 * detector_count))
```

A Step Counter reset/discontinuity is a Gate-R failure for that official session.

## 6. Lifecycle acceptance

Run at least one real-motion session through:

```text
FOREGROUND
-> BACKGROUND
-> SCREEN_OFF
-> SCREEN_ON
-> FOREGROUND_RESTORE
```

Continue real walking during the transition.

Expected:

- recording service remains active;
- event timestamps continue to be recorded or validly batched;
- session finalizes normally;
- final evidence manifest exists;
- delayed callback arrival alone is not treated as missing data.

If a session is interrupted and has no final manifest, classify it as incomplete rather than PASS.

## 7. Final integrated 60-second acceptance

For the final V2-E acceptance record, use one paired session ID and exercise all three domains at the same time while keeping their data paths independent.

Recommended sequence:

1. Generate a fresh V2-E session ID.
2. Enter that ID in RunnerProbe and tap **Arm Session**.
3. Wait for `READY`. If the Step Counter is present but has not supplied a baseline yet, take a few ordinary preliminary steps before the official interval.
4. Enter the same ID in GoGoGo Route Playback.
5. Import the independent test route, but do not start external counting yet.
6. Start GoGoGo route playback.
7. Confirm RunnerProbe begins observing the synthetic Android location stream.
8. Prepare manual/video external step counting.
9. Tap RunnerProbe **Begin Official Recording** and start the external count on the same cue.
10. Physically walk/run normally for at least 60 seconds while GoGoGo continues publishing the independent synthetic route.
11. During this interval, exercise the required lifecycle sequence if this is the lifecycle acceptance run.
12. Stop the external count and RunnerProbe official session on the same cue.
13. Stop GoGoGo route playback.
14. Export the GoGoGo producer ZIP.
15. Export the RunnerProbe consumer ZIP.
16. Run the full host-side analyzer with the external ground-truth count.

This combined procedure does **not** turn synthetic cadence into Android sensor events. Gate-L observes the synthetic location path; Gate-R observes only real physical sensor events; Gate-S evaluates only the producer's internal deterministic motion model.

The combined run is the preferred final acceptance session because it produces one matched evidence ID for Gate-L, Gate-R, and Gate-S.

## 8. Gate-S — synthetic model integrity

Gate-S is producer-internal test evidence. It is not an Android hardware sensor stream.

For a completed route session, producer evidence must contain non-empty `synthetic_motion.csv`.

Analyzer checks include:

- step index increments exactly by 1;
- step timestamps strictly increase;
- interval > 0;
- instantaneous cadence matches `60e9 / interval_ns`;
- target cadence respects configured limits;
- jitter remains within configured bound;
- target cadence is consistent with the configured speed/cadence mapping;
- producer recorder reports no model-integrity error.

## 9. Host-side analysis from exported ZIPs

The repository provides:

```text
motion-analysis/analyze_exports.py
```

### Gate-L + Gate-S only

```bash
python motion-analysis/analyze_exports.py \
  --producer-zip gogogo_producer_<session>.zip \
  --consumer-zip runnerprobe_<session>.zip \
  --session-id <session> \
  --output-dir analysis
```

Expected output:

```text
analysis/session_<session>/
  producer/
  consumer/
  session_summary.json
  gate_report.txt
```

Gate-R will be `NOT_RUN` when no external ground truth is supplied.

### Full Gate-L + Gate-R + Gate-S

After the 60-second external count:

```bash
python motion-analysis/analyze_exports.py \
  --producer-zip gogogo_producer_<session>.zip \
  --consumer-zip runnerprobe_<session>.zip \
  --session-id <session> \
  --ground-truth-count <manual_or_video_count> \
  --ground-truth-method manual \
  --output-dir analysis
```

Use `--ground-truth-method video` for a video-assisted count.

The tool derives the official interval from finalized RunnerProbe metadata and writes the canonical merged report.

## 10. Acceptance record

For each physical-device session record:

- phone model;
- Android version;
- GoGoGo commit SHA;
- RunnerProbe commit SHA;
- V2-E session ID;
- permissions granted;
- Step Detector name/vendor;
- Step Counter name/vendor;
- lifecycle sequence;
- external count method and total when Gate-R is run;
- Gate-L status;
- Gate-R status;
- Gate-S status;
- any WARN/FAIL codes.

Do not commit personal raw route traces to the public repository unless intentionally sanitized.

## 11. V2-E completion rule

Physical V2-E acceptance requires all of the following:

1. current CI run is green;
2. both APKs install and launch independently;
3. both evidence writers finalize with valid manifests;
4. Gate-L has a real target-device producer/consumer session;
5. Gate-R has at least one valid >=60 s external-ground-truth session;
6. Gate-S passes on a finalized producer trace;
7. lifecycle/background/screen-off behavior is represented in the evidence;
8. analyzer produces reproducible `session_summary.json` and `gate_report.txt`.

CI success alone is not physical compatibility acceptance.
