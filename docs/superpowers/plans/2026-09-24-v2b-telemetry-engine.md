# V2-B Synthetic Telemetry Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Add a deterministic, unit-testable synthetic running telemetry engine that consumes the existing route-playback samples and produces internally consistent cadence, step, distance, lap, and event data without changing existing route or mock-location behavior.

**Architecture:** Keep the existing RouteMotionEngine / RoutePlaybackController / ServiceGo path as the authoritative route and speed source. Add a shared telemetry data model, a pure-Java RunningTelemetryEngine, and a process-local TelemetryStore. ServiceGo feeds every accepted RouteSample into the telemetry engine after the existing LocationStateArbiter accepts it.

**Tech Stack:** Java 11, Android Gradle Plugin 8.12.1, compileSdk 32, minSdk 27, JUnit 4.13.2, existing Gradle wrapper.

**Spec:** docs/superpowers/specs/2026-09-24-synthetic-running-telemetry-v2-design.md

## Global Constraints

- Telemetry mode: Synthetic Telemetry.
- Scope: full running telemetry, not cadence-only display.
- Route-derived speed is the primary kinematic variable.
- Default target cadence is 160 spm; allowed target envelope is 120-200 spm.
- Base cadence ramp rate is <= 10 spm per 10 s; perturbation is bounded to approximately +/-2 spm.
- Near-zero route speed stops synthetic steps and reports cadence 0.
- Step generation is discrete; totalSteps changes only when StepEvent is emitted.
- Internal calculations use monotonic elapsed time; wall time is display/log correlation only.
- Pause freezes route-related telemetry progress and never creates catch-up steps on resume.
- Loop resets lap-local counters while session totals continue.
- Preserve current Route Playback V1 behavior and V2-A Location Monitor behavior.
- Do not couple telemetry generation to an Activity lifecycle.
- Keep simulation deterministic under a fixed seed.
- No new third-party dependency.

## Review Focus

1. **Dropped or delayed route ticks:** a 1-2 second gap while RUNNING must not create duplicate or missing steps beyond the <=1-step acceptance tolerance. Task 3 adds an explicit long-gap test.
2. **Pause/resume boundaries:** elapsed wall time during PAUSED must not advance synthetic active time, distance-derived step phase, or step count. Task 4 adds pause/resume tests.
3. **Near-zero speed:** speeds below 0.2 m/s must report 0 spm and emit no StepEvent. Task 2 and Task 4 test this.
4. **Lap crossings:** lapDistance and lapSteps reset at a lap boundary while totalDistance and totalSteps remain monotonic. Task 4 tests a loop crossing.
5. **Determinism:** identical route samples + identical seed must produce byte-for-byte equivalent telemetry values/events. Task 4 adds a replay determinism test.

---

### Task 1: Add the shared telemetry data model

**Files:**
- Modify: settings.gradle
- Create: telemetry-api/build.gradle
- Create: telemetry-api/src/main/AndroidManifest.xml
- Create: telemetry-api/src/main/java/com/zcshou/telemetry/api/TelemetryState.java
- Create: telemetry-api/src/main/java/com/zcshou/telemetry/api/TelemetryEventType.java
- Create: telemetry-api/src/main/java/com/zcshou/telemetry/api/TelemetrySnapshot.java
- Create: telemetry-api/src/main/java/com/zcshou/telemetry/api/TelemetryEvent.java
- Create: telemetry-api/src/test/java/com/zcshou/telemetry/api/TelemetrySnapshotTest.java
- Modify: app/build.gradle

**Interfaces:**
- Consumes: none.
- Produces:
  - enum TelemetryState { IDLE, RUNNING, PAUSED, STOPPED }
  - enum TelemetryEventType { STEP, PAUSE, RESUME, LAP, STOP }
  - immutable TelemetrySnapshot with Builder and getters.
  - immutable TelemetryEvent with constructor/getters.
  - app module dependency on project(':telemetry-api').

- [ ] **Step 1: Write the failing model test**

Create telemetry-api/src/test/java/com/zcshou/telemetry/api/TelemetrySnapshotTest.java:

