#!/usr/bin/env bash
set -eo pipefail

usage() {
  echo "usage: $0 ADB_SERIAL SESSION_ID CADENCE_SPM" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage

SERIAL="$1"
SESSION_ID="$2"
CADENCE="$3"

case "$CADENCE" in
  150|165|180) ;;
  *)
    echo "BCT-1 cadence must be 150, 165, or 180 spm" >&2
    exit 2
    ;;
esac

[[ -n "$SERIAL" ]] || usage
[[ "$SESSION_ID" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo "invalid session id" >&2
  exit 2
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ -z "$ADB_BIN" ]]; then ADB_BIN="adb"; fi
if [[ -z "$PYTHON_BIN" ]]; then PYTHON_BIN="python3"; fi
if [[ -z "$EVIDENCE_ROOT" ]]; then EVIDENCE_ROOT="$REPO_ROOT/evidence/bct1"; fi
if [[ -z "$COUNTER_BASELINE" ]]; then COUNTER_BASELINE="10000"; fi

SESSION_DIR="$EVIDENCE_ROOT/$SESSION_ID"
INJECTOR_PACKAGE="com.zcshou.v2finjector"
INJECTOR_COMPONENT="$INJECTOR_PACKAGE/.InjectionService"
LOGCAT_PID=""

if [[ -e "$SESSION_DIR" ]]; then
  echo "session directory already exists: $SESSION_DIR" >&2
  exit 2
fi

adb_cmd() {
  "$ADB_BIN" -s "$SERIAL" "$@"
}

adb_cmd get-state >/dev/null
DEBUGGABLE="$(adb_cmd shell getprop ro.debuggable | tr -d '\r')"
if [[ "$DEBUGGABLE" != "1" ]]; then
  echo "BCT-1 AOSP lab requires a debuggable device; ro.debuggable=$DEBUGGABLE" >&2
  exit 3
fi

SENSOR_DUMP="$(adb_cmd shell dumpsys sensorservice)"
grep -q "V2F Virtual Step Detector" <<<"$SENSOR_DUMP" || {
  echo "V2F Virtual Step Detector is not registered" >&2
  exit 3
}
grep -q "V2F Virtual Step Counter" <<<"$SENSOR_DUMP" || {
  echo "V2F Virtual Step Counter is not registered" >&2
  exit 3
}

mkdir -p "$SESSION_DIR"
printf '%s\n' "$SENSOR_DUMP" > "$SESSION_DIR/sensorservice_before.txt"

"$PYTHON_BIN" "$REPO_ROOT/bct1/generate_timeline.py"   --cadence "$CADENCE"   --duration 90   --counter-baseline "$COUNTER_BASELINE"   --output-dir "$SESSION_DIR"

cleanup() {
  set +e
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
    wait "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  adb_cmd shell dumpsys sensorservice enable     >"$SESSION_DIR/sensorservice_cleanup.txt" 2>&1 || true
}
trap cleanup EXIT INT TERM

adb_cmd logcat -c
adb_cmd logcat -v threadtime V2fSensorInjector:I '*:S'   > "$SESSION_DIR/injector_logcat.txt" 2>&1 &
LOGCAT_PID="$!"

adb_cmd shell dumpsys sensorservice   hal_bypass_replay_data_injection "$INJECTOR_PACKAGE"   > "$SESSION_DIR/injection_mode.txt"

adb_cmd shell am startservice   -n "$INJECTOR_COMPONENT"   -a com.zcshou.v2finjector.action.BASELINE   --ei counter_baseline "$COUNTER_BASELINE"   > "$SESSION_DIR/baseline_command.txt"

if [[ "$BCT1_AUTO_CONFIRM" != "1" ]]; then
  echo
  echo "RunnerProbe should now have a Step Counter baseline."
  echo "1) Arm RunnerProbe with Session ID: $SESSION_ID"
  echo "2) Wait for READY, then tap Begin Official Recording"
  echo "3) Open the isolated AUT observation screen"
  read -r -p "Press Enter only when RunnerProbe is RECORDING and AUT is ready: " _
fi

