# Find X8 Phase-0 Capability Probe

This probe answers one question quickly: can the stock OPPO Find X8 expose a reversible Android sensor-test path that reaches an ordinary `SensorManager` consumer without root, bootloader unlock, flashing, or persistent system modification?

## Preconditions

- OPPO Find X8 on stock ColorOS.
- Developer Options and USB debugging enabled.
- RunnerProbe installed.
- ADB authorized on the phone.
- No real-account exercise submission during this experiment.

List devices first:

```bash
adb devices
```

Use the exact serial shown by ADB. The host tool refuses an empty serial.

## Run

```bash
python bct1/probe_stock.py \
  --serial <ADB_SERIAL> \
  --output-dir evidence/findx8_phase0
```

The probe collects build properties, the SensorService dump, step-sensor presence, and briefly attempts Android's reversible SensorService data-injection test mode for RunnerProbe.

The probe always attempts:

```text
dumpsys sensorservice enable
```

before returning from the active mode check.

## Output

```text
evidence/findx8_phase0/
├── device_props.json
├── phase0_probe.json
└── sensorservice.txt
```

Possible preliminary classifications:

- `CANDIDATE`: both step sensor types are visible and the stock SensorService appears to expose the reversible test mode.
- `PARTIAL`: the test mode appears available but one required step sensor is missing.
- `UNAVAILABLE`: the stock test mode is not available or is rejected.

These are capability-probe classifications only. **Find X8 PASS is not granted here.** PASS requires RunnerProbe to receive both synthetic detector and counter events through standard Android `SensorManager` callbacks.

## Recovery

If Ctrl-C, an ADB disconnect, or any other failure leaves SensorService state uncertain, run:

```bash
adb -s <ADB_SERIAL> shell dumpsys sensorservice enable
adb -s <ADB_SERIAL> shell dumpsys sensorservice
```

If the state is still uncertain, reboot the phone before further testing.

## Stop Rule

If the stock path is `UNAVAILABLE`, or remains `PARTIAL` after one minimal repeat, stop vendor-specific investigation and switch to the approved AOSP Emulator fallback.
