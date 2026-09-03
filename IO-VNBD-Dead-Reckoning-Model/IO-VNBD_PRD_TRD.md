# PRD / TRD — Phone-Based Vehicle Dead-Reckoning Model
**Project:** SIH — GPS-denied vehicle navigation using smartphone sensors
**Document purpose:** Give any AI assistant or teammate joining this project full context in one read. If you're an AI assistant reading this for the first time: read this whole document before writing or suggesting any code.

---

## 1. Problem Statement

Our mobile app provides vehicle navigation (used by a person driving or riding in a car, phone inside the vehicle). GPS signal can be lost or degraded (tunnels, urban canyons, jamming, poor reception), and standard dead-reckoning by directly integrating raw phone IMU (accelerometer/gyroscope) data drifts badly over time due to sensor noise.

**Goal:** Train a deep learning model that predicts short-interval vehicle displacement (change in position) using only the phone's own inertial sensors, to correct for this drift when GPS is unavailable — better than naive double-integration of raw IMU data.

This is **not**:
- A GPS+IMU sensor-fusion project (we're not fusing, we're predicting displacement from IMU alone for GPS-denied intervals)
- A driving-behavior classifier (no event/behavior labels involved)
- A pedestrian dead-reckoning project (see Section 6 on demo scope — walking is used only for a live pipeline demo, not the trained use case)

---

## 2. Background / Data Source

