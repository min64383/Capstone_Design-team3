package hearspace.core.pipeline

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.guidance.MapAction
import hearspace.core.mapping.LocalMap
import hearspace.core.mapping.MapUpdate
import hearspace.core.mapping.VoxelView
import hearspace.core.tracking.Cluster
import hearspace.core.tracking.Detection
import hearspace.core.tracking.HeightClassifier
import hearspace.core.tracking.RepPoint
import hearspace.core.tracking.Tracker
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.ObstacleSnapshot

/**
 * 느린 경로 (§7.7): 역투영 → 월드 → 바닥 → 복셀 → **통로 안 복셀만** 군집 → 높이 분류 → 대표점 → 추적.
 * 통로로 먼저 자르는 이유: 좁은 복도에서는 통로 밖 벽·천장이 수평으로 이어져 모든 물체를 한 군집으로 묶는다
 * (M4 실측, 명세 v0.2.4 §7.4). 그래서 스냅샷에는 통로 안 물체만 있다.
 * 스레드를 모르고, 입력 깊이의 `tCaptureNs`만 시각으로 쓴다.
 * 명세 §7.7의 `process(depth)`와 달리 진행 방향을 입력으로 받는다(빠른 경로가 추정해 넘긴다, M5·M7).
 * 통로·삭제 기준 위치는 **머리**(깊이 촬영 시 카메라 + `head.offsetFromCameraM`, v0.2.8): 폰을 몸 옆에 들어도
 * 통로가 몸의 진행선에 놓이고 안내(머리 기준 방위각·거리)와 같은 기준이 된다.
 */