adb_cmd shell am startservice   -n "$INJECTOR_COMPONENT"   -a com.zcshou.v2finjector.action.RUN   --es session_id "$SESSION_ID"   --ei cadence_spm "$CADENCE"   --ei duration_s 90   --ei counter_baseline "$COUNTER_BASELINE"   > "$SESSION_DIR/run_command.txt"

if [[ "$BCT1_SKIP_WAIT" != "1" ]]; then
  sleep 92
fi

adb_cmd shell dumpsys sensorservice > "$SESSION_DIR/sensorservice_after.txt"
FINGERPRINT="$(adb_cmd shell getprop ro.build.fingerprint | tr -d '\r')"
ANDROID_RELEASE="$(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"

if [[ -z "$AUT_PACKAGE" ]]; then
  read -r -p "AUT package name: " AUT_PACKAGE
fi
[[ -n "$AUT_PACKAGE" ]] || {
  echo "AUT package is required" >&2
  exit 4
}

if [[ -z "$AUT_VERSION" ]]; then
  AUT_VERSION="$(adb_cmd shell dumpsys package "$AUT_PACKAGE"     | sed -n 's/.*versionName=//p' | head -n1 | tr -d '\r')"
fi
if [[ -z "$AUT_VERSION" ]]; then AUT_VERSION="unknown"; fi

AUT_OBSERVATION="$BCT1_AUT_OBSERVATION"
if [[ -z "$AUT_OBSERVATION" ]]; then
  read -r -p "AUT observation [observed/not_observed/environment_unsupported]: " AUT_OBSERVATION
fi
case "$AUT_OBSERVATION" in
  observed|not_observed|environment_unsupported) ;;
  *)
    echo "invalid AUT observation" >&2
    exit 4
    ;;
esac

AUT_CADENCE=""
if [[ "$AUT_OBSERVATION" == "observed" ]]; then
  AUT_CADENCE="$BCT1_AUT_CADENCE"
  if [[ -z "$AUT_CADENCE" ]]; then
    read -r -p "Stable AUT cadence (spm): " AUT_CADENCE
  fi
  [[ "$AUT_CADENCE" =~ ^[0-9]+([.][0-9]+)?$ ]] || {
    echo "observed AUT cadence must be numeric" >&2
    exit 4
  }
fi

if [[ -z "$AUT_EVIDENCE_FILE" ]]; then AUT_EVIDENCE_FILE="aut_screen_recording.mp4"; fi
SOURCE_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo UNKNOWN)"

export SESSION_DIR SESSION_ID CADENCE FINGERPRINT ANDROID_RELEASE
export AUT_PACKAGE AUT_VERSION AUT_OBSERVATION AUT_CADENCE AUT_EVIDENCE_FILE SOURCE_COMMIT

"$PYTHON_BIN" - <<'PY'
import json
import os
from pathlib import Path

root = Path(os.environ["SESSION_DIR"])
observation = os.environ["AUT_OBSERVATION"]
cadence_text = os.environ["AUT_CADENCE"]

aut = {
    "session_id": os.environ["SESSION_ID"],
    "aut_package": os.environ["AUT_PACKAGE"],
    "aut_version": os.environ["AUT_VERSION"],
    "observation": observation,
    "stable_cadence_spm": float(cadence_text) if observation == "observed" else None,
    "evidence_file": os.environ["AUT_EVIDENCE_FILE"],
    "notes": "",
}
manifest = {
    "schema_version": "bct1-1",
    "session_id": os.environ["SESSION_ID"],
    "execution_path": "aosp_emulator",
    "target_cadence_spm": int(os.environ["CADENCE"]),
    "duration_s": 90,
    "official_start_s": 30,
    "official_end_s": 90,
    "android_build_fingerprint": os.environ["FINGERPRINT"],
    "android_release": os.environ["ANDROID_RELEASE"],
    "runnerprobe_package": "com.zcshou.runnerprobe",
    "runnerprobe_source_commit": os.environ["SOURCE_COMMIT"],
    "aut_package": os.environ["AUT_PACKAGE"],
    "aut_version": os.environ["AUT_VERSION"],
    "aut_observation": observation,
}
(root / "aut_observation.json").write_text(
    json.dumps(aut, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
(root / "system_run.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

echo "BCT-1 run captured in: $SESSION_DIR"
echo "Now stop/finalize RunnerProbe and export runnerprobe_$SESSION_ID.zip"
