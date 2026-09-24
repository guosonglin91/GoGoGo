# V2-E Synthetic Motion Producer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic, speed-driven synthetic cadence/step model to GoGoGo, record its event-level trace, and expose only its own diagnostics/export without publishing synthetic steps into Android SensorManager.

**Architecture:** Keep route motion ownership in the existing `RoutePlaybackController`/ServiceGo path. Add a pure-Java `HumanMotionModel` that consumes route sample time/speed and emits synthetic step events, plus a dedicated `SyntheticMotionRecorder` owned by ServiceGo for the active route session. RouteActivity supplies a user-visible evidence session ID and may display producer diagnostics, but RunnerProbe never reads producer internals at runtime.

**Tech Stack:** Existing GoGoGo Android app, Java 11, JUnit 4.13.2, Android foreground ServiceGo, java.io/java.util.concurrent.

**Spec:** `docs/superpowers/specs/2026-09-24-v2-e-compatibility-harness-design.md`

## Global Constraints

- Physical stock non-root Android is the target environment.
- Synthetic cadence remains a test-model domain; it must not be injected into Android hardware sensor APIs.
- Route playback continues to publish standard Android location through the existing ServiceGo path.
- HumanMotionModel output must be deterministic for a fixed seed, monotonic in step index/time, and internally consistent with its cadence/interval fields.
- First version is speed-driven with small bounded timing variation only; no fatigue, slope, left/right asymmetry, or biomechanical gait model.
- Producer trace is for Gate-S and post-session comparison only.
- RunnerProbe must not receive producer cadence/steps by Binder, broadcast, shared storage, or direct API.
- Session IDs must use `[A-Za-z0-9_-]{1,48}`.
- Product code remains Java 11 and current Android SDK levels.

## Review Focus

- Route pause/stop/finish must stop synthetic step emission immediately and must not create a burst of catch-up steps on resume.
- Invalid or non-finite speed must not corrupt cadence math or emit non-monotonic timestamps.
- Large route-sample gaps must emit the correct number of elapsed synthetic steps without an unbounded loop.
- Recorder write failure must not crash or stop the location route; it must mark Gate-S evidence as failed.
- Starting a newer route session must close/reset the older motion model/recorder and must never mix rows across session IDs.

---

### Task 1: Add the pure-Java HumanMotionModel

**Files:**
- Create: `app/src/main/java/com/zcshou/motion/HumanMotionConfig.java`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticStepEvent.java`
- Create: `app/src/main/java/com/zcshou/motion/HumanMotionSnapshot.java`
- Create: `app/src/main/java/com/zcshou/motion/HumanMotionModel.java`
- Create: `app/src/test/java/com/zcshou/motion/HumanMotionModelTest.java`

**Interfaces:**
- Consumes: `long elapsedRealtimeNs`, `double speedMps`, active/paused state.
- Produces:
  - `List<SyntheticStepEvent> HumanMotionModel.onSample(long nowNs, double speedMps)`
  - `void HumanMotionModel.pause(long nowNs)`
  - `void HumanMotionModel.resume(long nowNs)`
  - `HumanMotionSnapshot HumanMotionModel.snapshot()`

- [ ] **Step 1: Write failing deterministic-output tests**

Create tests for a fixed seed/config:

```java
@Test
public void fixedSeedProducesRepeatableSteps() {
    HumanMotionConfig cfg = HumanMotionConfig.defaultConfig(1234L);
    HumanMotionModel a = new HumanMotionModel(cfg);
    HumanMotionModel b = new HumanMotionModel(cfg);

    assertEquals(
            a.onSample(20_000_000_000L, 4.0),
            b.onSample(20_000_000_000L, 4.0)
    );
}

