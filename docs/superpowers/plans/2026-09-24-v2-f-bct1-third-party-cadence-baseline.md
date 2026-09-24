# V2-F BCT-1 Third-Party Cadence Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and validate the first controlled V2-F baseline in which deterministic 150/165/180 spm step streams traverse an Android system sensor path, are independently observed by RunnerProbe, and can be compared with an unmodified target running app.

**Architecture:** The implementation has two execution paths. A short stock-device capability probe runs first on the OPPO Find X8 without root/unlock/flash; any unresolved PARTIAL result receives one repeat and then falls back to a controlled AOSP AVD. The fallback exposes test step sensors in a development AOSP image and uses Android's development-only replay/data-injection path to deliver deterministic detector/counter events to ordinary SensorManager consumers. RunnerProbe is the reference observer; the target app is a black-box AUT.

**Tech Stack:** Python 3 standard library, Java 11 / Android SDK 32 for RunnerProbe, ADB, AOSP `aosp-android-latest-release`, Android Emulator target `sdk_phone_x86_64`, Android platform APIs for the AOSP-only injector, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-24-v2-f-bct1-third-party-cadence-baseline-design.md`

## Global Constraints

- Primary physical device is OPPO Find X8 on stock ColorOS.
- Do not root, unlock the bootloader, flash the Find X8, modify `/system`, or modify vendor HAL files.
- PC + ADB host tooling is allowed.
- The first AUT is the actual unmodified third-party running app selected by the user.
- Do not patch the AUT, use private AUT IPC/storage, tune to authenticity checks, or submit synthetic results to production rankings/grades/rewards/services.
- Fixed cadence precedes speed-derived cadence: test 165 spm first, then 150/165/180 spm.
- Every formal case is 90 s; the official comparison window is the half-open interval `[30 s, 90 s)`.
- Full BCT-1 PASS requires both `TYPE_STEP_DETECTOR` and `TYPE_STEP_COUNTER`.
- RunnerProbe cadence tolerance is <= 2 spm from target in the official window.
- AUT visible cadence tolerance is <= 5 spm from target when the AUT exposes cadence.
- Stock-device PARTIAL gets one repeat only; unresolved PARTIAL or FAIL switches to AOSP AVD.
- AOSP fallback is a development/test environment only. It must be obviously labeled synthetic and must not be presented as a production sensor implementation.
- If RunnerProbe passes but AUT cadence is absent, classify `ANDROID_PASS_AUT_NOT_OBSERVED`, not Android sensor failure.

## Review Focus

1. **Multiple/no ADB devices:** host tools must refuse ambiguous device selection and never run mutating commands without an explicit serial.
2. **SensorService mode cleanup:** any attempted stock or AOSP data-injection mode must return SensorService to NORMAL in a `finally` path, including Ctrl-C and injector failure.
3. **165 spm drift/off-by-one:** timeline timestamps must be computed from the event index, not by repeatedly adding a rounded interval; the official 60 s window must contain exactly 165 detector steps.
4. **Counter continuity:** the counter baseline must be established before RunnerProbe begins recording, then remain monotonic with no session reset.
5. **AUT environment incompatibility:** install/launch/ABI/GMS failure must be recorded as an AUT environment result and must not invalidate a proven RunnerProbe Android reference path.

---

## File Structure

Create the following focused units:

```text
bct1/
├── __init__.py
├── timeline.py                 # deterministic cadence timeline
├── generate_timeline.py        # CLI -> synthetic_step_timeline.csv/json
├── adb.py                      # serial-safe adb subprocess wrapper
├── probe_stock.py              # Find X8 capability probe + cleanup
├── manifest.py                 # BCT-1 session manifest model
├── analyze_bct1.py             # RunnerProbe + ledger + AUT observation evaluator
└── tests/
    ├── test_timeline.py
    ├── test_adb.py
    ├── test_probe_stock.py
    ├── test_manifest.py
    └── test_analyze_bct1.py

