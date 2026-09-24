# V2-E Analysis and Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add host-side analysis that merges independent producer and RunnerProbe evidence, evaluates Gate-L/Gate-R/Gate-S reproducibly, writes machine-readable/session-readable reports, and provides an end-to-end stock-phone acceptance procedure.

**Architecture:** A Python-standard-library-only analyzer reads finalized producer and consumer evidence after the run; it never participates in live recording. Gate evaluators are pure functions with fixture-based unit tests. The analyzer creates the canonical merged `session_<id>/` directory required by the spec and writes `session_summary.json` plus `gate_report.txt`.

**Tech Stack:** Python 3 standard library (`csv`, `json`, `argparse`, `pathlib`, `statistics`, `shutil`, `unittest`) plus existing GitHub Actions Ubuntu runner.

**Spec:** `docs/superpowers/specs/2026-09-24-v2-e-compatibility-harness-design.md`

## Global Constraints

- Analysis happens only after recording; it must not provide a runtime channel from GoGoGo to RunnerProbe.
- Ground truth for Gate-R is external manual/video step count, never Android Step Detector/Counter itself.
- Gate-R official sessions are at least 60 s.
- Real cadence uses Step Detector timestamps; Step Counter is accumulated cross-check.
- Detector/counter agreement criterion is `abs(detector_count - counter_delta) <= max(2, ceil(0.03 * detector_count))`.
- Ground-truth step-count classification is PASS <= 3% relative error, WARN > 3% and <= 5%, FAIL > 5%.
- Gate-L, Gate-R, and Gate-S retain separate statuses; no passing gate may hide another failed gate.
- Required failure classes include permission denied, provider unavailable, sensor absent, sensor present/no events, warm-up incomplete, callback batching, session interruption, counter discontinuity, and trace write failure.
- Final canonical session directory must contain the required raw/summary files from the approved spec.
- Analyzer must use monotonic timestamps for intervals; wall-clock is metadata only.

## Review Focus

- Empty/malformed CSV input must produce a deterministic FAIL/error code rather than a stack trace or accidental PASS.
- Ground-truth value 0 or missing for an official Gate-R run must be rejected before relative-error division.
- Producer and consumer evidence with mismatched session IDs must not be merged silently.
- Duplicate/non-monotonic event timestamps must be reported as data-integrity failures even if final counts appear plausible.
- Threshold boundary values 3.00% and 5.00% must classify exactly as specified.

---

### Task 1: Add analyzer package, fixture layout, and typed status model

**Files:**
- Create: `motion-analysis/analyze_session.py`
- Create: `motion-analysis/v2e/__init__.py`
- Create: `motion-analysis/v2e/models.py`
- Create: `motion-analysis/v2e/io.py`
- Create: `motion-analysis/tests/test_io.py`
- Create: `motion-analysis/tests/fixtures/minimal/producer/synthetic_motion.csv`
- Create: `motion-analysis/tests/fixtures/minimal/consumer/location_events.csv`
- Create: `motion-analysis/tests/fixtures/minimal/consumer/step_detector_events.csv`
- Create: `motion-analysis/tests/fixtures/minimal/consumer/step_counter_events.csv`
- Create: `motion-analysis/tests/fixtures/minimal/consumer/accel_summary.csv`
- Create: `motion-analysis/tests/fixtures/minimal/consumer/gyro_summary.csv`

**Interfaces:**
- Consumes: finalized producer and consumer directories.
- Produces:
  - `GateStatus` enum values PASS/WARN/FAIL/NOT_RUN;
  - validated row objects/dicts;
  - deterministic `EvidenceError(code, message)`.

- [ ] **Step 1: Write failing CSV/session validation tests**

Use `unittest` and temporary directories:

```python
def test_rejects_mismatched_session_ids(self):
    with self.assertRaises(EvidenceError) as ctx:
        load_evidence(producer_dir, consumer_dir, "v2e_expected")
    self.assertEqual("SESSION_ID_MISMATCH", ctx.exception.code)

def test_rejects_missing_required_consumer_file(self):
    with self.assertRaises(EvidenceError) as ctx:
        load_consumer_evidence(incomplete_dir)
    self.assertEqual("MISSING_REQUIRED_FILE", ctx.exception.code)
```

