# Breadcrumb locomotion engine

This document tracks the Android implementation inspired by LocoKit2. The source review is pinned to LocoKit2 commit `fc7636f4e23f506cef9b72d637799645e60b5966`; Breadcrumb's baseline was `7989feb5e35a9ad87f422e85ba87cb8f149994fc`.

## Port map

| ID | LocoKit2 source | Breadcrumb adaptation |
|---|---|---|
| LK-01 | `Models/LocomotionSample.swift`; `Managers/TimelineRecorder.swift` | Persist observations independently from timeline segments; separately model motion and recording state. |
| LK-02 | `Samplers/KalmanFilter.swift`; `Samplers/AltitudeKalmanFilter.swift` | Filter position and velocity in local metres with explicit uncertainty; retain raw fixes. |
| LK-03 | `Samplers/StationaryStateDetector.swift`; `Models/MovingState.swift` | Time-window evidence and an explicit uncertain state; combine GNSS with steps and inertial features. |
| LK-04 | `Samplers/AccelerometerSampler.swift`; `Samplers/StepsMonitor.swift` | Bounded sensor windows, normalized Android units, explicit missing-feature masks. |
| LK-05 | `Samplers/SleepModeDetector.swift`; `Managers/LocomotionManager.swift` | Low-power anchor and wake verification adapted to Android service and sensor lifecycles. |
| LK-06 | `Models/DriftProfile.swift`; `DriftProfile+Learning.swift`; `DriftProfile+Inflation.swift` | Learn directional drift only from confirmed visits and use robust local statistics. |
| LK-07 | `Samplers/UndergroundDetector.swift` | Represent degraded location regimes without inventing accuracy or treating absent speed as zero. |
| LK-08 | `ActivityTypes/CoreMLFeatureProvider.swift`; `ActivityClassifier.swift`; `ClassifierResults.swift` | Versioned feature vectors and uncertain distributions; missing model output remains absent. |
| LK-09 | `ActivityTypes/ActivityTypesModel.swift`; `ActivityTypesManager.swift` | On-device models trained from confirmed, trip-grouped examples. |
| LK-10 | `TimelineItem+ActivityType.swift`; `ItemSegment.swift` | Keep confirmed corrections separate from classifier predictions and attach them to sample ranges. |
| LK-11 | `Managers/TimelineProcessor.swift`; `TimelineProcessor+Merges.swift`; `MergeScores.swift` | Reconcile bounded neighbouring timeline segments and score candidate merges. |
| LK-12 | `TimelineProcessor+EdgeCleansing.swift`; `TimelineProcessor+EdgeHealing.swift`; `TimelineProcessor+Extraction.swift` | Refine boundaries while preserving user locks and visible data gaps. |
| LK-13 | `TimelineItemVisit.swift`; `Place+Stats.swift`; `Histogram.swift` | Stable visit geometry and local schedule evidence; habits influence sampling but never prove movement. |
| LK-14 | `RecordingStats.swift`; `DailyRecordingStats.swift`; `DebugLogger.swift`; `OperationRegistry.swift`; `StorageHealthMonitor.swift` | Structured decision history, bounded operation tracking, write health and runtime counters. |

LocoKit2's algorithms are references, not Android APIs. CoreLocation, CoreMotion, CoreML, CreateML, iOS sentinel values, and iOS timer behavior are not portable contracts. In particular, Android's missing speed is not evidence of zero speed. The reviewed LocoKit2 revision does not include ready-to-use bundled classifier weights, and some diagnostics described in source comments are not shipped as a harness.

## Architecture and invariants

`locomotion-core` is a platform-free Kotlin module. Android adapters own location and sensor subscriptions, foreground-service policy, permissions, and effect acknowledgements. The app data layer owns Room persistence, checkpoints, corrections, and the legacy-history adapter.

Observations are independent of timeline segments. Each keeps wall time and monotonic time with a boot/session identity. A late observation can be retained for analysis without rewinding the live filter. Requested Android resources and resources actually confirmed by the adapter are separate facts. Raw locations remain available alongside filtered estimates. Missing sensor fields remain missing.