@Test
public void stepTimesAreStrictlyMonotonic() {
    HumanMotionModel model =
            new HumanMotionModel(HumanMotionConfig.defaultConfig(7L));

    List<SyntheticStepEvent> events =
            model.onSample(30_000_000_000L, 4.0);

    long previous = Long.MIN_VALUE;
    for (SyntheticStepEvent event : events) {
        assertTrue(event.getElapsedRealtimeNs() > previous);
        previous = event.getElapsedRealtimeNs();
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*HumanMotionModelTest'
```

Expected: FAIL because the model classes do not exist.

- [ ] **Step 3: Implement the initial cadence mapping and bounded jitter**

Use this explicit V2-E test-model contract:

```text
movement threshold = 0.5 m/s
target cadence spm = clamp(100 + 15 * speedMps, 100, 190)
jitter fraction = uniformly bounded to ±2%
base interval ns = 60e9 / targetCadenceSpm
actual interval ns = baseIntervalNs * (1 + jitter)
```

`HumanMotionConfig.defaultConfig(seed)` must contain those values rather than hard-code them throughout the model.

At speeds below 0.5 m/s, emit no step and hold the model in non-moving state.

- [ ] **Step 4: Add edge-case tests before extending behavior**

Add tests for:

- `Double.NaN`, positive infinity, negative speed → no events and snapshot error `INVALID_SPEED`;
- speed below 0.5 m/s → no events;
- all emitted cadence values stay in [100, 190] spm;
- per-step jitter never exceeds ±2%;
- a 10-second gap at 4.0 m/s emits a finite number of events and completes quickly.

Cap catch-up emission to 64 events per input sample. If more are due, emit 64, set `BACKLOG_CAPPED`, and advance the schedule so the next sample does not spin indefinitely.

- [ ] **Step 5: Implement pause/resume without catch-up bursts**

On pause, remember paused state. On resume, shift the next scheduled step by the paused duration so elapsed wall time while paused never becomes synthetic steps.

- [ ] **Step 6: Run the complete model tests**

```bash
./gradlew :app:testDebugUnitTest --tests '*HumanMotionModelTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/zcshou/motion app/src/test/java/com/zcshou/motion
git commit -m "feat: add deterministic synthetic motion model"
```

---

### Task 2: Add producer evidence session identity and synthetic trace writer

**Files:**
- Create: `app/src/main/java/com/zcshou/motion/MotionSessionId.java`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticMotionRecorder.java`
- Create: `app/src/test/java/com/zcshou/motion/MotionSessionIdTest.java`
- Create: `app/src/test/java/com/zcshou/motion/SyntheticMotionRecorderTest.java`

**Interfaces:**
- Consumes: validated evidence session ID and `SyntheticStepEvent`.
- Produces: finalized `synthetic_motion.csv` plus recorder status.

- [ ] **Step 1: Write failing ID and CSV tests**

Session ID rule:

```java
assertEquals("v2e_20260924_001",
        MotionSessionId.validate("v2e_20260924_001"));
assertThrows(IllegalArgumentException.class,
        () -> MotionSessionId.validate("../escape"));
```

Required CSV header:

```text
session_id,step_index,step_elapsed_ns,speed_mps,target_cadence_spm,instantaneous_cadence_spm,interval_ns
```

- [ ] **Step 2: Run tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*MotionSessionIdTest' --tests '*SyntheticMotionRecorderTest'
```

Expected: FAIL.

- [ ] **Step 3: Implement the recorder**

Use a single-thread `ExecutorService`. Create exactly one producer directory per evidence session under app-specific external Documents storage:

```text
v2e/producer/session_<id>/synthetic_motion.csv
```

`close()` must wait up to 5 seconds for queued writes and return:

```text
SUCCESS
TRACE_WRITE_FAILURE
TRACE_CLOSE_TIMEOUT
TRACE_CLOSE_FAILURE
```

- [ ] **Step 4: Add failure/isolation tests**

Test:

- write after close is rejected;
- two sessions produce separate directories;
- a recorder failure does not throw through the route callback thread;
- duplicate close is idempotent.

- [ ] **Step 5: Run recorder tests**

```bash
./gradlew :app:testDebugUnitTest --tests '*SyntheticMotionRecorderTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zcshou/motion app/src/test/java/com/zcshou/motion
git commit -m "feat: record synthetic motion evidence"
```

---

### Task 3: Carry an evidence session ID through RoutePlan without changing the location contract

**Files:**
- Modify: `app/src/main/java/com/zcshou/route/RoutePlan.java`
- Modify: `app/src/main/java/com/zcshou/gogogo/RouteActivity.java`
- Modify: `app/src/main/res/layout/activity_route.xml`
- Modify: `app/src/test/java/com/zcshou/route/RoutePlanTest.java` or create it if absent

**Interfaces:**
- Consumes: user-entered evidence session ID.
- Produces: `String RoutePlan.getEvidenceSessionId()`.

- [ ] **Step 1: Write the failing RoutePlan evidence-ID test**

```java
@Test
public void requestCarriesValidatedEvidenceSessionId() {
    RoutePlan plan = RoutePlan.request(
            Arrays.asList(new RoutePoint(0, 0), new RoutePoint(0, 0.001)),
            false,
            false,
            3.0,
            100L,
            "v2e_20260924_001"
    );
    assertEquals("v2e_20260924_001", plan.getEvidenceSessionId());
}
```

Also assert an unsafe ID is rejected.

- [ ] **Step 2: Run the test and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*RoutePlanTest'
```

Expected: FAIL because the overload/getter does not exist.

- [ ] **Step 3: Add the RoutePlan field and preserve existing callers**

Add a six-argument `request(..., String evidenceSessionId)`. Keep the existing five-argument method for current tests/callers by delegating to a generated safe value such as `route_<monotonic session-independent token>` only in legacy paths.

Do not put cadence or synthetic-step data into RoutePlan.

- [ ] **Step 4: Add the RouteActivity session-ID field**

Add an EditText labeled `V2-E Session ID`. Generate a default value on Activity creation:

```text
v2e_yyyyMMdd_HHmmss
```

Validate before `startRouteViaService()`. Pass it only into `RoutePlan`.

- [ ] **Step 5: Run all route/model tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zcshou/route/RoutePlan.java app/src/main/java/com/zcshou/gogogo/RouteActivity.java app/src/main/res/layout/activity_route.xml app/src/test
git commit -m "feat: add V2-E evidence session identity"
```

---

### Task 4: Integrate HumanMotionModel into ServiceGo route lifecycle

**Files:**
- Modify: `app/src/main/java/com/zcshou/service/ServiceGo.java`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticMotionStatus.java`
- Create: `app/src/test/java/com/zcshou/motion/SyntheticMotionCoordinatorTest.java`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticMotionCoordinator.java`

**Interfaces:**
- Consumes: `RouteSample` and RouteSessionState.
- Produces:
  - `void SyntheticMotionCoordinator.start(long routeSessionId, String evidenceSessionId, File root)`
  - `void SyntheticMotionCoordinator.onRouteSample(RouteSample sample)`
  - `SyntheticMotionStatus snapshot()`
  - `void stop()`

- [ ] **Step 1: Write failing coordinator lifecycle tests**

Use a fake recorder and deterministic model to assert:

- PLAYING samples emit/record steps;
- PAUSED sample causes no future catch-up;
- STOPPED/FINISHED/ERROR closes the recorder;
- a stale sample from an older route session is ignored;
- a new session closes the old one before opening the new recorder.

- [ ] **Step 2: Run the tests and verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests '*SyntheticMotionCoordinatorTest'
```

Expected: FAIL.

- [ ] **Step 3: Implement the coordinator**

Keep model/recorder ownership out of `RouteActivity`. `ServiceGo.startRouteInternal()` starts the coordinator after RoutePlan validation and before controller start. If controller start fails, close the coordinator immediately.

`ServiceGo.onRouteSample()` continues its existing location arbitration first, then calls the coordinator. Recorder exceptions must be caught and converted to Gate-S status; they must not prevent `setLocationGPS/setLocationNetwork` from continuing.

- [ ] **Step 4: Wire pause/resume/terminal states**

Map RouteSessionState as:

```text
PLAYING  -> onSample
PAUSED   -> pause
STOPPED  -> stop/finalize
FINISHED -> stop/finalize
ERROR    -> stop/finalize with error status
```

- [ ] **Step 5: Run app tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zcshou/service/ServiceGo.java app/src/main/java/com/zcshou/motion app/src/test/java/com/zcshou/motion
git commit -m "feat: integrate synthetic motion with route sessions"
```

---

### Task 5: Add producer diagnostics and export without exposing a RunnerProbe channel

**Files:**
- Modify: `app/src/main/java/com/zcshou/gogogo/LocationMonitorActivity.java`
- Modify: `app/src/main/res/layout/activity_location_monitor.xml`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticMotionDiagnosticSource.java`
- Create: `app/src/main/java/com/zcshou/motion/SyntheticMotionExporter.java`
- Create: `app/src/test/java/com/zcshou/motion/SyntheticMotionExporterTest.java`

**Interfaces:**
- Consumes: producer-local `SyntheticMotionStatus` and finalized producer session directory.
- Produces: local UI diagnostics and shareable `producer_<sessionId>.zip`.

- [ ] **Step 1: Add a failing exporter-content test**

The ZIP must contain:

```text
session_<id>/synthetic_motion.csv
```

and must refuse export until the recorder is finalized.

- [ ] **Step 2: Implement producer-local diagnostics**

Add display-only fields:

```text
Synthetic Session ID
Synthetic State
Target Cadence
Instantaneous Cadence
Synthetic Step Count
Last Synthetic Step Age
Gate-S Recorder Status
```

Label the section explicitly as `Synthetic Test Model`. Do not label it as Android Step Detector/Counter.

- [ ] **Step 3: Implement ZIP export using the app's existing FileProvider**

Do not add any broadcast receiver, bound service API, or shared file location intended for RunnerProbe. Export is user-triggered and post-session.

- [ ] **Step 4: Run tests and build**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zcshou/gogogo/LocationMonitorActivity.java app/src/main/res/layout/activity_location_monitor.xml app/src/main/java/com/zcshou/motion app/src/test
git commit -m "feat: expose Gate-S diagnostics and export"
```

---

### Task 6: Run producer-side Gate-S acceptance checks

**Files:**
- Create: `docs/v2e/synthetic-motion-test.md`

**Interfaces:**
- Consumes: current GoGoGo debug APK on the target phone.
- Produces: a finalized producer ZIP and documented Gate-S acceptance procedure.

- [ ] **Step 1: Start a route at a stable nonzero test speed**

Use a valid V2-E session ID and begin route playback. Confirm system location behavior remains unchanged from the current route implementation.

- [ ] **Step 2: Observe producer diagnostics for at least 20–30 seconds**

Expected:

- synthetic step count increases monotonically;
- step age resets on each event;
- target/instantaneous cadence remain within configured bounds;
- no recorder error.

- [ ] **Step 3: Pause for at least 5 seconds, then resume**

Expected: no burst of accumulated steps immediately after resume.

- [ ] **Step 4: Stop the route and export the producer ZIP**

Verify `synthetic_motion.csv` timestamps and step indices are strictly monotonic.

- [ ] **Step 5: Document the procedure and commit only the procedure**

```bash
git add docs/v2e/synthetic-motion-test.md
git commit -m "docs: add Gate-S producer acceptance procedure"
```
