package hearspace.core.pipeline

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.guidance.MapAction
import hearspace.core.mapping.LocalMap
import hearspace.core.mapping.MapUpdate
import hearspace.core.mapping.PruneCounts
import hearspace.core.mapping.VoxelView
import hearspace.core.tracking.Cluster
import hearspace.core.tracking.Detection
import hearspace.core.tracking.HeightClassifier
import hearspace.core.tracking.RepPoint
import hearspace.core.tracking.Tracker
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.MapHealth
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
    /** 고정 지도(정답 대입 O, M12.3 평가 전용). 있으면 바닥 추정·복셀 갱신을 건너뛰고 이 칸과 바닥을 쓴다. 앱은 넘기지 않는다. */
    private val fixedMap: FixedMap? = null,
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
        val fm = fixedMap
        val u = if (fm == null) map.update(depth, userPosW, headingW) else {
            MapUpdate(depth.tCaptureNs, 0, 0, 0, 0, 0, PruneCounts(0, 0, 0), fm.voxels.size, fm.floorY, MapHealth.OK)
        }
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
        val camFromWorld = depth.worldFromCam.rigidInverse()
        val occupied = fm?.voxels?.map { it.copy(lastSeenNs = depth.tCaptureNs) } ?: map.voxels.occupied()
        val inCorridor = occupied.filter { corridor.contains(it.centerW) }
        val voxels = withoutEdgeStructures(inCorridor, corridor)
        // C2: 남은 칸을 벽 칸과 나머지로 나눠 따로 묶는다(벽도 장애물로 남는다). 끄면 한 묶음(기준선). 가장자리 구조물 규칙을 먼저 전체에
        // 적용해야 한다: 벽 칸을 먼저 떼면 라벨 없는 옆 벽 밑동 조각이 짧은 덩어리로 남아 오경보가 됐다(S02 130646 0.11 → 0.22)
        val groups = if (config.cluster.separateWalls) {
            voxels.partition { it.hits > 0 && it.wallHits >= config.cluster.wallFraction * it.hits }.toList() // TSDF 표면 칸은 맞은 장이 0일 수 있다
        } else {
            listOf(voxels)
        }
        fun dbscan(g: List<VoxelView>): List<List<VoxelView>> {
            val multiplier = config.cluster.coreEvidenceMultiplier
            val coreMask = if (multiplier <= 1f || config.map.mode != hearspace.core.types.MapMode.HITS) null else {
                val minCoreEvidence = config.map.minHits * multiplier
                BooleanArray(g.size) { i -> g[i].evidence >= minCoreEvidence }
            }
            return Cluster.dbscanXZ(g.map { it.centerW }, config.cluster.epsM, config.cluster.minSamples, coreMask)
                .map { idx -> idx.map { g[it] } }
        }
        // C3b: 같은 물체 번호끼리(번호 없는 칸은 한 묶음) 묶고 그 안에서 떨어진 조각은 DBSCAN으로 나눈 뒤, 위에서 본 면적이 겹치는
        // 조각은 합친다(같은 물체의 앞면·윗면이 다른 번호가 된 경우). 벽 묶음과 나머지는 서로 합치지 않는다
        val clusters = if (config.map.instances) {
            groups.flatMap { g -> mergeOverlapping(g.groupBy { it.instId }.values.flatMap { dbscan(it) }) }
        } else {
            groups.flatMap { dbscan(it) }
        }
        val half = config.map.voxelSizeM / 2
        val debug = ArrayList<ClusterDebug>()
        val detections = ArrayList<Detection>()
        for ((clusterIndex, clusterVoxels) in clusters.withIndex()) {
            val pts = clusterVoxels.map { it.centerW }
            val reps = RepPoint.candidates(pts, corridor, config.map.voxelSizeM, config.repPoint.bandM, config.repPoint.bandQ)
            // CORRIDOR_BAND는 만든 점이라 칸 중심과 다르다: 그 점에 가장 가까운 칸을 기록한다
            val repVoxel = reps.getValue(config.repPoint.strategy).let { r -> clusterVoxels.minBy { (it.centerW - r).norm() } }
            val repDepth = if (fm == null) map.lastDepthMm else null
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
                repVoxelState = if (repVoxel != null && repDepth != null) {
                    map.voxels.cellView(repVoxel.centerW, repVoxel.lastSeenNs == depth.tCaptureNs, repDepth, depth.K, camFromWorld).name
                } else "",
                repVoxelAgeMs = repVoxel?.let { (depth.tCaptureNs - it.lastSeenNs).coerceAtLeast(0L) / 1_000_000f } ?: Float.NaN,
                repVoxelHits = repVoxel?.hits ?: -1,
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
                    suspicious = FalsePositiveFilter.matches(rawDebug, config.falsePositiveFilter),
                    instanceId = clusterVoxels.groupingBy { it.instId }.eachCount().maxByOrNull { it.value }!!.key,
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

    /**
     * 위에서 본 바닥 사각형(복셀 중심 ± 반 칸)이 작은 쪽 면적의 과반 넘게 겹치는 군집을 합친다(C3b). 같은 물체의 앞면과 윗면은
     * 위에서 보면 겹치고(SC-22: 경계 판정이 윗면 일부를 지워 매번 다른 영역이 되어 두 번호로 갈라졌다), 서로 다른 물체는 거의 겹치지 않는다
     * (엇갈린 두 상자, 벽 앞 물체). 과반은 영역 → 번호 연결의 과반과 같은 정의다.
     */
    private fun mergeOverlapping(clusters: List<List<VoxelView>>): List<List<VoxelView>> {
        if (clusters.size < 2) return clusters
        val half = config.map.voxelSizeM / 2
        val box = clusters.map { c ->
            floatArrayOf(c.minOf { it.centerW.x } - half, c.maxOf { it.centerW.x } + half, c.minOf { it.centerW.z } - half, c.maxOf { it.centerW.z } + half)
        }
        fun area(b: FloatArray) = (b[1] - b[0]) * (b[3] - b[2])
        val parent = IntArray(clusters.size) { it }
        fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
        for (a in clusters.indices) for (b in a + 1 until clusters.size) {
            val ox = minOf(box[a][1], box[b][1]) - maxOf(box[a][0], box[b][0])
            val oz = minOf(box[a][3], box[b][3]) - maxOf(box[a][2], box[b][2])
            if (ox <= 0f || oz <= 0f) continue
            if (ox * oz * 2 > minOf(area(box[a]), area(box[b]))) parent[find(a)] = find(b)
        }
        return clusters.indices.groupBy { find(it) }.values.map { ids -> ids.flatMap { clusters[it] } }
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

/** 미리 정한 점유 칸과 바닥(월드 y). 느린 경로가 지도 대신 쓴다(정답 대입 O, M12.3). */
class FixedMap(val voxels: List<VoxelView>, val floorY: Float)
