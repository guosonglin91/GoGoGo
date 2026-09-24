# Project Context

## Glossary

### BCT-1 — Baseline Compatibility Test, Round 1
The first V2-F experiment. It tests whether controlled synthetic Android step events can traverse the Android sensor stack and be observed by RunnerProbe and an unmodified target running application.

### AUT — App Under Test
The unmodified third-party running application used as the black-box compatibility consumer in BCT-1. BCT-1 does not modify the AUT or bypass its controls.

### Reference Observer
RunnerProbe acting as the independent baseline consumer of standard Android APIs. It determines whether the Android sensor path itself is working before conclusions are drawn about AUT behavior.

### Synthetic System Step Events
Controlled step-detector and step-counter test events with Android-compatible semantics that are delivered through the Android system sensor path in the research environment.

### Capability Gate
The short, reversible Phase-0 probe on the stock OPPO Find X8. Its purpose is to decide whether the stock system can support the required controlled sensor test path without root, unlocking, flashing, or persistent modification.

### Android Reference Layer
The RunnerProbe-observed system sensor path used for BCT-1 acceptance. It is distinct from the AUT compatibility result.

### AUT Compatibility
Whether the unmodified AUT visibly consumes or exposes cadence derived from the tested Android step-sensor path. Absence of AUT output does not by itself prove that the Android reference layer failed.