- [ ] **Step 2: Run tests and verify failure**

```bash
python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
```

Expected: FAIL because analyzer modules do not exist.

- [ ] **Step 3: Implement status/error model and strict CSV loaders**

Define:

```python
class GateStatus(str, Enum):
    PASS = "PASS"
    WARN = "WARN"
    FAIL = "FAIL"
    NOT_RUN = "NOT_RUN"

class EvidenceError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message
```

CSV loaders must validate required headers, parse numeric fields explicitly, reject non-finite floats, and verify each row's `session_id` equals the CLI-provided canonical session ID.

- [ ] **Step 4: Add malformed-input tests**

Pin these cases:

- empty CSV after header;
- duplicate header name;
- non-numeric timestamp;
- NaN/Infinity in speed/cadence;
- session-id mismatch;
- missing required file.

Each case must return a named `EvidenceError`.

- [ ] **Step 5: Run analyzer tests**

```bash
python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add motion-analysis
git commit -m "feat: add V2-E evidence loaders"
```

---

### Task 2: Implement Gate-R evaluator with exact threshold semantics

**Files:**
- Create: `motion-analysis/v2e/gate_r.py`
- Create: `motion-analysis/tests/test_gate_r.py`

**Interfaces:**
- Consumes: detector rows, counter rows, external ground-truth steps, session duration, sensor metadata/error flags.
- Produces: `GateResult(status, metrics, error_codes, notes)`.

- [ ] **Step 1: Write failing PASS/WARN/FAIL boundary tests**

Use exact cases:

```python
def test_ground_truth_error_three_percent_is_pass(self):
    r = classify_step_error(observed=97, ground_truth=100)
    self.assertEqual(GateStatus.PASS, r)

def test_ground_truth_error_just_over_three_percent_is_warn(self):
    r = classify_step_error(observed=96, ground_truth=100)
    self.assertEqual(GateStatus.WARN, r)

def test_ground_truth_error_five_percent_is_warn(self):
    r = classify_step_error(observed=95, ground_truth=100)
    self.assertEqual(GateStatus.WARN, r)

def test_ground_truth_error_over_five_percent_is_fail(self):
    r = classify_step_error(observed=94, ground_truth=100)
    self.assertEqual(GateStatus.FAIL, r)
```

- [ ] **Step 2: Write failing detector/counter tolerance tests**

Use:

```python
def test_detector_counter_tolerance_uses_max_two_or_three_percent(self):
    self.assertTrue(detector_counter_agree(20, 18))
    self.assertFalse(detector_counter_agree(20, 17))
    self.assertTrue(detector_counter_agree(200, 194))
    self.assertFalse(detector_counter_agree(200, 193))
```

The 200-step tolerance is `max(2, ceil(6)) = 6`.

- [ ] **Step 3: Run Gate-R tests and verify failure**

```bash
python -m unittest motion-analysis/tests/test_gate_r.py -v
```

Expected: FAIL.

- [ ] **Step 4: Implement Gate-R evaluation**

Rules:

- ground truth must be integer > 0 for an official Gate-R run;
- duration < 60.0 s → FAIL with `SESSION_TOO_SHORT`;
- required sensor absent → FAIL `SENSOR_ABSENT`;
- confirmed motion/ground truth > 0 plus zero detector events → FAIL `SENSOR_PRESENT_NO_EVENTS`;
- any counter discontinuity row → FAIL `COUNTER_DISCONTINUITY`;
- non-monotonic detector timestamps → FAIL `NON_MONOTONIC_STEP_TIME`;
- detector and counter each get separate ground-truth relative-error metrics;
- overall Gate-R severity is the worst of detector accuracy, counter accuracy, cross-check, duration, and hard errors;
- preserved counts with delayed arrivals may add `CALLBACK_BATCHING` and at most WARN if no hard failure occurs.

- [ ] **Step 5: Add missing/zero ground-truth tests**

```python
with self.assertRaises(EvidenceError):
    evaluate_gate_r(..., ground_truth_steps=0, ...)
```

No division by zero is permitted.

- [ ] **Step 6: Run Gate-R tests**

