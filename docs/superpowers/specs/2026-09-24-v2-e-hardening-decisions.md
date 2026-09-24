# V2-E Hardening Decisions — Evidence, Isolation, and Acceptance

**Date:** 2026-09-24  
**Branch:** `route-playback-v1`  
**Status:** Normative addendum for V2-E implementation  
**Applies to:** `2026-09-24-v2-e-compatibility-harness-design.md` and all V2-E implementation plans.

## 1. Why this addendum exists

The current V2-E design is sound in its producer/consumer isolation, but implementation must close several evidence-contract gaps before code is expanded further.

This addendum freezes the missing contracts for:

- producer/consumer evidence separation;
- common monotonic clock semantics;
- Gate-L producer/consumer correlation;
- official Gate-R time-window alignment;
- crash-safe evidence finalization;
- evidence schema/version/integrity metadata;
- CI acceptance ordering.

No third-party application adaptation or synthetic hardware-sensor injection is introduced.

## 2. Frozen evidence topology

Runtime evidence stays physically separated by application.

```text
GoGoGo Producer
  app-specific storage
    v2e/producer/session_<id>/
      producer_location.csv
      synthetic_motion.csv
      producer_meta.json
      evidence_manifest.json

RunnerProbe
  app-specific storage
    v2e/consumer/session_<id>/
      location_events.csv
      step_detector_events.csv
      step_counter_events.csv
      accel_summary.csv
      gyro_summary.csv
      runnerprobe_meta.json
      evidence_manifest.json

Host-side analyzer
  post-session only
    session_<id>/
      producer/
      consumer/
      external_ground_truth.json   # only when Gate-R is executed
      session_summary.json
      gate_report.txt
```

The producer and consumer MUST NOT read each other's runtime files. The merged canonical session directory exists only after recording is complete.

## 3. Evidence schema version

Every metadata file and every evidence manifest MUST contain:

```text
schema_version = "v2e-1"
```

The analyzer rejects an unknown schema version with `UNSUPPORTED_SCHEMA_VERSION`.

CSV formats are versioned by the session metadata/manifest; column reordering or semantic changes require a new schema version.

## 4. Common clock contract

All interval logic uses Android monotonic time.

Producer records:

- route/publication event elapsed-realtime nanoseconds;
- location object's elapsed-realtime nanoseconds when available;
- wall time only as metadata.

RunnerProbe records:

- `Location.getElapsedRealtimeNanos()`;
- `SensorEvent.timestamp`;
- app-side arrival `SystemClock.elapsedRealtimeNanos()`;
- wall time only as metadata.

Producer and consumer traces are comparable only when recorded during the same device boot. Metadata MUST therefore include a per-session boot marker sufficient to detect an intervening reboot. A reboot between producer and consumer evidence invalidates post-session correlation with `CLOCK_DOMAIN_MISMATCH`.

The analyzer never uses wall-clock timestamps for cadence, latency, or route-alignment calculations.

## 5. Gate-L requires producer publication evidence

Gate-L must prove more than "the consumer received moving locations."

GoGoGo therefore records `producer_location.csv` at the point where ServiceGo publishes the test location into Android LocationManager.

Required columns:

```text
session_id,provider,publication_elapsed_ns,location_elapsed_ns,latitude,longitude,speed_mps,bearing_deg,accuracy_m
```

RunnerProbe continues to record:

```text
session_id,provider,location_elapsed_ns,arrival_elapsed_ns,wall_time_ms,latitude,longitude,speed_mps,bearing_deg,accuracy_m,is_mock
```

Gate-L post-session analysis compares producer publication evidence with RunnerProbe observations using provider plus monotonic time.

Hard PASS requirements remain structural:

- producer publication trace is non-empty;
- consumer location trace is non-empty;
- both traces are monotonic;
- at least one supported provider is observed;
- coordinates move during a session that claims route motion;
- consumer reports the Android mock/test-location flag for the synthetic route;
- no direct producer-to-consumer runtime data channel exists.

Spatial error, callback lag, publication-to-observation coverage, and provider-specific loss rates are reported as metrics in V2-E. Numeric pass thresholds for those metrics are not frozen until at least one stock-device calibration session has been captured; this avoids inventing unsupported tolerance values.

## 6. Gate-R official-window protocol

A 60 s official Gate-R run is not defined merely by "record for about one minute."

RunnerProbe MUST use an armed-recording state:

```text
IDLE
  -> ARMING
  -> READY
  -> RECORDING
  -> FINALIZING
  -> COMPLETE / ERROR
```

ARMING starts sensor subscriptions. READY requires:

- required runtime permission granted;
- Step Detector presence known;
- Step Counter presence known;
- at least one valid Step Counter baseline when the counter is present.

The official measurement interval begins only after READY and an explicit start action/cue.

The session records exact `official_start_elapsed_ns` and `official_end_elapsed_ns`. Official Gate-R counts use sensor-event timestamps inside that interval, not callback-arrival timestamps.

External ground truth is stored as `external_ground_truth.json` with at least:

```json
{
  "schema_version": "v2e-1",
  "session_id": "...",
  "count": 0,
  "method": "manual|video",
  "official_start_elapsed_ns": 0,
  "official_end_elapsed_ns": 0,
  "notes": ""
}
```

