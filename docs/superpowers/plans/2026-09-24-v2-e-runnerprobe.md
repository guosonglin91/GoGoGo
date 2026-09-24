# V2-E RunnerProbe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an independent Android consumer APK that records standard Android location and real motion-sensor events, computes real cadence, survives foreground/background/screen-off transitions, and exports reproducible Gate-L/Gate-R evidence.

**Architecture:** Add a new `:runnerprobe` Android application module with applicationId `com.zcshou.runnerprobe`. A foreground `MotionRecordingService` owns LocationManager/SensorManager subscriptions and session files; the Activity only manages permissions, session controls, live status, and export. Pure-Java cadence/counter logic is isolated from Android framework code so it can be unit tested on the JVM.

**Tech Stack:** Android Gradle Plugin 8.12.1, Java 11, compileSdk 32, minSdk 27, targetSdk 32, Android LocationManager, SensorManager, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-09-24-v2-e-compatibility-harness-design.md`

## Global Constraints

- Test device is a physical, stock, non-rooted Android phone.
- RunnerProbe must be a separate APK/process from GoGoGo.
- RunnerProbe must consume only standard Android APIs.
- RunnerProbe must not use Binder, shared preferences, shared databases, cadence broadcasts, GoGoGo-internal APIs, or live producer traces to obtain synthetic cadence/steps.
- `TYPE_STEP_DETECTOR` is the real-time cadence source; `TYPE_STEP_COUNTER` is the accumulated cross-check.
- Official cadence uses a 15 s rolling window; 5 s cadence is diagnostic.
- Cadence state progression is WARMING_UP → PROVISIONAL → VALID → GATE_ELIGIBLE.
- Official Gate-R sessions are at least 60 s; quick diagnostics are 20–30 s.
- Android sensor event timestamps and app-side arrival elapsed-realtime timestamps must both be recorded.
- Accelerometer and gyroscope are diagnostic only and do not directly determine PASS/FAIL.
- Session evidence must remain readable after foreground/background/screen-off/restore transitions.
- Runtime target remains targetSdk 32; do not silently raise SDK versions in this plan.

## Review Focus

- Android 10+ activity-recognition permission denied or revoked mid-session must prevent false cadence recording and produce an explicit permission error state.
- A present Step Detector that emits no events during confirmed motion must be distinguishable from WARMING_UP and from “sensor absent.”
- Step Counter reset/discontinuity must never yield a negative or silently wrapped session delta.
- Screen-off/background lifecycle transitions must not silently terminate the session or overwrite the current trace files.
- Export/share must never expose partial files as a successfully finalized session; a write/close failure must remain visible in session status.

---

### Task 1: Add the independent RunnerProbe Android module

**Files:**
- Modify: `settings.gradle`
- Create: `runnerprobe/build.gradle`
- Create: `runnerprobe/src/main/AndroidManifest.xml`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/MainActivity.java`
- Create: `runnerprobe/src/main/res/layout/activity_main.xml`
- Create: `runnerprobe/src/main/res/values/strings.xml`
- Create: `runnerprobe/src/main/res/values/themes.xml`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/ModuleSmokeTest.java`

**Interfaces:**
- Consumes: existing root Gradle configuration and Java 11 toolchain.
- Produces: installable module `:runnerprobe` with applicationId `com.zcshou.runnerprobe`.

- [ ] **Step 1: Write the failing module smoke test**

Create `runnerprobe/src/test/java/com/zcshou/runnerprobe/ModuleSmokeTest.java`:

```java
package com.zcshou.runnerprobe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ModuleSmokeTest {
    @Test
    public void runnerProbeHasIndependentPackageContract() {
        assertEquals("com.zcshou.runnerprobe", BuildContract.APPLICATION_ID);
    }
}
```

The reference to `BuildContract` intentionally fails before the module implementation exists.

- [ ] **Step 2: Register the module and verify the test fails**

Change `settings.gradle` to:

```groovy
include ':app'
include ':runnerprobe'
```

Run:

```bash
./gradlew :runnerprobe:testDebugUnitTest
```

Expected: FAIL because `BuildContract` and/or the module implementation do not yet exist.

- [ ] **Step 3: Create the minimal module configuration and package contract**

Create `runnerprobe/build.gradle`:

```groovy
plugins {
    id "com.android.application"
}