```bash
python -m unittest motion-analysis/tests/test_gate_r.py -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add motion-analysis/v2e/gate_r.py motion-analysis/tests/test_gate_r.py
git commit -m "feat: evaluate Gate-R motion sensor evidence"
```

---

### Task 3: Implement Gate-S evaluator for synthetic timing consistency

**Files:**
- Create: `motion-analysis/v2e/gate_s.py`
- Create: `motion-analysis/tests/test_gate_s.py`

**Interfaces:**
- Consumes: `synthetic_motion.csv`.
- Produces: Gate-S status plus monotonicity, cadence/interval, jitter, and completeness metrics.

- [ ] **Step 1: Write failing synthetic consistency tests**

Fixture rows must validate:

```text
step_index strictly increments by 1
step_elapsed_ns strictly increases
interval_ns > 0
instantaneous_cadence_spm approximately equals 60e9 / interval_ns
target cadence in [100, 190]
jitter <= ±2% from base interval implied by target cadence
```

Use a relative numeric tolerance of `1e-6` for cadence/interval arithmetic after CSV decimal parsing.

- [ ] **Step 2: Add explicit failure tests**

Test each independently:

- duplicate step index;
- decreasing timestamp;
- zero/negative interval;
- cadence/interval mismatch;
- target cadence outside configured bounds;
- jitter > 2%;
- empty trace for a session that claims movement.

- [ ] **Step 3: Run tests and verify failure**

```bash
python -m unittest motion-analysis/tests/test_gate_s.py -v
```

Expected: FAIL.

- [ ] **Step 4: Implement Gate-S evaluator**

Return named error codes:

```text
STEP_INDEX_NON_MONOTONIC
STEP_TIME_NON_MONOTONIC
INVALID_STEP_INTERVAL
CADENCE_INTERVAL_MISMATCH
TARGET_CADENCE_OUT_OF_RANGE
JITTER_BOUND_EXCEEDED
SYNTHETIC_TRACE_EMPTY
```

Any listed integrity error is FAIL.

- [ ] **Step 5: Run tests**

```bash
python -m unittest motion-analysis/tests/test_gate_s.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add motion-analysis/v2e/gate_s.py motion-analysis/tests/test_gate_s.py
git commit -m "feat: evaluate Gate-S synthetic motion evidence"
```

---

### Task 4: Implement Gate-L location compatibility evaluator

**Files:**
- Create: `motion-analysis/v2e/gate_l.py`
- Create: `motion-analysis/tests/test_gate_l.py`

**Interfaces:**
- Consumes: RunnerProbe `location_events.csv`.
- Produces: Gate-L status plus provider, monotonicity, coordinate-motion, speed, bearing, and timing metrics.

- [ ] **Step 1: Write failing Gate-L tests**

PASS fixture must have:

- at least two location callbacks;
- strictly non-decreasing location elapsed-realtime timestamps;
- at least one coordinate change above a tiny floating-point epsilon;
- finite speed and bearing values when present;
- at least one GPS or NETWORK provider row.

Failing fixtures:

- no rows;
- non-monotonic elapsed timestamp;
- all coordinates identical for a route session that claims motion;
- NaN coordinate/speed;
- provider value outside `gps/network`.

- [ ] **Step 2: Run tests and verify failure**

```bash
python -m unittest motion-analysis/tests/test_gate_l.py -v
```

Expected: FAIL.

- [ ] **Step 3: Implement Gate-L evaluator**

Use geodesic distance only to establish that motion occurred; a simple haversine helper is sufficient. Do not compare against GoGoGo internal route state at runtime.

Named failures:

```text
LOCATION_TRACE_EMPTY
LOCATION_TIME_NON_MONOTONIC
LOCATION_NO_MOTION
LOCATION_VALUE_INVALID
LOCATION_PROVIDER_INVALID
```

- [ ] **Step 4: Run tests**

```bash
python -m unittest motion-analysis/tests/test_gate_l.py -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add motion-analysis/v2e/gate_l.py motion-analysis/tests/test_gate_l.py
git commit -m "feat: evaluate Gate-L location evidence"
```

---

### Task 5: Build canonical merged session output and human-readable report