A ground-truth record with mismatched session ID or interval is rejected.

## 7. Callback batching semantics

For Step Detector and Step Counter:

- sensor timestamp is authoritative for event placement in the official interval;
- arrival elapsed time is used only to estimate delivery latency/batching;
- delayed delivery alone does not imply missing steps.

RunnerProbe metadata records each sensor's:

- name;
- vendor;
- version;
- wake-up status when available;
- reporting mode when available.

This prevents a device-specific batching pattern from being misclassified as a data-integrity failure.

## 8. Crash-safe finalization

Evidence files MUST NOT appear finalized while writes are still in flight.

Writers use temporary names:

```text
*.csv.partial
*.json.partial
```

Finalization order:

1. stop accepting new events;
2. drain the single writer queue;
3. flush and fsync/close all streams;
4. write final metadata;
5. compute SHA-256 and byte size for all finalized evidence payloads;
6. write `evidence_manifest.json.partial`;
7. atomically rename payloads to final names where supported;
8. rename the manifest last.

The existence of final `evidence_manifest.json` is the completion marker.

A directory without a final manifest is incomplete and MUST NOT be treated as a successful session.

Required error codes include:

- `TRACE_WRITE_FAILURE`
- `TRACE_CLOSE_TIMEOUT`
- `TRACE_CLOSE_FAILURE`
- `SESSION_NOT_FINALIZED`
- `EVIDENCE_HASH_MISMATCH`

## 9. Evidence manifest

Each producer/consumer manifest contains at least:

```json
{
  "schema_version": "v2e-1",
  "session_id": "...",
  "role": "producer|consumer",
  "finalized": true,
  "files": [
    {
      "name": "location_events.csv",
      "bytes": 0,
      "sha256": "..."
    }
  ]
}
```

The host analyzer verifies the manifest before evaluating any gate.

## 10. Session metadata minimum contract

Both producer and consumer metadata include:

- `schema_version`;
- `session_id`;
- app version;
- source commit SHA when available from CI/build metadata;
- device model;
- Android release/API level;
- session start/end elapsed time;
- boot marker;
- finalization status;
- machine-readable error codes.

Producer metadata additionally includes the motion-model configuration and seed.

Consumer metadata additionally includes permission state, provider state, sensor descriptors, lifecycle events, and official Gate-R interval.

## 11. Recorder close semantics

Metadata must be written after prior queued writes have been drained enough to know whether any write failed.

A recorder MUST NOT serialize a success status on the caller thread and then discover a queued write failure afterward.

`close()` therefore owns final metadata generation and returns a final immutable status.

Duplicate close remains idempotent.

## 12. Storage/export isolation

Both Android apps write only to their own app-specific storage during a live session.

User-visible export happens only after finalization, via a deliberate export/share action.

RunnerProbe never reads producer output and GoGoGo never reads RunnerProbe output at runtime.

## 13. CI contract

Before physical-device acceptance, CI MUST run in this order:

1. GoGoGo JVM unit tests;
2. RunnerProbe JVM unit tests;
3. Python analyzer unit tests;
4. GoGoGo debug APK assembly;
5. RunnerProbe debug APK assembly;
6. upload both APKs as separate artifacts.

A documentation-only commit may still run the full contract; CI success means all currently implemented automated checks pass, not that physical Gate-L/Gate-R/Gate-S acceptance has been completed.

## 14. Revised implementation order

V2-E implementation proceeds in this order:

1. **Phase 0 — Evidence contract primitives**
   - schema version;
   - session ID validation;
   - manifest/hash/finalization semantics;
   - common metadata fields.
2. **Phase 1 — RunnerProbe module + pure domain logic**
   - independent APK;
   - cadence tracker;
   - Step Counter tracker.
3. **Phase 2 — RunnerProbe recording service**
   - permissions;
   - foreground service;
   - lifecycle;
   - crash-safe traces.
4. **Phase 3 — Producer evidence**
   - `producer_location.csv`;
   - HumanMotionModel;
   - synthetic recorder;
   - producer manifest.
5. **Phase 4 — Host analyzer**
   - manifest validation;
   - Gate-L correlation;
   - Gate-R evaluation;
   - Gate-S evaluation;
   - merged report.
6. **Phase 5 — CI artifacts**
   - both APKs + analyzer tests.
7. **Phase 6 — Stock-phone acceptance**
   - Gate-L calibration;
   - 60 s Gate-R ground-truth run;
   - Gate-S trace;
   - background/screen-off lifecycle run.

## 15. Acceptance distinction

Three statuses are always kept separate:

- **Automated CI:** source/tests/build integrity.
- **Device harness readiness:** both APKs install and sessions finalize/export.
- **Physical gate acceptance:** Gate-L/Gate-R/Gate-S results from the target phone.

No CI build result may be described as physical compatibility PASS.

## 16. Non-goals remain unchanged

V2-E still does not implement:

- third-party application adaptation;
- anti-cheat bypass;
- synthetic Android hardware-sensor injection;
- root/Magisk dependencies;
- custom sensor HAL/AOSP modifications.