aosp-sensor-lab/
├── README.md
├── patches/
│   └── 0001-v2f-register-step-test-sensors.patch
├── injector/
│   ├── Android.bp
│   ├── AndroidManifest.xml
│   ├── privapp-permissions-com.zcshou.v2finjector.xml
│   └── src/com/zcshou/v2finjector/
│       ├── MainActivity.java
│       ├── InjectionService.java
│       └── Timeline.java
└── scripts/
    ├── prepare_aosp.sh
    ├── build_avd.sh
    ├── install_injector.sh
    └── run_case.sh

docs/v2f/
├── findx8-phase0.md
├── aosp-avd-lab.md
└── bct1-acceptance.md
```

The AOSP patch is kept in this repository, but applies to an external AOSP checkout. It must only register the two lab sensor descriptors needed for BCT-1; event generation belongs to the AOSP-only injector.

---

### Task 1: Deterministic Cadence Timeline

**Files:**
- Create: `bct1/__init__.py`
- Create: `bct1/timeline.py`
- Create: `bct1/generate_timeline.py`
- Test: `bct1/tests/test_timeline.py`

**Interfaces:**
- Produces: `StepSample(index: int, offset_ns: int, detector_value: float, counter_value: int)`
- Produces: `build_timeline(cadence_spm: int, duration_s: int, counter_baseline: int) -> list[StepSample]`
- Produces: `samples_in_window(samples, start_ns, end_ns) -> list[StepSample]`
- CLI writes `synthetic_step_timeline.csv` and `synthetic_step_timeline.json`.

- [ ] **Step 1: Write failing timeline tests**

```python
def test_165_spm_has_exact_official_window_count():
    samples = build_timeline(165, 90, 10_000)
    official = samples_in_window(samples, 30_000_000_000, 90_000_000_000)
    assert len(official) == 165

def test_supported_matrix_has_expected_60_second_counts():
    for cadence in (150, 165, 180):
        samples = build_timeline(cadence, 90, 10_000)
        official = samples_in_window(samples, 30_000_000_000, 90_000_000_000)
        assert len(official) == cadence

def test_detector_and_counter_semantics():
    samples = build_timeline(165, 90, 10_000)
    assert all(s.detector_value == 1.0 for s in samples)
    assert [s.counter_value for s in samples[:3]] == [10_001, 10_002, 10_003]
    assert all(b.offset_ns > a.offset_ns for a, b in zip(samples, samples[1:]))
```

Also test rejection of cadence <= 0, duration <= 0, negative counter baseline, and unsupported cadence values when the CLI is run in formal BCT-1 mode.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
python -m unittest bct1.tests.test_timeline -v
```

Expected: import/function failures because `bct1.timeline` does not exist.

- [ ] **Step 3: Implement index-derived integer timestamps**

Use:

```python
from dataclasses import dataclass

NS_PER_MINUTE = 60_000_000_000
FORMAL_CADENCES = {150, 165, 180}

@dataclass(frozen=True)
class StepSample:
    index: int
    offset_ns: int
    detector_value: float
    counter_value: int

def build_timeline(cadence_spm: int, duration_s: int = 90,
                   counter_baseline: int = 10_000):
    if cadence_spm <= 0 or duration_s <= 0 or counter_baseline < 0:
        raise ValueError("invalid cadence timeline arguments")
    end_ns = duration_s * 1_000_000_000
    out = []
    index = 1
    while True:
        offset_ns = round(index * NS_PER_MINUTE / cadence_spm)
        if offset_ns >= end_ns:
            break
        out.append(StepSample(
            index=index,
            offset_ns=offset_ns,
            detector_value=1.0,
            counter_value=counter_baseline + index,
        ))
        index += 1
    return out

def samples_in_window(samples, start_ns, end_ns):
    return [s for s in samples if start_ns <= s.offset_ns < end_ns]
```

The CLI CSV header is exactly:

```text
step_index,offset_ns,detector_value,counter_value,target_spm
```

- [ ] **Step 4: Run tests and CLI smoke test**

```bash
python -m unittest bct1.tests.test_timeline -v
python bct1/generate_timeline.py --cadence 165 --duration 90 --counter-baseline 10000 --output-dir /tmp/bct1_165
```

Expected: tests PASS; JSON reports `official_window_count=165`.