~~~java
package com.zcshou.telemetry.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TelemetrySnapshotTest {
    @Test
    public void builderPreservesCoreFields() {
        TelemetrySnapshot snapshot = new TelemetrySnapshot.Builder(7L)
                .state(TelemetryState.RUNNING)
                .monotonicTimeNs(1_000_000_000L)
                .wallClockTimeMs(1234L)
                .latitude(43.0)
                .longitude(87.0)
                .speedMps(3.0f)
                .bearingDeg(90.0f)
                .cadenceSpm(160.0f)
                .stepLengthM(1.125f)
                .totalSteps(10L)
                .totalDistanceM(11.25)
                .lapIndex(2)
                .lapDistanceM(1.25)
                .lapSteps(1L)
                .routeLengthM(10.0)
                .targetCadenceSpm(160.0f)
                .build();

        assertEquals(7L, snapshot.getSessionId());
        assertEquals(TelemetryState.RUNNING, snapshot.getState());
        assertEquals(160.0f, snapshot.getCadenceSpm(), 0.0001f);
        assertEquals(10L, snapshot.getTotalSteps());
        assertEquals(2, snapshot.getLapIndex());
        assertEquals(1L, snapshot.getLapSteps());
    }
}
~~~

- [ ] **Step 2: Run the test and confirm RED**

Run:

~~~bash
./gradlew :telemetry-api:testDebugUnitTest
~~~

Expected: Gradle fails because telemetry-api is not yet included / model classes do not exist.

- [ ] **Step 3: Add the module and exact model surface**

Change settings.gradle to:

~~~groovy
include ':app'
include ':telemetry-api'
~~~

Create telemetry-api/build.gradle:

~~~groovy
plugins {
    id "com.android.library"
}

