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

`geometry.enabled=true` runs a geometry-only analyzer after each map update. It does not drive audio guidance yet.
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