- [ ] **Step 5: Commit**

```bash
git add bct1
git commit -m "feat: add deterministic BCT-1 cadence timeline"
```

---

### Task 2: Stock Find X8 Capability Probe

**Files:**
- Create: `bct1/adb.py`
- Create: `bct1/probe_stock.py`
- Create: `bct1/tests/test_adb.py`
- Create: `bct1/tests/test_probe_stock.py`
- Create: `docs/v2f/findx8-phase0.md`

**Interfaces:**
- `AdbClient(serial: str)` requires an explicit serial.
- `collect_stock_probe(client) -> dict` is read-only.
- `attempt_reversible_injection_mode_probe(client, package_name) -> dict` may switch SensorService mode but must always run `dumpsys sensorservice enable` before returning.
- Probe result states: `CANDIDATE`, `PARTIAL`, `UNAVAILABLE`; stock `PASS` is reserved for later RunnerProbe evidence.

- [ ] **Step 1: Write failing serial-safety and parser tests**

Fixtures must cover zero devices, one device, two devices, step sensors present/absent, and a SensorService response that rejects data injection.

```python
def test_adb_requires_explicit_serial():
    with self.assertRaises(ValueError):
        AdbClient("")

def test_probe_detects_both_step_sensor_types():
    report = parse_sensorservice_dump(FIXTURE_BOTH_STEP_SENSORS)
    self.assertTrue(report["step_detector_present"])
    self.assertTrue(report["step_counter_present"])
```

- [ ] **Step 2: Verify RED**

```bash
python -m unittest bct1.tests.test_adb bct1.tests.test_probe_stock -v
```

Expected: missing module/function failures.

- [ ] **Step 3: Implement read-only collection**

Collect exactly:

```text
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.fingerprint
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell dumpsys sensorservice
adb -s SERIAL shell pm list features
```

Write `phase0_probe.json`, `sensorservice.txt`, and `device_props.json`.

Do not infer step support from feature flags alone; sensor list evidence is primary.

- [ ] **Step 4: Implement reversible mode probe with unconditional cleanup**

The active probe sequence is:

```python
try:
    before = adb.shell("dumpsys sensorservice")
    result = adb.shell(
        "dumpsys sensorservice data_injection com.zcshou.runnerprobe",
        check=False,
    )
    during = adb.shell("dumpsys sensorservice", check=False)
finally:
    cleanup = adb.shell("dumpsys sensorservice enable", check=False)
    after = adb.shell("dumpsys sensorservice", check=False)
```

Classify a visible/accepted mode without app-delivered synthetic events as `CANDIDATE`, never PASS.

Add SIGINT handling so cleanup executes on Ctrl-C.

- [ ] **Step 5: Run unit tests**

```bash
python -m unittest bct1.tests.test_adb bct1.tests.test_probe_stock -v
```

Expected: PASS.

- [ ] **Step 6: Document the exact user command**

```bash
python bct1/probe_stock.py --serial <ADB_SERIAL> --output-dir evidence/findx8_phase0
```

The runbook must explicitly say: if the active probe leaves SensorService state uncertain, run `adb -s SERIAL shell dumpsys sensorservice enable` and reboot before proceeding.

- [ ] **Step 7: Commit**

```bash
git add bct1 docs/v2f/findx8-phase0.md
git commit -m "feat: add reversible stock sensor capability probe"
```

---

### Task 3: AOSP AVD Step-Sensor Surface and Lab Injector

**Files:**
- Create: `aosp-sensor-lab/patches/0001-v2f-register-step-test-sensors.patch`
- Create: `aosp-sensor-lab/injector/Android.bp`
- Create: `aosp-sensor-lab/injector/AndroidManifest.xml`
- Create: `aosp-sensor-lab/injector/privapp-permissions-com.zcshou.v2finjector.xml`
- Create: `aosp-sensor-lab/injector/src/com/zcshou/v2finjector/MainActivity.java`
- Create: `aosp-sensor-lab/injector/src/com/zcshou/v2finjector/InjectionService.java`
- Create: `aosp-sensor-lab/injector/src/com/zcshou/v2finjector/Timeline.java`
- Create: `aosp-sensor-lab/scripts/prepare_aosp.sh`
- Create: `aosp-sensor-lab/scripts/build_avd.sh`
- Create: `aosp-sensor-lab/scripts/install_injector.sh`
- Create: `docs/v2f/aosp-avd-lab.md`