android {
    compileSdk = 32
    namespace = "com.zcshou.runnerprobe"

    defaultConfig {
        applicationId "com.zcshou.runnerprobe"
        minSdkVersion 27
        targetSdkVersion 32
        versionCode 1
        versionName "0.1.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.5.1'
    implementation 'com.google.android.material:material:1.7.0'
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.3.0'
    androidTestImplementation 'androidx.test:runner:1.7.0'
}
```

Create `runnerprobe/src/main/java/com/zcshou/runnerprobe/BuildContract.java`:

```java
package com.zcshou.runnerprobe;

public final class BuildContract {
    public static final String APPLICATION_ID = "com.zcshou.runnerprobe";

    private BuildContract() {
    }
}
```

Create a minimal Activity/layout/theme and this manifest contract:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />

    <application
        android:label="RunnerProbe"
        android:theme="@style/Theme.RunnerProbe">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

For API 29+ later request `android.permission.ACTIVITY_RECOGNITION` at runtime using the literal permission string; do not depend on newer compileSdk constants.

- [ ] **Step 4: Run unit tests and assemble the independent APK**

Run:

```bash
./gradlew :runnerprobe:testDebugUnitTest :runnerprobe:assembleDebug
```

Expected: PASS and an APK under `runnerprobe/build/outputs/apk/debug/`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle runnerprobe
git commit -m "feat: add independent RunnerProbe app"
```

---

### Task 2: Implement pure-Java real cadence and Step Counter tracking

**Files:**
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/domain/CadenceState.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/domain/CadenceSnapshot.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/domain/CadenceTracker.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/domain/StepCounterTracker.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/domain/CadenceTrackerTest.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/domain/StepCounterTrackerTest.java`

**Interfaces:**
- Consumes: monotonic step-event timestamps in nanoseconds and absolute Step Counter values.
- Produces:
  - `CadenceSnapshot CadenceTracker.onStep(long sensorTimestampNs)`
  - `CadenceSnapshot CadenceTracker.snapshot(long nowNs)`
  - `StepCounterTracker.Result StepCounterTracker.onCounter(long sensorTimestampNs, long absoluteCount)`
  - `long StepCounterTracker.getSessionDelta()`

- [ ] **Step 1: Write failing cadence state/rolling-window tests**

Test exact states and a stable 120 spm stream:

```java
@Test
public void reachesGateEligibleAfterFifteenSecondsOfSteps() {
    CadenceTracker tracker = new CadenceTracker();
    long t = 1_000_000_000L;

    for (int i = 0; i < 32; i++) {
        tracker.onStep(t + i * 500_000_000L);
    }

    CadenceSnapshot s = tracker.snapshot(t + 15_500_000_000L);
    assertEquals(CadenceState.GATE_ELIGIBLE, s.getState());
    assertEquals(120.0, s.getCadence15sSpm(), 0.5);
    assertTrue(s.getCadence5sSpm() > 119.0);
}

@Test
public void fewerThanFourEventsRemainWarmingUp() {
    CadenceTracker tracker = new CadenceTracker();
    tracker.onStep(1_000_000_000L);
    tracker.onStep(1_500_000_000L);
    tracker.onStep(2_000_000_000L);
    assertEquals(CadenceState.WARMING_UP,
            tracker.snapshot(2_000_000_000L).getState());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsNonMonotonicStepTimestamp() {
    CadenceTracker tracker = new CadenceTracker();
    tracker.onStep(2_000_000_000L);
    tracker.onStep(1_000_000_000L);
}
```

- [ ] **Step 2: Run the cadence tests and verify they fail**

Run:

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*CadenceTrackerTest'
```

Expected: FAIL because the domain classes do not exist.

- [ ] **Step 3: Implement the cadence state machine and rolling calculation**

Use these exact semantics:

```java
public enum CadenceState {
    WARMING_UP,
    PROVISIONAL,
    VALID,
    GATE_ELIGIBLE
}
```

`CadenceTracker` must:

- reject non-monotonic timestamps;
- retain timestamps needed for the last 15 seconds;
- remember the first session step timestamp separately;
- compute cadence from inter-step intervals as `(n - 1) * 60e9 / (last - first)`;
- compute 5 s and 15 s windows independently;
- use state thresholds: fewer than 4 events → WARMING_UP; at least 4 → PROVISIONAL; session step-span at least 10 s → VALID; at least 15 s → GATE_ELIGIBLE;
- return `Double.NaN` when a cadence window has fewer than two events rather than reporting `0.0`.

- [ ] **Step 4: Write failing Step Counter discontinuity tests**

```java
@Test
public void deltaStartsAtZeroAndGrowsMonotonically() {
    StepCounterTracker tracker = new StepCounterTracker();
    tracker.onCounter(1_000L, 600L);
    assertEquals(0L, tracker.getSessionDelta());
    tracker.onCounter(2_000L, 603L);
    assertEquals(3L, tracker.getSessionDelta());
}

@Test
public void decreasingAbsoluteCounterMarksDiscontinuity() {
    StepCounterTracker tracker = new StepCounterTracker();
    tracker.onCounter(1_000L, 600L);
    StepCounterTracker.Result result = tracker.onCounter(2_000L, 590L);
    assertTrue(result.isDiscontinuity());
    assertEquals(0L, tracker.getSessionDelta());
}
```

- [ ] **Step 5: Implement Step Counter tracking**

A decreasing absolute value must set a discontinuity flag and reset the baseline to the new absolute value. Never return a negative session delta.

- [ ] **Step 6: Run both domain test classes**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*CadenceTrackerTest' --tests '*StepCounterTrackerTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add runnerprobe/src/main/java/com/zcshou/runnerprobe/domain runnerprobe/src/test/java/com/zcshou/runnerprobe/domain
git commit -m "feat: add real cadence and step counter trackers"
```

---

### Task 3: Add session trace formats and safe file writing

**Files:**
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SessionId.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SessionFileStore.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SensorSummaryAccumulator.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SessionMetadata.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/session/SessionIdTest.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/session/SessionFileStoreTest.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/session/SensorSummaryAccumulatorTest.java`

**Interfaces:**
- Consumes: typed event values from the recording service.
- Produces:
  - `String SessionId.validate(String raw)`
  - `SessionFileStore(File rootDir, String sessionId)`
  - append methods for location, detector, counter, accel summary, gyro summary
  - `SessionFileStore.CloseResult close()`
  - one-second magnitude summaries from `SensorSummaryAccumulator`;
  - `void SessionFileStore.writeMetadata(SessionMetadata metadata)` producing `runnerprobe_meta.json`.

- [ ] **Step 1: Write failing session-id and file-format tests**

Session IDs must match `[A-Za-z0-9_-]{1,48}`.

```java
@Test
public void rejectsUnsafeSessionId() {
    assertThrows(IllegalArgumentException.class,
            () -> SessionId.validate("../bad"));
}

@Test
public void acceptsStableAsciiSessionId() {
    assertEquals("v2e_20260924_001",
            SessionId.validate("v2e_20260924_001"));
}
```

Write a temporary-directory file test that asserts exact CSV headers:

```text
location_events.csv:
session_id,provider,location_elapsed_ns,arrival_elapsed_ns,wall_time_ms,latitude,longitude,speed_mps,bearing_deg,accuracy_m,is_mock

step_detector_events.csv:
session_id,sensor_timestamp_ns,arrival_elapsed_ns,event_value

step_counter_events.csv:
session_id,sensor_timestamp_ns,arrival_elapsed_ns,absolute_count,session_delta,discontinuity

accel_summary.csv / gyro_summary.csv:
session_id,window_start_elapsed_ns,window_end_elapsed_ns,event_count,mean_magnitude,min_magnitude,max_magnitude

runnerprobe_meta.json must contain:
session_id, start_elapsed_ns, end_elapsed_ns, lifecycle_events, device_model, android_release, detector_name, detector_vendor, counter_name, counter_vendor, permission_state, error_codes
```

- [ ] **Step 2: Run session tests and verify they fail**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*SessionIdTest' --tests '*SessionFileStoreTest' --tests '*SensorSummaryAccumulatorTest'
```

Expected: FAIL because the session classes do not exist.

- [ ] **Step 3: Implement SessionId, summaries, and asynchronous file writes**

Use a single-thread `ExecutorService` inside `SessionFileStore` so Android sensor callbacks never perform blocking filesystem writes directly. Write headers when files are created. `writeMetadata(SessionMetadata)` serializes `runnerprobe_meta.json` with a stable field order before close. `close()` must submit a final flush/close operation, wait up to 5 seconds, and return a result with `success` and `errorCode`.

Use these error codes:

```text
TRACE_WRITE_FAILURE
TRACE_CLOSE_TIMEOUT
TRACE_CLOSE_FAILURE
```

- [ ] **Step 4: Add the review-focus failure tests**

Add tests for:

- write attempt after close → rejected;
- duplicate close → idempotent success;
- output directory creation failure → `TRACE_WRITE_FAILURE`;
- 1-second summary with no samples → no row;
- magnitude input with NaN/Infinity → sample ignored instead of poisoning the whole summary.

- [ ] **Step 5: Run session tests**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*session*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runnerprobe/src/main/java/com/zcshou/runnerprobe/session runnerprobe/src/test/java/com/zcshou/runnerprobe/session
git commit -m "feat: add RunnerProbe session evidence store"
```

---

### Task 4: Implement the Android foreground recording service

**Files:**
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/service/MotionRecordingService.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/service/RecordingSnapshot.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/service/RecordingError.java`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/service/RecordingSnapshotBus.java`
- Modify: `runnerprobe/src/main/AndroidManifest.xml`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/service/RecordingSnapshotTest.java`

**Interfaces:**
- Consumes: Android LocationManager/SensorManager callbacks and a validated session ID.
- Produces:
  - service actions `ACTION_START_SESSION`, `ACTION_STOP_SESSION`, `ACTION_MARK_FOREGROUND`, `ACTION_MARK_BACKGROUND`;
  - immutable `RecordingSnapshot` for UI;
  - raw session files through `SessionFileStore`.

- [ ] **Step 1: Write the failing immutable snapshot/error-state unit test**

Verify that a snapshot distinguishes these states:

```text
PERMISSION_DENIED
SENSOR_ABSENT
SENSOR_PRESENT_NO_EVENTS
WARMING_UP
RECORDING
COUNTER_DISCONTINUITY
TRACE_WRITE_FAILURE
STOPPED
```

The snapshot must expose detector event count, counter delta, 5 s cadence, 15 s cadence, cadence state, last step age, latest provider, and lifecycle state.

- [ ] **Step 2: Run the service-domain test and verify it fails**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*RecordingSnapshotTest'
```

Expected: FAIL.

- [ ] **Step 3: Implement the foreground service skeleton and manifest registration**

Add to the manifest application:

```xml
<service
    android:name=".service.MotionRecordingService"
    android:exported="false"
    android:foregroundServiceType="location" />
```

`MotionRecordingService` must create a notification channel, call `startForeground()` immediately after a valid start request, then register listeners.

Use `SensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)` for Step Detector and Step Counter so `maxReportLatencyUs=0`.

Use a moderate rate such as `SENSOR_DELAY_GAME` only for accelerometer/gyroscope diagnostic summaries; do not persist every accel/gyro event.

- [ ] **Step 4: Implement permission/sensor discovery guards before registration**

Required conditions:

- API 29+ without `ACTIVITY_RECOGNITION` → `PERMISSION_DENIED`;
- no fine/coarse location → Gate-L status unavailable but sensor recording may continue;
- missing Step Detector → explicit `SENSOR_ABSENT`;
- missing Step Counter → explicit `SENSOR_ABSENT` for the counter sub-gate;
- a present sensor with zero callbacks remains `SENSOR_PRESENT_NO_EVENTS` until the first event, not `0.0 spm`.

- [ ] **Step 5: Implement sensor and location callbacks**

On Step Detector, implement `handleStepDetector(SensorEvent event)` with this sequence:

```java
long arrivalNs = SystemClock.elapsedRealtimeNanos();
CadenceSnapshot cadence = cadenceTracker.onStep(event.timestamp);
store.appendStepDetector(event.timestamp, arrivalNs, event.values[0]);
detectorEventCount += 1L;
lastDetectorArrivalNs = arrivalNs;
publishCurrentSnapshot(cadence, arrivalNs);
```

`publishCurrentSnapshot(CadenceSnapshot cadence, long nowNs)` must construct the immutable `RecordingSnapshot` from the service's current counters/provider/error/lifecycle fields and publish it through `RecordingSnapshotBus`.

On Step Counter, round the framework float value only after validating it is finite and non-negative, then update `StepCounterTracker`.

On location callback, write provider, `location.getElapsedRealtimeNanos()`, arrival elapsed realtime, wall time, coordinates, speed, bearing, accuracy, and `location.isFromMockProvider()`.

- [ ] **Step 6: Implement lifecycle markers and screen receiver**

Register a receiver while a session is active for:

```text
Intent.ACTION_SCREEN_OFF
Intent.ACTION_SCREEN_ON
```

The Activity will send foreground/background markers to the service. Store lifecycle transitions in the in-memory session state so they can later be serialized into the session summary.

- [ ] **Step 7: Implement stop/finalization semantics**

Stopping must:

1. unregister all LocationManager/SensorManager listeners;
2. unregister the screen receiver;
3. flush any pending accel/gyro one-second summaries;
4. build `SessionMetadata` from start/end elapsed time, lifecycle events, `Build.MODEL`, `Build.VERSION.RELEASE`, sensor names/vendors, permission state, and accumulated error codes, then call `store.writeMetadata(metadata)`;
5. close `SessionFileStore`;
6. preserve write/close errors in the final snapshot;
7. stop foreground mode only after evidence finalization is attempted.

- [ ] **Step 8: Run unit tests and assemble**

```bash
./gradlew :runnerprobe:testDebugUnitTest :runnerprobe:assembleDebug
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add runnerprobe/src/main/AndroidManifest.xml runnerprobe/src/main/java/com/zcshou/runnerprobe/service runnerprobe/src/test/java/com/zcshou/runnerprobe/service
git commit -m "feat: record Android motion and location events"
```

---

### Task 5: Add permission flow, live diagnostics, and session control UI

**Files:**
- Modify: `runnerprobe/src/main/java/com/zcshou/runnerprobe/MainActivity.java`
- Modify: `runnerprobe/src/main/res/layout/activity_main.xml`
- Modify: `runnerprobe/src/main/res/values/strings.xml`
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/PermissionGate.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/PermissionGateTest.java`

**Interfaces:**
- Consumes: user-entered session ID and `RecordingSnapshotBus`.
- Produces: explicit permission requests, service start/stop, foreground/background markers, and live diagnostic display.

- [ ] **Step 1: Write failing permission decision tests**

Test these exact rules:

```java
@Test
public void api29NeedsActivityRecognitionForStepSensors() {
    PermissionGate.Result r = PermissionGate.evaluate(
            29, true, true, false);
    assertTrue(r.needsActivityRecognition());
}

@Test
public void locationCanBeUnavailableWithoutBlockingSensorOnlySession() {
    PermissionGate.Result r = PermissionGate.evaluate(
            32, false, false, true);
    assertFalse(r.canRecordLocation());
    assertTrue(r.canRecordMotionSensors());
}
```

- [ ] **Step 2: Run permission tests and verify they fail**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*PermissionGateTest'
```

Expected: FAIL.

- [ ] **Step 3: Implement permission evaluation and runtime requests**

Request:

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`;
- API 29+ `android.permission.ACTIVITY_RECOGNITION`.

Do not start a motion session until the user has answered the activity-recognition permission request. If denied, show `PERMISSION_DENIED`; do not silently continue and later report `0 spm`.

- [ ] **Step 4: Implement the live UI contract**

The screen must show at minimum:

```text
Session ID
Recording state
Cadence state
Cadence 5 s
Cadence 15 s
Detector event count
Counter absolute value
Counter session delta
Detector vs Counter difference
Last step age
Step Detector present?
Step Counter present?
Latest location provider
Latitude / Longitude
Speed
Bearing
isMock
Current lifecycle state
Last error code
```

Buttons:

```text
Start Session
Stop Session
Share Session
```

When cadence is not valid, display `WARMING_UP`, `PROVISIONAL`, or `NO_EVENTS`; never display a synthetic `0.0 spm` placeholder.

- [ ] **Step 5: Wire Activity lifecycle markers without owning sensor listeners**

`onStart()` sends `ACTION_MARK_FOREGROUND`; `onStop()` sends `ACTION_MARK_BACKGROUND` only if a session is active. Sensor listeners remain owned by the foreground service.

- [ ] **Step 6: Run tests and build**

```bash
./gradlew :runnerprobe:testDebugUnitTest :runnerprobe:assembleDebug
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add runnerprobe/src/main runnerprobe/src/test/java/com/zcshou/runnerprobe/PermissionGateTest.java
git commit -m "feat: add RunnerProbe session controls and diagnostics"
```

---

### Task 6: Add deterministic session export and share support

**Files:**
- Create: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SessionExporter.java`
- Create: `runnerprobe/src/main/res/xml/file_paths.xml`
- Modify: `runnerprobe/src/main/AndroidManifest.xml`
- Modify: `runnerprobe/src/main/java/com/zcshou/runnerprobe/MainActivity.java`
- Create: `runnerprobe/src/test/java/com/zcshou/runnerprobe/session/SessionExporterTest.java`

**Interfaces:**
- Consumes: one finalized session directory.
- Produces: `runnerprobe_<sessionId>.zip` containing only finalized RunnerProbe evidence files, including `runnerprobe_meta.json`.

- [ ] **Step 1: Write the failing ZIP-content test**

Create a temp session with the five CSV files plus `runnerprobe_meta.json`; assert the ZIP contains them under a single `session_v2e_fixture_001/` root and rejects export while the session is still open.

- [ ] **Step 2: Run the exporter test and verify it fails**

```bash
./gradlew :runnerprobe:testDebugUnitTest --tests '*SessionExporterTest'
```

Expected: FAIL.

- [ ] **Step 3: Implement SessionExporter using java.util.zip**

The exporter must:

- require a closed/finalized session;
- sort filenames before zipping for deterministic order;
- include location, detector, counter, accel summary, gyro summary, and `runnerprobe_meta.json`;
- return an explicit error instead of creating a success ZIP if any required file is unreadable.

- [ ] **Step 4: Add FileProvider share support**

Manifest provider:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.files"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

Share with `Intent.ACTION_SEND`, `application/zip`, and `FLAG_GRANT_READ_URI_PERMISSION`.

- [ ] **Step 5: Run tests and build**

```bash
./gradlew :runnerprobe:testDebugUnitTest :runnerprobe:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runnerprobe/src/main runnerprobe/src/test
git commit -m "feat: export RunnerProbe evidence bundles"
```

---

### Task 7: Extend CI to test and publish both Android APKs

**Files:**
- Modify: `.github/workflows/route-playback-debug.yml`

**Interfaces:**
- Consumes: `:app` and `:runnerprobe` Gradle modules.
- Produces: CI evidence that both unit-test suites pass and separate APK artifacts are generated.

- [ ] **Step 1: Change the test step to run both modules explicitly**

Replace the generic unit-test command with:

```yaml
- name: Run Debug Unit Tests
  run: |
    ./gradlew :app:testDebugUnitTest
    ./gradlew :runnerprobe:testDebugUnitTest
```

- [ ] **Step 2: Build both APKs explicitly**

```yaml
- name: Assemble Debug APKs
  run: |
    ./gradlew :app:assembleDebug
    ./gradlew :runnerprobe:assembleDebug
```

- [ ] **Step 3: Upload separate artifacts**

Keep the existing GoGoGo upload and add:

```yaml
- name: Save RunnerProbe Debug APK
  uses: actions/upload-artifact@v4
  with:
    name: runnerprobe-v2e-debug-apk
    path: runnerprobe/build/outputs/apk/debug/*.apk
    if-no-files-found: error
```

- [ ] **Step 4: Run the same commands locally before commit**

```bash
./gradlew :app:testDebugUnitTest :runnerprobe:testDebugUnitTest
./gradlew :app:assembleDebug :runnerprobe:assembleDebug
```

Expected: all tasks PASS.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/route-playback-debug.yml
git commit -m "ci: build and test RunnerProbe"
```

---

### Task 8: Perform the first physical-device RunnerProbe acceptance test

**Files:**
- Create: `docs/v2e/runnerprobe-device-test.md`

**Interfaces:**
- Consumes: installed RunnerProbe APK on the target stock non-root Android phone.
- Produces: a recorded quick diagnostic session and a documented 60-second Gate-R procedure.

- [ ] **Step 1: Install both current debug APKs and verify package separation**

Run:

```bash
adb shell pm list packages | grep -E 'com\.zcshou\.(gogogo|runnerprobe)'
```

Expected: both `com.zcshou.gogogo` and `com.zcshou.runnerprobe` are present.

- [ ] **Step 2: Run a 20–30 second real-walking quick diagnostic**

Start a RunnerProbe session, grant requested permissions, physically walk while carrying the phone, then stop.

Expected:

- Detector event count > 0;
- Counter delta >= 0 and normally > 0;
- cadence progresses out of WARMING_UP if enough steps/time occur;
- no trace-write error.

- [ ] **Step 3: Verify background and screen-off continuity**

Start another session, move through:

```text
FOREGROUND → BACKGROUND → SCREEN_OFF → SCREEN_ON → FOREGROUND_RESTORE
```

while physically walking.

Expected: the session remains active and the final evidence is not silently truncated. Callback batching is acceptable; missing session finalization is not.

- [ ] **Step 4: Run a 60-second counted Gate-R session**

Use manual/video step counting as the external ground truth and record the exact total for the analysis plan. Do not use Android’s own detector/counter as ground truth.

- [ ] **Step 5: Share/export the finalized RunnerProbe ZIP and inspect its entries**

Expected required files:

```text
location_events.csv
step_detector_events.csv
step_counter_events.csv
accel_summary.csv
gyro_summary.csv
runnerprobe_meta.json
```

- [ ] **Step 6: Document the device procedure and observed sensor metadata**

The doc must record Android version, device model, Step Detector name/vendor, Step Counter name/vendor, permissions granted, and the exact lifecycle sequence used.

- [ ] **Step 7: Commit only the procedure, not personal raw route traces**

```bash
git add docs/v2e/runnerprobe-device-test.md
git commit -m "docs: add RunnerProbe device acceptance procedure"
```
