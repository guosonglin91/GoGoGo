#!/usr/bin/env bash
set -euo pipefail

EXPECTED_BRANCH="aosp-android-latest-release"
ROOT="$(pwd)"
PATCH_SOURCE="${1:-}"
INJECTOR_SOURCE="${2:-}"

test -d "$ROOT/frameworks/native/services/sensorservice"
test -d "$ROOT/build/make"

CURRENT_BRANCH="$(repo info . 2>/dev/null | sed -n 's/.*Current revision: //p' | head -n1 || true)"
if [[ -n "$CURRENT_BRANCH" && "$CURRENT_BRANCH" != "$EXPECTED_BRANCH" ]]; then
  echo "Expected AOSP branch $EXPECTED_BRANCH; found $CURRENT_BRANCH" >&2
  exit 2
fi

if [[ -z "$PATCH_SOURCE" || -z "$INJECTOR_SOURCE" ]]; then
  echo "Usage: $0 /path/to/0001-v2f-register-step-test-sensors.patch /path/to/injector" >&2
  exit 2
fi

pushd frameworks/native >/dev/null
git apply --check "$PATCH_SOURCE"
git apply "$PATCH_SOURCE"
popd >/dev/null

rm -rf packages/apps/V2fSensorInjector
mkdir -p packages/apps/V2fSensorInjector
cp -R "$INJECTOR_SOURCE"/. packages/apps/V2fSensorInjector/

echo "V2-F AOSP lab assets prepared on $EXPECTED_BRANCH"
