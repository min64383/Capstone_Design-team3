# Indoor geometry / voxel confidence experiment

This branch adds two opt-in experiments without changing the default behavior.

## 1. Current occupancy evidence

`map.freeEvidenceDecay > 0` enables a current-evidence value for HITS mode.
Hits add evidence; only a **confirmed free-space observation inside the camera FOV** subtracts evidence.
Out-of-view, invalid-depth and occluded voxels are not reduced, preserving the project safety rule.
Historical `hits` remain available for logs.

## 2. Weak-bridge clustering

`cluster.coreEvidenceMultiplier > 1` lets only strongly observed voxels expand DBSCAN clusters.
Lower-evidence voxels can still attach as border points, but cannot form a chain connecting an obstacle to a background wall.
The default value is `1.0`, identical to the current clustering behavior.

## 3. Indoor geometry analyzer

`floor.mode=ELEVATION` runs a geometry-only analyzer after each map update, including unknown-floor updates and TRISTATE maps. `SINGLE` (default) keeps the existing map and obstacle guidance unchanged. Geometry never drives audio guidance.

`geometry.enabled` is a legacy config field retained for readability of older configs. It is not the activation switch: a legacy `true` with `floor.mode=SINGLE` is rejected with a migration message. Use `floor.mode=ELEVATION`; `geometry.enabled` may stay false.
It produces a ground profile and candidates:

- FLAT
- OCCUPIED
- WALL
- OVERHANG
- STEP_UP / STEP_DOWN
- STAIRS_UP / STAIRS_DOWN
- SLOPE_UP / SLOPE_DOWN
- FLOOR_LOSS
- NARROW_PASSAGE
- OPENING
- UNKNOWN

`FLOOR_LOSS` is deliberately conservative: missing ground alone is not enough. There must be valid ground before the gap and again after it.

Use `app/src/main/assets/config/indoor-geometry-experimental.override.json` for the first A/B experiment.
Do not connect geometry findings directly to safety guidance until replay/ground-truth evaluation is complete.

## M13.5 review (2026-10-28)

The experimental override now changes **only** `floor.mode`, leaving voxel decay, core evidence and frontend settings unchanged. This isolates geometry from guidance/map changes. On a device copy the JSON to the documented `config.override.json` location; the bundled experimental file is not loaded automatically.

- Ground and occupancy use the same half corridor width and independent `geometry.maxDistanceM` (initial **unvalidated** candidate 2 m, measured along heading from the user). Reducing the range does not establish depth accuracy.
- Incomplete ground coverage yields UNKNOWN unless there is an explicit ground-risk finding; depth loss alone never proves FLOOR_LOSS. An internally bounded gap is only a geometric FLOOR_LOSS candidate, not a confirmed hazard.
- Height histogram ties use distance to the estimated floor and a deterministic secondary key. Both platform edges remain in findings even though primary is a single label.
- Narrow passages require different connected XZ components for their two boundaries; floor-height voxels are not boundaries. This is conservative: connected walls can suppress a candidate, and unobserved gaps are not proven traversable.
- Consecutive OPENING bins produce one finding per run of bins; a disconnected opening can produce a separate finding.
- Former heuristic values (ground height range, histogram minimum, obstacle height, opening factor, gap/slope support and confidence formula parameters) live in `GeometryConfig`, strict JSON parsing and `default.json`. Confidence values are heuristic scores, not calibrated probabilities.

### Logs and viewer

`geometry_debug.csv` is written only in ELEVATION. Each slow step has one row per finding (or an UNKNOWN row when no estimate exists), with columns:

`tCaptureNs,primary,type,distanceM,confidence,detail,profile`

`profile` contains semicolon-separated `alongStartM:alongEndM:heightM:nPoints` bins; missing height is empty. Detail and profile are quoted CSV fields. Count **distinct tCaptureNs** when comparing primary-label rates, so compound findings do not double-count frames. `ReplayResult.SlowRec.geometry` carries the same result into the viewer; the top view shows primary and all findings from the last completed slow step, never a future step. The panel says these are experimental and disconnected from guidance.

PC replay example (repository root):

```powershell
.\gradlew.bat :core:replay -Psession=testdata/sessions/20261003_130834_S01 -Pout=data/m13-5/review -PoverridesFile=app/src/main/assets/config/indoor-geometry-experimental.override.json
```

For the viewer, use the same `floor.mode=ELEVATION` JSON as a variant override in the experiment controls.

### Validation scope

Synthetic tests cover flat/up/down/platform/slopes, missing ground, bounded gaps, central obstacles, independent side boundaries, out-of-ROI head-height walls, opening deduplication, floor-relative histogram ties and maximum distance. A synthetic replay compares deterministic obstacle/guidance logs with SINGLE vs ELEVATION and checks geometry log timestamps and stale-log removal.

The historical 38 flat-corridor sessions and their exact manifest are not present in this checkout. It contains 13 pre-2026-10-24 indoor sessions and 48 later outdoor recordings; the outdoor sessions must not be treated as flat-corridor negatives. A before/after replay on the available indoor subset is only a partial regression check. Existing 2026-10-24 O01~O05 recordings already include curbs, slopes and stairs with scene labels and measured camera distances. Only O01 has structured obstacle annotations; the remaining recordings are not absent. The additional replay in `M13_5_REVIEW.md` shows severe suppression of expected candidates even in the near-range subset. Detailed event geometry/visibility annotations and diagnosis are still needed; lower false-positive rates on flat floors do not validate hazard recall. No audio-guidance connection or default activation is authorized by this experiment.
