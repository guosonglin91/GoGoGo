#!/usr/bin/env bash
set -euo pipefail

test -f build/envsetup.sh
source build/envsetup.sh
lunch sdk_phone_x86_64
m V2fSensorInjector sensorservice -j"$(nproc)"

echo "Build complete. Launch the configured AVD with: emulator"
