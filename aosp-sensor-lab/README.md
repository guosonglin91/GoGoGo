# V2-F AOSP Sensor Lab

This directory contains the controlled AOSP-only fallback for BCT-1.

It is intentionally **not** an ordinary Android application feature. The lab requires a debuggable AOSP image, registers two clearly named synthetic sensors, and uses Android's development-only HAL-bypass replay/data-injection mode.

The two sensors are:

- `V2F Virtual Step Detector` — `TYPE_STEP_DETECTOR`
- `V2F Virtual Step Counter` — `TYPE_STEP_COUNTER`

The unmodified RunnerProbe and AUT continue to consume standard `SensorManager` APIs.

See `docs/v2f/aosp-avd-lab.md` for build and use instructions.