**Files:**
- Create: `motion-analysis/v2e/report.py`
- Modify: `motion-analysis/analyze_session.py`
- Create: `motion-analysis/tests/test_report.py`

**Interfaces:**
- Consumes: producer directory, consumer directory, canonical session ID, optional Gate-R ground-truth steps, session duration/lifecycle metadata.
- Produces:
  - canonical `session_<id>/`;
  - copied raw files;
  - `session_summary.json`;
  - `gate_report.txt`.

- [ ] **Step 1: Write the failing end-to-end fixture test**

Invoke the CLI against fixed fixture directories:

```bash
python motion-analysis/analyze_session.py \
  --session-id v2e_fixture_001 \
  --producer motion-analysis/tests/fixtures/full/producer \
  --consumer motion-analysis/tests/fixtures/full/consumer \
  --ground-truth-steps 120 \
  --duration-seconds 60 \
  --output build/v2e
```

Test that the final directory contains:

```text
location_events.csv
step_detector_events.csv
step_counter_events.csv
accel_summary.csv
gyro_summary.csv
synthetic_motion.csv
session_summary.json
gate_report.txt
```

- [ ] **Step 2: Define exact JSON top-level schema**

```json
{
  "session_id": "v2e_fixture_001",
  "duration_seconds": 60.0,
  "ground_truth_steps": 120,
  "gate_l": {"status": "PASS", "metrics": {}, "error_codes": []},
  "gate_r": {"status": "PASS", "metrics": {}, "error_codes": []},
  "gate_s": {"status": "PASS", "metrics": {}, "error_codes": []},
  "lifecycle_events": [],
  "device": {},
  "sensors": {}
}
```

No single overall PASS may replace the three gate statuses.

- [ ] **Step 3: Implement deterministic report generation**

`gate_report.txt` must contain, in this order:

```text
SESSION
GATE-L
GATE-R
GATE-S
ERROR CODES
NOTES
```

Sort error codes and metric keys so repeated analysis of identical evidence produces byte-stable JSON/report output except for no timestamps generated by the analyzer itself.

- [ ] **Step 4: Prevent partial output on failure**

Write into `session_<id>.tmp/`, validate/copy all required files, write summaries, then atomically replace/rename to `session_<id>/` only when analysis finishes successfully. On analysis failure, leave no completed canonical session directory.

- [ ] **Step 5: Run report/end-to-end tests**

```bash
python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add motion-analysis
git commit -m "feat: generate canonical V2-E gate reports"
```

---

### Task 6: Add CI coverage for host-side analysis

**Files:**
- Modify: `.github/workflows/route-playback-debug.yml`

**Interfaces:**
- Consumes: repository Python analyzer/tests.
- Produces: CI gate for analyzer behavior before APK artifacts are published.

- [ ] **Step 1: Add analyzer unit tests before Android assembly**

Add:

```yaml
- name: Run V2-E Analysis Tests
  run: python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
```

Place it after checkout/setup and before APK assembly.

- [ ] **Step 2: Run all local verification commands**

```bash
python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :runnerprobe:testDebugUnitTest
./gradlew :app:assembleDebug :runnerprobe:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/route-playback-debug.yml
git commit -m "ci: verify V2-E analysis"
```

---

### Task 7: Execute the full stock-phone V2-E acceptance procedure

**Files:**
- Create: `docs/v2e/end-to-end-acceptance.md`

**Interfaces:**
- Consumes: installed GoGoGo and RunnerProbe APKs, one physical stock non-root Android phone, manual/video ground truth, exported producer/consumer evidence.
- Produces: one canonical analyzed session and explicit Gate-L/Gate-R/Gate-S statuses.

- [ ] **Step 1: Verify execution preconditions**