android {
    namespace = 'com.zcshou.telemetry.api'
    compileSdk = 32

    defaultConfig {
        minSdkVersion 27
        targetSdkVersion 32
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation 'junit:junit:4.13.2'
}
~~~

Create telemetry-api/src/main/AndroidManifest.xml:

~~~xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
~~~

Create TelemetryState.java and TelemetryEventType.java exactly with the enum values listed in **Interfaces**.

Implement TelemetrySnapshot as an immutable final class with these fields and matching getters:

~~~java
long sessionId;
TelemetryState state;
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
double routeLengthM;
float targetCadenceSpm;
~~~

Implement nested Builder(long sessionId) with fluent setters named exactly after the fields and build().

Implement TelemetryEvent with:

~~~java
public TelemetryEvent(
        long sessionId,
        TelemetryEventType type,
        long monotonicTimeNs,
        long wallClockTimeMs,
        int lapIndex,
        long totalSteps
)
~~~

and getters for all six values.

Finally add to app/build.gradle dependencies:

~~~groovy
implementation project(':telemetry-api')
~~~

- [ ] **Step 4: Run model tests**

Run:

~~~bash
./gradlew :telemetry-api:testDebugUnitTest :app:testDebugUnitTest
~~~

Expected: PASS; all existing route tests remain green.

- [ ] **Step 5: Commit**

~~~bash
git add settings.gradle app/build.gradle telemetry-api
git commit -m "feat: add shared telemetry data model"
~~~

---

### Task 2: Add deterministic cadence profile generation

**Files:**
- Create: app/src/main/java/com/zcshou/telemetry/TelemetryConfig.java
- Create: app/src/main/java/com/zcshou/telemetry/DeterministicNoise.java
- Create: app/src/main/java/com/zcshou/telemetry/CadenceProfile.java
- Create: app/src/test/java/com/zcshou/telemetry/CadenceProfileTest.java

**Interfaces:**
- Consumes: no Android API.
- Produces:
  - TelemetryConfig.defaultConfig()
  - double CadenceProfile.sample(double activeSeconds, double speedMps)
  - deterministic perturbation keyed by seed and whole-second bucket.

- [ ] **Step 1: Write cadence tests first**

Create CadenceProfileTest.java:

~~~java
package com.zcshou.telemetry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CadenceProfileTest {
    @Test
    public void defaultProfileIsDeterministicAndBounded() {
        TelemetryConfig config = TelemetryConfig.defaultConfig();
        CadenceProfile a = new CadenceProfile(config);
        CadenceProfile b = new CadenceProfile(config);

        for (int second = 0; second <= 120; second++) {
            double ca = a.sample(second, 3.0);
            double cb = b.sample(second, 3.0);
            assertEquals(ca, cb, 0.0);
            assertTrue(ca >= 120.0);
            assertTrue(ca <= 200.0);
        }
    }

    @Test
    public void nearZeroSpeedProducesZeroCadence() {
        CadenceProfile profile = new CadenceProfile(TelemetryConfig.defaultConfig());
        assertEquals(0.0, profile.sample(20.0, 0.0), 0.0);
        assertEquals(0.0, profile.sample(20.0, 0.19), 0.0);
    }

    @Test
    public void baseRampChangesNoFasterThanOneSpmPerSecond() {
        TelemetryConfig config = TelemetryConfig.defaultConfig();
        CadenceProfile profile = new CadenceProfile(config);

        double previous = profile.sampleBaseCadence(0.0);
        for (int second = 1; second <= 30; second++) {
            double current = profile.sampleBaseCadence(second);
            assertTrue(Math.abs(current - previous) <= 1.000001);
            previous = current;
        }
    }
}
~~~

- [ ] **Step 2: Run the cadence tests and confirm RED**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.CadenceProfileTest
~~~

Expected: FAIL because TelemetryConfig/CadenceProfile do not exist.

- [ ] **Step 3: Implement configuration and deterministic noise**

TelemetryConfig.defaultConfig() must return these exact defaults:

~~~java
targetCadenceSpm = 160.0
minimumCadenceSpm = 120.0
maximumCadenceSpm = 200.0
startupCadenceSpm = 145.0
baseRampSpmPerSecond = 1.0
noiseAmplitudeSpm = 2.0
minimumMovingSpeedMps = 0.2
seed = 20260924L
~~~

TelemetryConfig must validate:
- target in [120, 200];
- noiseAmplitudeSpm >= 0;
- baseRampSpmPerSecond > 0;
- minimumMovingSpeedMps >= 0.

DeterministicNoise.sample(long seed, long bucket) returns a stable value in [-1.0, +1.0]. Use java.util.Random with a bucket-mixed seed so the result depends on the bucket, not on call order:

~~~java
static double sample(long seed, long bucket) {
    long mixed = seed ^ (bucket * 0x9E3779B97F4A7C15L);
    return new java.util.Random(mixed).nextDouble() * 2.0 - 1.0;
}
~~~

CadenceProfile.sampleBaseCadence(activeSeconds) ramps from startupCadenceSpm toward targetCadenceSpm by at most baseRampSpmPerSecond * activeSeconds.

CadenceProfile.sample(activeSeconds, speedMps):
1. returns 0 when speedMps < minimumMovingSpeedMps;
2. computes base cadence;
3. adds deterministic whole-second-bucket perturbation;
4. clamps to [minimumCadenceSpm, maximumCadenceSpm].

- [ ] **Step 4: Run cadence tests**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.CadenceProfileTest
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add app/src/main/java/com/zcshou/telemetry app/src/test/java/com/zcshou/telemetry
git commit -m "feat: add deterministic cadence profile"
~~~

---

### Task 3: Add phase-accumulating discrete StepScheduler

**Files:**
- Create: app/src/main/java/com/zcshou/telemetry/StepScheduler.java
- Create: app/src/main/java/com/zcshou/telemetry/StepEmission.java
- Create: app/src/test/java/com/zcshou/telemetry/StepSchedulerTest.java

**Interfaces:**
- Consumes: elapsed active time delta and average cadence for that interval.
- Produces:
  - StepEmission StepScheduler.advance(long deltaActiveNs, double cadenceSpm, long intervalEndMonotonicNs, long intervalEndWallMs)
  - StepEmission.getStepCount()
  - StepEmission.getMonotonicEventTimesNs()
  - StepScheduler.getTotalSteps()
  - StepScheduler.reset()

- [ ] **Step 1: Write scheduler tests**

~~~java
package com.zcshou.telemetry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepSchedulerTest {
    @Test
    public void emitsAbout160StepsInOneMinuteAt160Spm() {
        StepScheduler scheduler = new StepScheduler();
        long nowNs = 0L;

        int emitted = 0;
        for (int i = 0; i < 600; i++) {
            nowNs += 100_000_000L;
            emitted += scheduler.advance(
                    100_000_000L, 160.0, nowNs, i * 100L
            ).getStepCount();
        }

        assertTrue(Math.abs(emitted - 160) <= 1);
        assertEquals(emitted, scheduler.getTotalSteps());
    }

    @Test
    public void noActiveDeltaMeansNoCatchUpSteps() {
        StepScheduler scheduler = new StepScheduler();
        scheduler.advance(1_000_000_000L, 160.0, 1_000_000_000L, 1000L);
        long before = scheduler.getTotalSteps();

        StepEmission paused = scheduler.advance(
                0L, 160.0, 11_000_000_000L, 11000L
        );

        assertEquals(0, paused.getStepCount());
        assertEquals(before, scheduler.getTotalSteps());
    }

    @Test
    public void twoSecondTickGapStillUsesPhaseAccumulator() {
        StepScheduler scheduler = new StepScheduler();
        StepEmission emission = scheduler.advance(
                2_000_000_000L, 160.0, 2_000_000_000L, 2000L
        );

        assertTrue(Math.abs(emission.getStepCount() - 5) <= 1);
        assertEquals(emission.getStepCount(), emission.getMonotonicEventTimesNs().size());
    }
}
~~~

- [ ] **Step 2: Run scheduler tests and confirm RED**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.StepSchedulerTest
~~~

Expected: FAIL because StepScheduler/StepEmission do not exist.

- [ ] **Step 3: Implement the scheduler**

Use a fractional step phase accumulator:

~~~java
phase += cadenceSpm / 60.0 * (deltaActiveNs / 1_000_000_000.0);
int count = (int) Math.floor(phase);
phase -= count;
totalSteps += count;
~~~

For event timestamps, spread emitted steps monotonically inside the active interval rather than stamping every event at the interval end. For count > 0:

~~~java
long startNs = intervalEndMonotonicNs - deltaActiveNs;
for (int i = 1; i <= count; i++) {
    long eventNs = startNs + Math.round(
            (double) deltaActiveNs * i / (count + 1)
    );
    eventTimes.add(eventNs);
}
~~~

Rules:
- deltaActiveNs <= 0 -> zero events and no phase change;
- cadenceSpm <= 0 -> zero events and no phase change;
- reset() sets phase and totalSteps to zero;
- emitted event timestamps must be strictly increasing inside one emission.

StepEmission must be immutable and expose an unmodifiable List<Long>.

- [ ] **Step 4: Run scheduler tests**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.StepSchedulerTest
~~~

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add app/src/main/java/com/zcshou/telemetry/StepScheduler.java app/src/main/java/com/zcshou/telemetry/StepEmission.java app/src/test/java/com/zcshou/telemetry/StepSchedulerTest.java
git commit -m "feat: add deterministic step scheduler"
~~~

---

### Task 4: Build RunningTelemetryEngine and consistency validation

**Files:**
- Create: app/src/main/java/com/zcshou/telemetry/TelemetryUpdate.java
- Create: app/src/main/java/com/zcshou/telemetry/TelemetryConsistencyValidator.java
- Create: app/src/main/java/com/zcshou/telemetry/RunningTelemetryEngine.java
- Create: app/src/test/java/com/zcshou/telemetry/RunningTelemetryEngineTest.java

**Interfaces:**
- Consumes: com.zcshou.route.RouteSample.
- Produces:
  - RunningTelemetryEngine(TelemetryConfig config)
  - synchronized TelemetryUpdate accept(RouteSample sample)
  - synchronized void reset()
  - TelemetryUpdate.getSnapshot()
  - TelemetryUpdate.getEvents()
  - double TelemetryConsistencyValidator.relativeError(TelemetrySnapshot snapshot)
  - boolean TelemetryConsistencyValidator.passes(TelemetrySnapshot snapshot)

- [ ] **Step 1: Write end-to-end engine tests**

Use a helper in RunningTelemetryEngineTest:

~~~java
private RouteSample sample(
        long sessionId,
        RouteSessionState state,
        long elapsedNs,
        long wallMs,
        double speed,
        double distance,
        double routeLength,
        int lap
) {
    return new RouteSample.Builder(sessionId)
            .state(state)
            .latitudeWgs84(43.8195)
            .longitudeWgs84(87.5698)
            .altitudeMeters(800.0)
            .targetSpeedMps(speed)
            .outputSpeedMps(speed)
            .bearingDeg(90.0)
            .timestampMs(wallMs)
            .elapsedRealtimeNanos(elapsedNs)
            .lapCount(lap)
            .distanceMeters(distance)
            .routeLengthMeters(routeLength)
            .progressFraction(routeLength > 0 ? distance / routeLength : 0)
            .segmentIndex(0)
            .segmentCount(4)
            .build();
}
~~~

Add these tests:

~~~java
@Test
public void runningSnapshotIsKinematicallyConsistent() {
    RunningTelemetryEngine engine =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());

    engine.accept(sample(1, RouteSessionState.PLAYING, 0, 0, 3.0, 0, 400, 0));
    TelemetrySnapshot snapshot = engine.accept(
            sample(1, RouteSessionState.PLAYING, 10_000_000_000L, 10_000, 3.0, 30.0, 400, 0)
    ).getSnapshot();

    assertEquals(TelemetryState.RUNNING, snapshot.getState());
    assertEquals(30.0, snapshot.getTotalDistanceM(), 0.001);
    assertTrue(snapshot.getCadenceSpm() >= 120.0f);
    assertTrue(TelemetryConsistencyValidator.passes(snapshot));
}

