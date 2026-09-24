# V2-F AOSP AVD Lab

## Purpose

This is the deterministic fallback when the stock OPPO Find X8 does not expose a usable reversible SensorService test path.

The lab modifies only a **debuggable AOSP emulator image**. It does not patch the target AUT and does not add system-sensor injection to the normal GoGoGo APK.

## AOSP baseline

Use the current `aosp-android-latest-release` branch and the `sdk_phone_x86_64` lunch target.

From an AOSP checkout, copy or clone this repository elsewhere and run:

```bash
aosp-sensor-lab/scripts/prepare_aosp.sh \
  /path/to/GoGoGo/aosp-sensor-lab/patches/0001-v2f-register-step-test-sensors.patch \
  /path/to/GoGoGo/aosp-sensor-lab/injector
```

The script performs `git apply --check` before changing `frameworks/native`.

Build the narrow targets first:

```bash
source build/envsetup.sh
lunch sdk_phone_x86_64
m V2fSensorInjector sensorservice -j"$(nproc)"
```

Then build/launch the emulator image as required by the local AOSP checkout.

## Required verification

Before a BCT-1 session:

```bash
adb -s <SERIAL> shell getprop ro.debuggable
adb -s <SERIAL> shell dumpsys sensorservice | grep 'V2F Virtual Step'
```

The first command must return `1`. Both lab sensor names must be visible.

The host will enter:

```text
hal_bypass_replay_data_injection com.zcshou.v2finjector
```

for the formal experiment and will always restore:

```text
dumpsys sensorservice enable
```

afterward.

## Counter baseline

Before RunnerProbe begins official recording, the injector emits one counter baseline event (default 10000). This is not a synthetic step. It exists so RunnerProbe can establish the current cumulative counter and enter READY.

## Isolation

Do not upload synthetic exercise results to production services. The AUT is observed as a black-box consumer only. An AUT that does not expose cadence is recorded as `NOT_OBSERVED`; this lab does not alter or bypass the AUT.