**Interfaces:**
- AOSP branch: `aosp-android-latest-release`.
- AVD target: `sdk_phone_x86_64`.
- Two system-visible lab sensors: `V2F Virtual Step Detector` type 18 and `V2F Virtual Step Counter` type 19.
- Injector package: `com.zcshou.v2finjector`.
- Injector starts only on debuggable AOSP builds and uses Android's development-only HAL-bypass replay/data-injection mode.
- Broadcast/API parameters: session id, cadence spm, duration sec, counter baseline.

- [ ] **Step 1: Add a patch-validation test before the patch**

Create a Python test in `bct1/tests/test_aosp_assets.py` that requires the patch to contain all of:

```text
SENSOR_TYPE_STEP_DETECTOR
SENSOR_TYPE_STEP_COUNTER
V2F Virtual Step Detector
V2F Virtual Step Counter
```

and requires the injector source to reference:

```text
HAL_BYPASS_REPLAY_DATA_INJECTION
injectSensorData
TYPE_STEP_DETECTOR
TYPE_STEP_COUNTER
```

Run and verify RED.

- [ ] **Step 2: Create the minimal AOSP sensor-registration patch**

Patch the external AOSP SensorService test surface, not the OPPO image. The patch must add two no-op virtual `SensorInterface` descriptors and register them in the user-visible sensor list only on debuggable builds.

The implementation must use dedicated handles outside the current built-in range and define:

```text
name: V2F Virtual Step Detector
type: SENSOR_TYPE_STEP_DETECTOR
stringType: android.sensor.step_detector
reporting mode: SPECIAL_TRIGGER

name: V2F Virtual Step Counter
type: SENSOR_TYPE_STEP_COUNTER
stringType: android.sensor.step_counter
reporting mode: ON_CHANGE
```

The patch must touch only SensorService test-lab code required to enumerate these sensors; it must not alter normal hardware sensor data.

- [ ] **Step 3: Create the AOSP-only privileged injector**

`Android.bp` must build a platform-signed privileged app:

```bp
android_app {
    name: "V2fSensorInjector",
    srcs: ["src/**/*.java"],
    manifest: "AndroidManifest.xml",
    platform_apis: true,
    privileged: true,
    certificate: "platform",
}
```

Manifest declares `android.permission.LOCATION_HARDWARE` and an exported command receiver/service limited to the controlled lab image.

At startup the injector must refuse non-debuggable builds by checking `Build.IS_DEBUGGABLE`.

- [ ] **Step 4: Implement baseline + scheduled injection semantics**

Before a formal run, inject one counter-only baseline event at the configured baseline value so RunnerProbe can become READY.

For each timeline sample:

```java
sensorManager.injectSensorData(
    detector,
    new float[]{1.0f},
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    eventTimestampNs
);

sensorManager.injectSensorData(
    counter,
    new float[]{(float) counterValue},
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
    eventTimestampNs
);
```

Use `startElapsedNs + round(index * 60_000_000_000L / cadenceSpm)` for event timestamps. Delivery scheduling may wake late, but the injected event timestamp must remain the ideal deterministic timestamp.

On any failed injection, stop the run, persist an error marker, and disable injection mode.

- [ ] **Step 5: Add AOSP preparation/build scripts**

`prepare_aosp.sh` must verify it is run from an AOSP checkout, apply the patch with `git apply --check` before `git apply`, and copy the injector into `packages/apps/V2fSensorInjector`.