@Test
public void pauseDoesNotCreateCatchUpSteps() {
    RunningTelemetryEngine engine =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());

    engine.accept(sample(1, RouteSessionState.PLAYING, 0, 0, 3, 0, 400, 0));
    TelemetrySnapshot beforePause = engine.accept(
            sample(1, RouteSessionState.PAUSED, 5_000_000_000L, 5000, 0, 15, 400, 0)
    ).getSnapshot();

    TelemetrySnapshot stillPaused = engine.accept(
            sample(1, RouteSessionState.PAUSED, 65_000_000_000L, 65000, 0, 15, 400, 0)
    ).getSnapshot();

    TelemetrySnapshot resumed = engine.accept(
            sample(1, RouteSessionState.PLAYING, 65_100_000_000L, 65100, 3, 15.3, 400, 0)
    ).getSnapshot();

    assertEquals(beforePause.getTotalSteps(), stillPaused.getTotalSteps());
    assertTrue(resumed.getTotalSteps() - stillPaused.getTotalSteps() <= 1);
}

@Test
public void belowMovingThresholdHasZeroCadenceAndNoSteps() {
    RunningTelemetryEngine engine =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());

    engine.accept(sample(1, RouteSessionState.PLAYING, 0, 0, 0.19, 0, 400, 0));
    TelemetrySnapshot snapshot = engine.accept(
            sample(1, RouteSessionState.PLAYING, 10_000_000_000L, 10000, 0.19, 1.9, 400, 0)
    ).getSnapshot();

    assertEquals(0.0f, snapshot.getCadenceSpm(), 0.0f);
    assertEquals(0L, snapshot.getTotalSteps());
}