Motion, stationary, and uncertain are distinct outcomes. Transport classification cannot veto physical movement. User-confirmed labels and boundaries outrank later predictions. A movement gap remains a gap when there is not enough evidence to draw a route. Demonstration and synthetic records never become training labels.

The initial implementation targets everyday transport and local learning. Models and diagnostic traces remain on the device. No downloaded transit schedule or route geometry is required.

## Implementation sequence

1. Define contracts and this source map.
2. Persist observations, corrections, provenance, and resumable checkpoints.
3. Add structured diagnostics and sensor/location evidence.
4. Add filtering, motion/stationary estimates, and confirmed-place drift.
5. Add adaptive capture and retrospective start/end refinement.
6. Materialize timeline segments and bridge existing history.
7. Add transport labels, correction history, and local training.
8. Add isolated replay, comparisons, backup support, and staged rollout.

Each feature lands in a small local commit with its focused verification. The full suite and static checks run after major layers. Real-device battery and sensor behavior require a Pixel Fold field pilot; emulator results do not establish either.

## Licensing

LocoKit2 is distributed under MPL-2.0. This first architecture document and the new Kotlin contracts are original descriptions and definitions, not copied source files. Any future code adapted from LocoKit2 must retain its upstream attribution and applicable MPL notices in the corresponding source file; changes to MPL-covered files must be distributed under MPL-2.0. The surrounding Breadcrumb application remains under its existing license. See [Mozilla's MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/) for file-level distribution guidance.

## Progress — 2026-10-13

Implemented and verified in the current branch:

- A pure-Kotlin module with observation contracts, a local-ENU Kalman filter, bounded sensor windows, time-confirmed movement/stationary decisions, directional place-drift profiles, and a deterministic observation pipeline.
- A live Android adapter for linear acceleration, gravity and available step sensors. The recording service now sends GPS, coarse Activity Recognition and sensor-window observations through the new pipeline, stores them locally, and publishes coordinate-free decisions to **Settings → Engine diagnostics**. The legacy ingest still owns track creation and remains the active recorder.
- A pure capture policy and a bounded 60-second departure prebuffer. These are not yet driving the Android resource requests or departure lifecycle.
- Timeline segment materialization for movement, visits and long data gaps, plus pure correction replay that preserves confirmed labels, locked boundaries, deletion and undo. These segments are not yet bridged into the existing history UI/database.
- Manual activity labels for motorcycle, scooter, bus, tram, train and metro. The platform's current broad `IN_VEHICLE` event does not automatically distinguish them.
- An experimental recorder-generation setting (Classic by default, Locomotion v2 on the next recording session). In v2, the new engine's fresh movement/stationary estimate supplies the movement corroboration consumed by the existing track recorder; the legacy recorder still owns track creation, location writes, and lifecycle.
- Explicit finished-track activity corrections now label eligible live engine observations locally. Training requires confirmed examples from multiple independent trips and whole-trip holdout validation. The versioned personal model is stored locally, rejected if corrupt or incompatible, and re-suggests labels for up to 500 recent unconfirmed timeline trips.
- Model suggestions are separate from the accepted activity label and appear in the timeline with confidence. A manual correction marks the label as user-confirmed and prevents future suggestions from overwriting it. Existing track labels and geometry survive migration 24→25.
- Classifier tests cover local model round-trip, schema rejection, and grouped validation; the database migration has a data-preservation test.
- Human relabels now also append durable `CONFIRM_TRANSPORT` or `CONFIRM_VISIT` events in migration 25→26. Manual track splits record paired, locked start/end boundaries in one operation; undo removes exactly those locks. A repository adapter exposes persisted assertions as core `UserCorrection` values. This records feedback safely, but the existing timeline is not yet regenerated through correction replay, so the durable ledger does not yet alter timeline segmentation.

Still pending from the plan: bridge persisted corrections into timeline regeneration, explicit stationary/boundary feedback beyond track split, full v2 ownership of track segmentation and lifecycle, applying adaptive capture effects to Android resource requests, transit-context features, underground detection, richer diagnostics, replay and fault lab, engine comparison, backup v3, and controlled rollout. Pixel Fold sensor and battery behavior has not been field-tested. The current v2 switch is experimental and affects movement corroboration only; it is not yet a complete replacement recorder.