class SlowPath(
    private val config: Config,
    /** 단계별 처리 시간을 재는 시계(ns). 실행 결과에는 영향이 없고 [lastStageNs]에만 쓴다. */
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /** 바닥·복셀 맵. */
    val map = LocalMap(config)

    private val tracker = Tracker(config.track, config.repPoint.strategy)

    /** 마지막 맵 갱신 결과(`slow_path.csv` 기록용). */
    var lastMapUpdate: MapUpdate? = null
        private set

    /** 마지막 깊이에서 Detection으로 넘어간 군집 통계(false positive 분석용). */
    var lastClusterDebug: List<ClusterDebug> = emptyList()
        private set

    /** 마지막 [process]의 단계별 처리 시간(이 기기·PC의 실측, IMPROVE_SPEC §3-3). */
    var lastStageNs: StageTimes = StageTimes(0, 0, 0)
        private set

    /** 깊이 한 장을 처리해 스냅샷을 만든다. */
    fun process(depth: DepthFrame, headingW: Vec3): ObstacleSnapshot {
        val t0 = nanoTime()
        val userPosW = HeadPose.fromCamera(depth.worldFromCam.translation(), headingW, config.head.offsetFromCameraM).positionW
        val u = map.update(depth, userPosW, headingW)
        lastMapUpdate = u
        val t1 = nanoTime()
        val floorY = u.floorY
        if (floorY == null) {
            lastClusterDebug = emptyList()
            val obstacles = tracker.update(emptyList())
            lastStageNs = StageTimes(t1 - t0, 0, nanoTime() - t1)
            return ObstacleSnapshot(depth.tCaptureNs, obstacles, null, u.mapHealth)
        }

        val corridor = Corridor(userPosW, headingW, floorY, config.corridor)
        val inCorridor = map.voxels.occupied().filter { corridor.contains(it.centerW) }
        val voxels = withoutEdgeStructures(inCorridor, corridor)
        val centers = voxels.map { it.centerW }
        val half = config.map.voxelSizeM / 2
        val debug = ArrayList<ClusterDebug>()
        val detections = ArrayList<Detection>()
        val clusters = Cluster.dbscanXZ(centers, config.cluster.epsM, config.cluster.minSamples)
        for ((clusterIndex, idx) in clusters.withIndex()) {
            val clusterVoxels = idx.map { voxels[it] }
            val pts = clusterVoxels.map { it.centerW }
            val reps = RepPoint.candidates(pts, corridor, config.map.voxelSizeM)
            val aabbMin = Vec3(pts.minOf { it.x } - half, pts.minOf { it.y } - half, pts.minOf { it.z } - half)
            val aabbMax = Vec3(pts.maxOf { it.x } + half, pts.maxOf { it.y } + half, pts.maxOf { it.z } + half)
            val heightClass = HeightClassifier.classify(pts.map { it.y - floorY }, config.cluster)!! // 군집 = 통로 안 부분
            val scores = clusterVoxels.map { it.score }
            val hits = clusterVoxels.map { it.hits }
            val laterals = pts.map { corridor.lateralM(it) }
            val alongs = pts.map { corridor.alongM(it) }
            val heights = pts.map { it.y - floorY }
            val agesMs = clusterVoxels.map { (depth.tCaptureNs - it.lastSeenNs).coerceAtLeast(0L) / 1_000_000f }

            val rawDebug = ClusterDebug(
                tCaptureNs = depth.tCaptureNs,
                clusterIndex = clusterIndex,
                nVoxels = pts.size,
                heightClass = heightClass,
                aabbMinW = aabbMin,
                aabbMaxW = aabbMax,
                lateralMinM = laterals.min(),
                lateralMaxM = laterals.max(),
                alongMinM = alongs.min(),
                alongMaxM = alongs.max(),
                heightMinM = heights.min(),
                heightMaxM = heights.max(),
                centroidW = reps.getValue(hearspace.core.types.RepStrategy.CENTROID),
                nearestW = reps.getValue(hearspace.core.types.RepStrategy.NEAREST),
                corridorNearestW = reps.getValue(hearspace.core.types.RepStrategy.CORRIDOR_NEAREST),
                scoreMin = scores.min(),
                scoreMean = scores.average().toFloat(),
                scoreMax = scores.max(),
                hitsMin = hits.min(),
                hitsMean = hits.average().toFloat(),
                hitsMax = hits.max(),
                oldestVoxelAgeMs = agesMs.max(),
                newestVoxelAgeMs = agesMs.min(),
            )
            val filterReason = FalsePositiveFilter.reason(rawDebug, config.falsePositiveFilter)
            debug += rawDebug.copy(filtered = filterReason != null, filterReason = filterReason ?: "")

            if (filterReason == null) {
                detections += Detection(
                    repCandidatesW = reps,
                    aabbMinW = aabbMin,
                    aabbMaxW = aabbMax,
                    heightClass = heightClass,
                    inCorridor = true,
                    confidence = scores.average().toFloat(),
                    lastSeenNs = clusterVoxels.maxOf { it.lastSeenNs },
                )
            }
        }
        lastClusterDebug = debug
        val t2 = nanoTime()
        val obstacles = tracker.update(detections)
        lastStageNs = StageTimes(t1 - t0, t2 - t1, nanoTime() - t2)
        return ObstacleSnapshot(depth.tCaptureNs, obstacles, floorY, u.mapHealth)
    }

    /**
     * 나란한 가장자리 구조물(벽·담장·난간·길가 차량 등)의 복셀을 뺀다. 가장자리 구역(|좌우| ≥ `corridor.edgeInnerM`)의
     * 복셀만 따로 군집해, 진행 방향으로 `corridor.edgeMinLengthM` 이상 이어진 군집을 구조물로 본다.
     * 좁은 복도에서 사용자가 조금만 치우쳐도 옆 벽 한 줄이 통로 안에 들어와 계속 경고하던 문제(M5 실측) 대응.
     * 벽에 붙은 물체는 가장자리 구역 안 부분만 벽과 함께 빠지고 안쪽으로 나온 부분은 남는다.
     * 실내 벽에 한정하지 않는 규칙이다(실외 확장, §15).
     */
    private fun withoutEdgeStructures(voxels: List<VoxelView>, corridor: Corridor): List<VoxelView> {
        val edge = voxels.filter { kotlin.math.abs(corridor.lateralM(it.centerW)) >= config.corridor.edgeInnerM }
        if (edge.isEmpty()) return voxels
        val removed = HashSet<VoxelView>()
        for (idx in Cluster.dbscanXZ(edge.map { it.centerW }, config.cluster.epsM, config.cluster.minSamples)) {
            val along = idx.map { corridor.alongM(edge[it].centerW) }
            if (along.max() - along.min() >= config.corridor.edgeMinLengthM) idx.forEach { removed += edge[it] }
        }
        return if (removed.isEmpty()) voxels else voxels.filter { it !in removed }
    }

    /** 빠른 경로의 맵 명령을 적용한다(§7.5): SCALE = 추적 복귀 시 score × `state.recoverScoreScale`, RESET = 초기화. */
    fun apply(action: MapAction) {
        when (action) {
            MapAction.NONE -> Unit
            MapAction.SCALE -> map.voxels.scaleScores(config.state.recoverScoreScale)
            MapAction.RESET -> reset()
        }
    }

    /** 맵·바닥·추적을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        map.reset()
        tracker.reset()
    }
}

/** 느린 경로 단계별 처리 시간(ns): 맵 갱신(역투영·바닥·복셀), 군집(통로·구조물 제외·군집·대표점), 추적. */
data class StageTimes(val mapNs: Long, val clusterNs: Long, val trackNs: Long)