@Test
public void lapCountersResetButTotalsContinue() {
    RunningTelemetryEngine engine =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());

    engine.accept(sample(1, RouteSessionState.PLAYING, 0, 0, 3, 0, 100, 0));
    TelemetrySnapshot before = engine.accept(
            sample(1, RouteSessionState.PLAYING, 30_000_000_000L, 30000, 3, 90, 100, 0)
    ).getSnapshot();

    TelemetryUpdate crossed = engine.accept(
            sample(1, RouteSessionState.PLAYING, 40_000_000_000L, 40000, 3, 120, 100, 1)
    );
    TelemetrySnapshot after = crossed.getSnapshot();

    assertEquals(1, after.getLapIndex());
    assertEquals(20.0, after.getLapDistanceM(), 0.001);
    assertTrue(after.getTotalSteps() >= before.getTotalSteps());
    assertTrue(after.getLapSteps() < after.getTotalSteps());
    assertTrue(crossed.getEvents().stream()
            .anyMatch(e -> e.getType() == TelemetryEventType.LAP));
}

@Test
public void identicalSeedAndSamplesProduceIdenticalResults() {
    RunningTelemetryEngine a =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());
    RunningTelemetryEngine b =
            new RunningTelemetryEngine(TelemetryConfig.defaultConfig());

    for (int i = 0; i <= 100; i++) {
        RouteSample s = sample(
                1, RouteSessionState.PLAYING,
                i * 100_000_000L, i * 100L,
                3.0, i * 0.3, 400.0, 0
        );

        TelemetrySnapshot sa = a.accept(s).getSnapshot();
        TelemetrySnapshot sb = b.accept(s).getSnapshot();

        assertEquals(sa.getCadenceSpm(), sb.getCadenceSpm(), 0.0f);
        assertEquals(sa.getTotalSteps(), sb.getTotalSteps());
        assertEquals(sa.getStepLengthM(), sb.getStepLengthM(), 0.0f);
    }
}
~~~

- [ ] **Step 2: Run engine tests and confirm RED**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.RunningTelemetryEngineTest
~~~

