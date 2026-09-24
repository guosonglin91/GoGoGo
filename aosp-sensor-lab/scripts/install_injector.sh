#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:?usage: install_injector.sh ADB_SERIAL}"
APK="$(find out/target/product -path '*/system/priv-app/V2fSensorInjector/V2fSensorInjector.apk' -print -quit)"
test -n "$APK"

adb -s "$SERIAL" root
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" remount
adb -s "$SERIAL" push "$APK" /system/priv-app/V2fSensorInjector/V2fSensorInjector.apk
adb -s "$SERIAL" push   packages/apps/V2fSensorInjector/privapp-permissions-com.zcshou.v2finjector.xml   /system/etc/permissions/privapp-permissions-com.zcshou.v2finjector.xml
adb -s "$SERIAL" reboot
adb -s "$SERIAL" wait-for-device

adb -s "$SERIAL" shell dumpsys sensorservice | grep -E 'V2F Virtual Step (Detector|Counter)'