`build_avd.sh` runs:

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64
m -j"$(nproc)"
```

The runbook then launches:

```bash
emulator
```

- [ ] **Step 6: Install the platform injector and verify enumeration**

On the userdebug/eng AVD:

```bash
adb root
adb remount
adb push out/target/product/*/system/priv-app/V2fSensorInjector/V2fSensorInjector.apk   /system/priv-app/V2fSensorInjector/V2fSensorInjector.apk
adb push aosp-sensor-lab/injector/privapp-permissions-com.zcshou.v2finjector.xml   /system/etc/permissions/
adb reboot
```

Then require:

```bash
adb shell dumpsys sensorservice
```

to list both V2F sensor names before proceeding.

- [ ] **Step 7: Run repository asset tests and AOSP compile gate**

Repository:

```bash
python -m unittest bct1.tests.test_aosp_assets -v
```

AOSP checkout:

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64
m V2fSensorInjector sensorservice -j"$(nproc)"
```

Expected: both build targets succeed.

- [ ] **Step 8: Commit**

```bash
git add aosp-sensor-lab bct1/tests/test_aosp_assets.py docs/v2f/aosp-avd-lab.md
git commit -m "feat: add AOSP V2-F virtual step sensor lab"
```

---

### Task 4: RunnerProbe BCT-1 Reference Metadata

**Files:**
- Modify: `runnerprobe/src/main/java/com/zcshou/runnerprobe/session/SessionMetadata.java`
- Modify: `runnerprobe/src/main/java/com/zcshou/runnerprobe/service/MotionRecordingService.java`
- Test: matching RunnerProbe unit tests under `runnerprobe/src/test/java/com/zcshou/runnerprobe/`

**Interfaces:**
- Preserve the existing V2-E evidence schema compatibility.
- Add sensor handle/type/stringType fields for detector and counter.
- Do not add any private GoGoGo/injector data channel to RunnerProbe.

- [ ] **Step 1: Add failing metadata serialization tests**

Require metadata for each step sensor to include:

```json
{
  "handle": 123,
  "type": 18,
  "string_type": "android.sensor.step_detector",
  "name": "V2F Virtual Step Detector",
  "vendor": "V2F Lab"
}
```

and analogous counter data.

- [ ] **Step 2: Verify RED**

```bash
./gradlew :runnerprobe:testDebugUnitTest
```

Expected: new metadata assertions fail.

- [ ] **Step 3: Extend metadata builder and service capture**

Populate fields from `Sensor.getHandle()`, `getType()`, `getStringType()`, `getName()`, and `getVendor()`.

Do not relax existing READY behavior: a present counter still requires a baseline callback before official recording.

- [ ] **Step 4: Run RunnerProbe tests**

```bash
./gradlew :runnerprobe:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runnerprobe
git commit -m "feat: record BCT-1 step sensor identity"
```

---

### Task 5: BCT-1 Manifest and Analyzer

**Files:**
- Create: `bct1/manifest.py`
- Create: `bct1/analyze_bct1.py`
- Create: `bct1/tests/test_manifest.py`
- Create: `bct1/tests/test_analyze_bct1.py`

**Interfaces:**
- Manifest result enums: `PASS`, `ANDROID_PASS_AUT_NOT_OBSERVED`, `PARTIAL`, `FAIL`.
- Inputs: synthetic ledger, RunnerProbe export ZIP, system probe/report, AUT observation JSON.
- Output: `bct1_report.json` and `bct1_report.txt`.

- [ ] **Step 1: Write failing analyzer tests**

Test exact cases:

```python
def test_android_pass_when_165_spm_reference_is_within_two_spm(): ...
def test_counter_discontinuity_is_fail(): ...
def test_missing_counter_channel_is_partial(): ...
def test_aut_absent_does_not_demote_android_reference_pass(): ...
def test_aut_171_for_target_165_is_not_aut_pass(): ...
def test_aut_170_for_target_165_is_aut_pass(): ...
```

Also test that only events in `[30s, 90s)` count toward the formal cadence result.

- [ ] **Step 2: Verify RED**

```bash
python -m unittest bct1.tests.test_manifest bct1.tests.test_analyze_bct1 -v
```

- [ ] **Step 3: Implement manifest validation**

Required manifest keys:

```text
schema_version = bct1-1
session_id
execution_path = findx8_stock | aosp_emulator
target_cadence_spm
duration_s = 90
official_start_s = 30
official_end_s = 90
android_build_fingerprint
runnerprobe_package
runnerprobe_source_commit
aut_package
aut_version
aut_observation = observed | not_observed | environment_unsupported
```

Reject missing/unknown values rather than guessing.

- [ ] **Step 4: Implement reference-layer calculations**

Compute detector cadence from detector timestamps in the official window:

```python
detector_spm = detector_count * 60.0 / official_duration_s
reference_pass = abs(detector_spm - target_spm) <= 2.0
```

Counter consistency remains:

```python
abs(detector_count - counter_delta) <= max(2, math.ceil(0.03 * detector_count))
```

Require monotonic detector timestamps and counter values.

- [ ] **Step 5: Implement AUT classification**

If Android reference passes and AUT reports a stable cadence:

```python
aut_pass = abs(aut_cadence_spm - target_spm) <= 5.0
```

If no AUT cadence is visible, return `ANDROID_PASS_AUT_NOT_OBSERVED`.

If AUT cannot install/launch in the emulator, store `environment_unsupported` and preserve the Android reference result.

- [ ] **Step 6: Run tests**

```bash
python -m unittest bct1.tests.test_manifest bct1.tests.test_analyze_bct1 -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add bct1
git commit -m "feat: add BCT-1 evidence analyzer"
```

---

### Task 6: One-Command 165 spm AOSP Lab Run

**Files:**
- Create: `aosp-sensor-lab/scripts/run_case.sh`
- Create: `docs/v2f/bct1-acceptance.md`
- Test: extend `bct1/tests/test_aosp_assets.py`

**Interfaces:**
- Usage: `run_case.sh SERIAL SESSION_ID CADENCE_SPM`
- Formal cadence is restricted to 150, 165, or 180.
- Script creates `evidence/bct1/<session_id>/`.

- [ ] **Step 1: Add failing script-contract tests**

Require the script to reject unsupported cadence, missing serial, reused session directory, and non-debuggable device.

- [ ] **Step 2: Implement the orchestration sequence**

The script must:

1. verify exactly the specified ADB serial is reachable;
2. verify `ro.debuggable=1`;
3. verify both V2F step sensors are visible;
4. generate the 90 s ledger;
5. put SensorService into `hal_bypass_replay_data_injection com.zcshou.v2finjector`;
6. command the injector to emit the counter baseline;
7. wait for explicit operator confirmation that RunnerProbe is READY;
8. command the injector to start the 90 s formal stream;
9. capture filtered logcat and pre/post `dumpsys sensorservice`;
10. always run `dumpsys sensorservice enable` in cleanup;
11. write `system_run.json`.

Do not automate interaction with the target AUT beyond install/launch convenience; observation remains black-box.

- [ ] **Step 3: Add AUT observation template**

The acceptance doc requires the operator to create:

```json
{
  "session_id": "bct1_...",
  "aut_package": "...",
  "aut_version": "...",
  "observation": "observed",
  "stable_cadence_spm": 165.0,
  "evidence_file": "aut_screen_recording.mp4",
  "notes": ""
}
```

For no visible cadence use `"observation": "not_observed"`; for install/launch incompatibility use `"environment_unsupported"`.

- [ ] **Step 4: Run script-contract tests**

```bash
python -m unittest bct1.tests.test_aosp_assets -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add aosp-sensor-lab docs/v2f
git commit -m "feat: add one-command BCT-1 AOSP run flow"
```

---

### Task 7: CI and BCT-1 Toolkit Artifact

**Files:**
- Modify: `.github/workflows/route-playback-debug.yml`

**Interfaces:**
- Existing V2-E CI must remain green.
- CI validates host code and static AOSP lab assets; it does not attempt a full AOSP image build on GitHub-hosted runners.
- New artifact: `v2f-bct1-toolkit`.

- [ ] **Step 1: Add BCT-1 Python tests to CI**

Add:

```yaml
- name: Run V2-F BCT-1 Unit Tests
  run: python -m unittest discover -s bct1/tests -p 'test_*.py' -v
```

- [ ] **Step 2: Package the toolkit**

Artifact contents:

```text
bct1/**
aosp-sensor-lab/**
docs/v2f/**
docs/superpowers/specs/2026-09-24-v2-f-bct1-third-party-cadence-baseline-design.md
docs/superpowers/plans/2026-09-24-v2-f-bct1-third-party-cadence-baseline.md
```

- [ ] **Step 3: Run all local automated gates**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :runnerprobe:testDebugUnitTest
PYTHONPATH=motion-analysis python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
python -m unittest discover -s bct1/tests -p 'test_*.py' -v
./gradlew :app:assembleDebug
./gradlew :runnerprobe:assembleDebug
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/route-playback-debug.yml
git commit -m "ci: publish V2-F BCT-1 lab toolkit"
```

---

### Task 8: Execute BCT-1 Acceptance in the Frozen Order

**Files:**
- Evidence only; no product-code changes unless a defect is discovered and returned to the owning task.

**Interfaces:**
- M1 = 165 spm.
- M2 = 150/165/180 spm after M1.
- Find X8 path is attempted first; unresolved PARTIAL switches to AOSP AVD.

- [ ] **Step 1: Run Find X8 Phase 0**

```bash
adb devices
python bct1/probe_stock.py --serial SERIAL --output-dir evidence/findx8_phase0
```

Record one of: `CANDIDATE`, `PARTIAL`, `UNAVAILABLE`.

If a candidate active path cannot produce both system-delivered step channels in RunnerProbe after one repeat, stop stock-device work and move to AOSP AVD.

- [ ] **Step 2: Build/launch AOSP fallback if required**

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64
m -j"$(nproc)"
emulator
```

Install RunnerProbe and the AOSP-only injector. Verify both V2F step sensors appear in `dumpsys sensorservice`.

- [ ] **Step 3: Execute M1 at 165 spm**

Use a fresh session id:

```text
bct1_165_YYYYMMDD_HHMMSS
```

Arm RunnerProbe, establish the counter baseline, begin RunnerProbe recording, execute the 90 s synthetic stream, and capture the AUT screen for the official final 60 s.

- [ ] **Step 4: Analyze M1**

```bash
python bct1/analyze_bct1.py \
  --session-dir evidence/bct1/SESSION_ID \
  --runnerprobe-zip path/to/runnerprobe_SESSION_ID.zip \
  --aut-observation evidence/bct1/SESSION_ID/aut_observation.json
```

Do not proceed to the three-cadence matrix until the Android reference layer passes.

- [ ] **Step 5: Execute M2**

Repeat with new session ids for 150, 165, and 180 spm.

- [ ] **Step 6: Freeze the first-round result**

The final BCT-1 report must state one of:

```text
PASS
ANDROID_PASS_AUT_NOT_OBSERVED
PARTIAL
FAIL
```

and identify which execution path produced the result: `findx8_stock` or `aosp_emulator`.

No target-specific bypass work follows from a NOT_OBSERVED result inside this plan.

---

## Plan Self-Review

### Spec coverage

- Find X8 stock capability gate: Task 2 and Task 8.
- One-repeat PARTIAL rule: Task 2/8.
- AOSP AVD fallback: Task 3/6/8.
- Fixed 165 first, then 150/165/180: Task 1/6/8.
- Detector + counter semantics: Task 1/3/4/5.
- 90 s / final 60 s window: Task 1/5/8.
- RunnerProbe <= 2 spm: Task 5.
- AUT <= 5 spm: Task 5.
- Full evidence contract: Task 2/5/6/8.
- AUT black-box/no bypass boundary: Global Constraints and Task 8.
- GitHub teaching/toolkit packaging after the path exists: Task 7.

### Type/interface consistency

- `StepSample` fields are identical between generator and analyzer inputs.
- Formal window is consistently `[30 s, 90 s)`.
- Counter baseline is established before RunnerProbe official recording.
- AUT observation states are the same in manifest and analyzer.
- BCT-1 result enums match the approved spec.

### Deliberate non-goals

- No speed-to-cadence model.
- No cadence coaching.
- No policy-gate/restriction round.
- No Cuttlefish.
- No target-app reverse engineering or authenticity bypass.