Expected: FAIL because engine classes do not exist.

- [ ] **Step 3: Implement TelemetryUpdate and RunningTelemetryEngine**

TelemetryUpdate is immutable:

~~~java
public final class TelemetryUpdate {
    private final TelemetrySnapshot snapshot;
    private final List<TelemetryEvent> events;

    public TelemetryUpdate(TelemetrySnapshot snapshot, List<TelemetryEvent> events) {
        this.snapshot = snapshot;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public TelemetrySnapshot getSnapshot() { return snapshot; }
    public List<TelemetryEvent> getEvents() { return events; }
}
~~~

RunningTelemetryEngine state:
- currentSessionId;
- lastMonotonicNs;
- lastWallMs;
- lastRouteState;
- activeElapsedNs;
- lastCadenceSpm;
- lapStepBase;
- lastLapIndex;
- CadenceProfile;
- StepScheduler.

Exact active-time rule:

~~~java
long deltaNs = lastMonotonicNs < 0
        ? 0L
        : Math.max(0L, sample.getElapsedRealtimeNanos() - lastMonotonicNs);

long activeDeltaNs =
        lastRouteState == RouteSessionState.PLAYING ? deltaNs : 0L;

activeElapsedNs += activeDeltaNs;
~~~

When sessionId changes:
- reset every engine field;
- set currentSessionId to the new positive session id;
- reset cadence profile state (profile itself is stateless by active time);
- reset StepScheduler;
- do not carry steps/laps across sessions.

Map states:
- READY -> IDLE
- PLAYING -> RUNNING
- PAUSED -> PAUSED
- STOPPED / FINISHED / ERROR -> STOPPED

For interval step scheduling, compute average cadence between lastCadenceSpm and the newly sampled active cadence while RUNNING. For a transition to PAUSED/STOPPED, use the last running cadence for the active interval before transition. Never feed PAUSED wall-time into StepScheduler.

Generate events:
- each emitted step -> STEP;
- RUNNING -> PAUSED -> PAUSE;
- PAUSED -> RUNNING -> RESUME;
- every increase in RouteSample.lapCount -> LAP, one event per crossed lap;
- first transition to STOPPED -> STOP.

Build snapshot:
- latitude/longitude/bearing/speed from RouteSample;
- totalDistanceM = max(previous total distance, sample.getDistanceMeters()) for the same session;
- routeLengthM = sample.getRouteLengthMeters();
- lapIndex = max(0, sample.getLapCount());
- lapDistanceM = routeLength > 0 ? totalDistance - lapIndex * routeLength : totalDistance, clamped >= 0;
- lapSteps = totalSteps - lapStepBase;
- cadenceSpm = 0 unless mapped state is RUNNING and speed >= 0.2;
- stepLengthM = speed * 60 / cadence when cadence > 0, otherwise 0;
- targetCadenceSpm = config target.

TelemetryConsistencyValidator.relativeError(snapshot):

~~~java
if (snapshot.getSpeedMps() < 0.2f || snapshot.getCadenceSpm() <= 0.0f) {
    return 0.0;
}
double reconstructed =
        snapshot.getCadenceSpm() * snapshot.getStepLengthM() / 60.0;
return Math.abs(snapshot.getSpeedMps() - reconstructed)
        / Math.max(snapshot.getSpeedMps(), 1e-9);
~~~

passes(snapshot) returns relativeError < 0.02.

- [ ] **Step 4: Run engine and all route tests**

~~~bash
./gradlew :app:testDebugUnitTest
~~~

Expected: PASS for new telemetry tests and every existing com.zcshou.route test.

- [ ] **Step 5: Commit**

~~~bash
git add app/src/main/java/com/zcshou/telemetry app/src/test/java/com/zcshou/telemetry
git commit -m "feat: add synthetic running telemetry engine"
~~~

---

### Task 5: Add TelemetryStore and wire ServiceGo to the engine

**Files:**
- Create: app/src/main/java/com/zcshou/telemetry/TelemetryStore.java
- Create: app/src/test/java/com/zcshou/telemetry/TelemetryStoreTest.java
- Modify: app/src/main/java/com/zcshou/service/ServiceGo.java

**Interfaces:**
- Consumes: TelemetryUpdate from RunningTelemetryEngine.
- Produces:
  - TelemetryStore.getInstance()
  - getLatestSnapshot()
  - addListener(Listener)
  - removeListener(Listener)
  - publish(TelemetryUpdate)
  - reset()
  - Listener.onSnapshot(TelemetrySnapshot)
  - Listener.onEvent(TelemetryEvent)

- [ ] **Step 1: Write store tests**

~~~java
package com.zcshou.telemetry;

import com.zcshou.telemetry.api.TelemetryEvent;
import com.zcshou.telemetry.api.TelemetryEventType;
import com.zcshou.telemetry.api.TelemetrySnapshot;
import com.zcshou.telemetry.api.TelemetryState;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TelemetryStoreTest {
    @Test
    public void publishUpdatesLatestAndNotifiesListeners() {
        TelemetryStore store = new TelemetryStore();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger events = new AtomicInteger();

        store.addListener(new TelemetryStore.Listener() {
            @Override public void onSnapshot(TelemetrySnapshot snapshot) {
                snapshots.incrementAndGet();
            }
            @Override public void onEvent(TelemetryEvent event) {
                events.incrementAndGet();
            }
        });

        TelemetrySnapshot snapshot = new TelemetrySnapshot.Builder(1L)
                .state(TelemetryState.RUNNING)
                .build();
        TelemetryEvent event = new TelemetryEvent(
                1L, TelemetryEventType.STEP, 10L, 20L, 0, 1L
        );

        store.publish(new TelemetryUpdate(
                snapshot, Collections.singletonList(event)
        ));

        assertSame(snapshot, store.getLatestSnapshot());
        assertEquals(1, snapshots.get());
        assertEquals(1, events.get());
    }
}
~~~

- [ ] **Step 2: Run store test and confirm RED**

~~~bash
./gradlew :app:testDebugUnitTest --tests com.zcshou.telemetry.TelemetryStoreTest
~~~

Expected: FAIL because TelemetryStore does not exist.

- [ ] **Step 3: Implement TelemetryStore**

Use:
- AtomicReference<TelemetrySnapshot> for latest snapshot;
- CopyOnWriteArrayList<Listener> for listeners;
- listener exceptions are caught so one observer cannot break the producer;
- reset() clears latest and listeners are retained so bridge clients can survive a new producer session.

Provide a package-visible constructor for unit tests and a process singleton from getInstance().

- [ ] **Step 4: Wire ServiceGo without changing route ownership**

Add fields:

~~~java
private RunningTelemetryEngine mTelemetryEngine;
private TelemetryStore mTelemetryStore;
~~~

In onCreate(), after RoutePlaybackController creation:

~~~java
mTelemetryEngine =
        new RunningTelemetryEngine(TelemetryConfig.defaultConfig());
mTelemetryStore = TelemetryStore.getInstance();
~~~

In onRouteSample(), only after LocationStateArbiter accepts the RouteSample and after the existing RouteSnapshot diagnostic publication:

~~~java
TelemetryUpdate telemetryUpdate = mTelemetryEngine.accept(sample);
mTelemetryStore.publish(telemetryUpdate);
~~~

Do not:
- change LocationStateArbiter semantics;
- change setTestProviderLocation calls;
- change RoutePlaybackController cadence/timing;
- make RouteActivity own the telemetry engine.

In onDestroy():

~~~java
if (mTelemetryEngine != null) {
    mTelemetryEngine.reset();
}
if (mTelemetryStore != null) {
    mTelemetryStore.reset();
}
~~~

- [ ] **Step 5: Run the complete unit suite and build Debug**

~~~bash
./gradlew :telemetry-api:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
~~~

Expected:
- all tests PASS;
- app debug APK is produced;
- no behavior regression in existing route tests.

- [ ] **Step 6: Commit**

~~~bash
git add app/src/main/java/com/zcshou/service/ServiceGo.java app/src/main/java/com/zcshou/telemetry app/src/test/java/com/zcshou/telemetry
git commit -m "feat: publish route telemetry from ServiceGo"
~~~

---

## V2-B Completion Gate

Before starting V2-C, run:

~~~bash
./gradlew clean :telemetry-api:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
~~~

PASS requires:
- all pre-existing route tests green;
- all new telemetry tests green;
- deterministic replay test green;
- pause/resume no-catch-up test green;
- near-zero-speed no-step test green;
- lap reset/session accumulation test green;
- Debug APK builds successfully.

Do not proceed to the cross-app Binder bridge if any V2-B gate is red.