Before any implementation run, confirm:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
```

Expected before executing these plans: clean worktree on `route-playback-v1`. If local changes or unpushed commits exist, reconcile them before applying plan tasks so the remote design/plan branch is not overwritten.

- [ ] **Step 2: Choose one canonical session ID and enter it in both apps**

Example:

```text
v2e_20260924_001
```

The same exact ID must appear in producer and consumer raw rows.

- [ ] **Step 3: Run Gate-L**

Start RunnerProbe recording, start a GoGoGo route, and record at least 20–30 seconds.

Expected: RunnerProbe receives standard Android location callbacks with changing coordinates and monotonic timestamps.

- [ ] **Step 4: Run Gate-R independently using real physical motion**

Stop route playback if it would confuse the operator, then conduct a separately identified 60-second physical walking/running session while RunnerProbe records. Count steps manually or from video.

Record the exact external count. The Android detector/counter values are observations, not ground truth.

- [ ] **Step 5: Exercise lifecycle transitions during a dedicated Gate-R session**

Use:

```text
FOREGROUND → BACKGROUND → SCREEN_OFF → SCREEN_ON → FOREGROUND_RESTORE
```

Confirm session finalization still succeeds. Delivery batching may become WARN; silent loss is not acceptable.

- [ ] **Step 6: Run Gate-S producer trace**

Run GoGoGo route playback long enough to produce a useful synthetic trace, including one pause/resume cycle, then stop and export.

- [ ] **Step 7: Analyze the exported directories**

```bash
python motion-analysis/analyze_session.py \
  --session-id v2e_20260924_001 \
  --producer <producer-session-dir> \
  --consumer <runnerprobe-session-dir> \
  --ground-truth-steps <manual-or-video-count> \
  --duration-seconds 60 \
  --output build/v2e
```

The angle-bracket values here are operator inputs from the just-completed test, not code placeholders: replace them with the actual exported directory paths and counted integer before execution.

- [ ] **Step 8: Inspect the canonical results**

Verify:

```text
build/v2e/session_v2e_20260924_001/
├── location_events.csv
├── step_detector_events.csv
├── step_counter_events.csv
├── accel_summary.csv
├── gyro_summary.csv
├── synthetic_motion.csv
├── session_summary.json
└── gate_report.txt
```

Confirm Gate-L/Gate-R/Gate-S are reported separately.

- [ ] **Step 9: Document observed PASS/WARN/FAIL reasons without committing personal route traces**

Write the reusable procedure and example redacted report structure to `docs/v2e/end-to-end-acceptance.md`. Do not commit raw location traces from personal/device testing.

- [ ] **Step 10: Commit**

```bash
git add docs/v2e/end-to-end-acceptance.md
git commit -m "docs: add V2-E end-to-end acceptance procedure"
```

---

### Task 8: Final verification against the approved spec

**Files:**
- Read: `docs/superpowers/specs/2026-09-24-v2-e-compatibility-harness-design.md`
- Read: all files changed by the three V2-E plans

**Interfaces:**
- Consumes: completed implementation and all test outputs.
- Produces: evidence-based completion decision; no code change unless a discovered gap requires one.

- [ ] **Step 1: Run complete automated verification**

```bash
python -m unittest discover -s motion-analysis/tests -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :runnerprobe:testDebugUnitTest
./gradlew :app:assembleDebug :runnerprobe:assembleDebug
```

Expected: all PASS.

- [ ] **Step 2: Check every spec completion criterion explicitly**

Verify the implementation/evidence demonstrates all eight criteria in spec section 18, one by one. Do not infer device behavior from unit tests.

- [ ] **Step 3: Search for forbidden coupling**

Run:

```bash
grep -R "com\.zcshou\.gogogo" runnerprobe/src/main/java || true
grep -R "SyntheticMotion" runnerprobe/src/main/java || true
```

Expected: no RunnerProbe dependency on GoGoGo internals or producer synthetic-motion classes.

- [ ] **Step 4: Search for unfinished plan markers in product code/docs**

```bash
grep -R -n -E 'TODO|TBD|implement later' runnerprobe app/src/main/java/com/zcshou/motion motion-analysis docs/v2e || true
```

Expected: no unresolved implementation placeholders introduced by V2-E.

- [ ] **Step 5: Review Git history and working tree**

```bash
git status --short
git log --oneline --decorate -12
```

Expected: clean worktree and small task-scoped commits matching the plan.

- [ ] **Step 6: Only then claim V2-E complete**

Completion requires both automated verification and the physical-device acceptance evidence. A green CI build alone is insufficient.