**Dataset:** [IO-VNBD](https://github.com/onyekpeu/IO-VNBD) — Inertial and Odometry Vehicle Navigation Benchmark Dataset, published by onyekpeu.

- Recorded on real roads in the UK, Nigeria, and France using a research vehicle.
- The vehicle tracking dataset was recorded using a research vehicle equipped with ego-motion sensors, including a GPS receiver, inertial navigation sensors, wheel-speed sensors amongst other sensors found on the car, as well as the inertial navigation sensors and GPS receiver in an Android smartphone, all sampling at 10Hz.
- Two parallel data sources exist for the same drives: vehicle (V) sensors (GPS, IMU, wheel-speed) and smartphone (S) sensors (GPS, IMU). Data is available in "Synchronised V and S datasets" and "Unsynchronised V and S Dataset" folders in the repo.
- Total dataset size: ~40 hours / 1,300km (vehicle-extracted data) and ~58 hours / 4,400 km (smartphone-recorded data).
- Diverse scenarios captured: traffic, roundabouts, hard-braking, on different road types (country roads, motorways, etc.).

**We use only the smartphone (S) sensor columns** — accelerometer, gyroscope, GPS. We deliberately do **not** use vehicle wheel-speed or other car-only sensor columns, even where present in synchronized files, because our deployed app will only ever have access to phone sensors, never car electronics/OBD data. Including wheel-speed in training would make offline results look better than what's achievable in the real app — this would be a misleading result.

**Scope decision:** Due to a few-days build timeline, we use 1-2 driving sequences from the smartphone data rather than the full dataset. This is a stated, intentional scoping decision for a hackathon deliverable, not a shortcut — it should be described honestly as "trained and validated on a subset of the IO-VNBD benchmark dataset" in any write-up or pitch.

---

## 3. Users & Stakeholders

- **End user:** a person driving/riding in a vehicle using our navigation app on their phone.
- **This deliverable's direct recipient:** our fullstack developer, who will integrate the trained model into the app. They are assumed to have no ML background — all handoff material must be written in plain language (see Section 8).
- **Context:** built for Smart India Hackathon (SIH). A live presentation/demo is required (see Section 6).

---

## 4. Functional Requirements

| ID | Requirement |
|----|-------------|
| FR1 | Given a window of phone accelerometer + gyroscope readings, the model outputs predicted displacement (dx, dy in meters, or distance + heading change) for that window. |
| FR2 | The model must be evaluated against naive (non-ML) INS integration on the same test data, with a clear quantitative improvement metric. |
| FR3 | A documented inference function and HTTP API must be delivered so the model can be called from the mobile app backend without any ML knowledge. |
| FR4 | A live demo must exist showing the sensor → model → prediction pipeline running in real time from an actual phone. |

---

## 5. Technical Design

### 5.1 Problem framing
Supervised sequence regression. Input: a sliding window of phone IMU readings. Label: ground-truth displacement over that window, derived from phone GPS positions converted to local Cartesian coordinates.

### 5.2 Input features
- Phone accelerometer and gyroscope only.
- Because phone orientation inside the vehicle is not fixed (mount, cupholder, pocket) — unlike a rigidly-mounted car sensor — raw x/y/z accelerometer values are not directly reliable across sessions. Feature engineering handles this via one of:
  - (a) orientation-invariant features: accelerometer magnitude + gyroscope z-axis (yaw rate), or
  - (b) rotating raw readings into a phone-independent frame using device attitude/quaternion data, if present in the dataset.
- Ground-truth GPS is used only as the training label, never as a model input (since the whole point is predicting displacement when GPS is unavailable).

### 5.3 Model architecture
1D-CNN feature extractor → LSTM → small fully-connected regression head, outputting (dx, dy) per window. This CNN+LSTM pattern is standard for IMU-based dead-reckoning (IONet-style). Kept intentionally small (2 conv layers, 1-2 LSTM layers, hidden size 64-128) to train in a reasonable time on the available hardware.

### 5.4 Preprocessing
- GPS → local Cartesian (meters), origin at first point.
- Sliding windows over IMU features, paired with displacement labels.
- Feature standardization (scaler saved for reuse at inference).
- Train/val/test split is **time-based** (not random) — first 70% of a drive = train, next 15% = val, last 15% = test — to avoid time-series leakage.

### 5.5 Training
- PyTorch, `mps` backend (Apple Silicon GPU).
- MSE loss on (dx, dy), MAE in meters tracked for interpretability.
- Adam optimizer, LR scheduler on val-loss plateau, early stopping.

### 5.6 Evaluation (core proof of concept)
For a held-out test sequence: compare (a) ground-truth GPS trajectory, (b) naive INS trajectory (raw IMU double-integration, no ML), and (c) model-corrected trajectory (accumulated model predictions). Report mean position error and final drift error for both naive and model, and % improvement. **This evaluation — not the live demo — is the primary evidence of the model's value.**

---

## 6. Demo Plan (important — do not conflate with evaluation)

Two separate things exist and must not be blended in any presentation or claim:

1. **Evaluation plot (Phase 5 / Section 5.6):** measured accuracy on real held-out **car** test data. This is the actual scientific/technical claim.
2. **Live walking demo:** at the presentation, a live phone (streaming via a local web page + WebSocket) is carried while walking, showing the sensor→model→prediction pipeline running in real time on stage. This is a **pipeline demo only** — walking produces different motion signals than driving, so live predictions during this demo are illustrative, not accurate, and the UI carries a visible disclaimer to that effect. A pre-recorded fallback video must exist in case live WiFi/demo fails on stage.

Any AI assistant helping with this project should never present the live-walk demo's output as a model accuracy claim.

---

## 7. Hardware / Environment

- Development machine: MacBook M1 Pro, 8-core CPU / 14-core GPU. Use PyTorch `mps` backend, not CUDA.
- Timeline: a few days total for build (see phase priority in Section 9).

---

## 8. Handoff Interface (for the fullstack developer)

- `predict_displacement(imu_window)` — Python function. Input: raw (unnormalized) phone accelerometer + gyroscope readings for one window, matching the exact shape/order/units documented in the README. Output: (dx, dy) in meters.
- A minimal FastAPI wrapper (`POST /predict`, `GET /health`) exposing the same function over HTTP.
- A plain-language README (no ML jargon) covering: what the model does/doesn't do, exact input/output contract, how to run it locally, and known limitations (below).

**Known limitations to state explicitly to the developer:**
- Trained on a limited subset of driving conditions/roads — accuracy may vary on unseen road types or driving styles.
- Accuracy degrades the longer GPS remains unavailable (drift accumulates even with correction, just slower than naive INS).
- Assumes phone stays roughly fixed in position/orientation during a GPS-denied interval; large mid-interval repositioning (e.g. picking up the phone) is not modeled.

---

## 9. Build Plan / Phase Priority

Full phase-by-phase build prompts exist in the companion file `IO-VNBD_AI_Model_Prompt_Package.md`. Priority order if time is constrained:

1. Data setup + exploration (verify phone sensor columns actually match assumptions above)
2. Preprocessing (orientation-invariant features, time-based split)
3. Model + training (keep small, fast iteration)
4. **Evaluation — highest priority after training.** This is the actual proof of the project's value; never cut this to save time for the demo.
5. Handoff package for the developer — required even if rushed; an undocumented model is not a usable deliverable.
6. Live demo harness — lowest priority. If time runs out, a pre-recorded video substitutes safely; a broken live demo on stage is worse than no live demo.

---

## 10. Open Questions / Assumptions to Revisit

- Exact column names/schema in the IO-VNBD smartphone data files have not yet been verified against this document's assumptions — confirm during the data exploration step before building preprocessing.
- Whether the dataset includes device orientation/quaternion data (needed for feature approach 5.2b) is unconfirmed — check during exploration.
- No formal accuracy target (e.g. "must beat naive INS by X%") has been set — currently the goal is directional improvement + a clear demo, not a fixed numeric bar. Revisit if SIH judging criteria specify a target.
