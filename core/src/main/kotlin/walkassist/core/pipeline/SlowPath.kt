package walkassist.core.pipeline

import walkassist.core.geometry.Vec3
import walkassist.core.guidance.Corridor
import walkassist.core.mapping.LocalMap
import walkassist.core.mapping.MapUpdate
import walkassist.core.tracking.Cluster
import walkassist.core.tracking.Detection
import walkassist.core.tracking.HeightClassifier
import walkassist.core.tracking.RepPoint
import walkassist.core.tracking.Tracker
import walkassist.core.types.Config
import walkassist.core.types.DepthFrame
import walkassist.core.types.ObstacleSnapshot

/**
 * 느린 경로 (§7.7): 역투영 → 월드 → 바닥 → 복셀 → 군집 → 높이 분류 → 대표점 → 추적.
 * 스레드를 모르고, 입력 깊이의 `tCaptureNs`만 시각으로 쓴다.
 * 명세 §7.7의 `process(depth)`와 달리 통로·삭제 기준이 되는 사용자 위치와 진행 방향을 입력으로 받는다
 * (진행 방향은 빠른 경로가 추정해 넘긴다, M5·M7).
 */
class SlowPath(private val config: Config) {

    /** 바닥·복셀 맵. */
    val map = LocalMap(config)

    private val tracker = Tracker(config.track, config.repPoint.strategy)

    /** 마지막 맵 갱신 결과(`slow_path.csv` 기록용). */
    var lastMapUpdate: MapUpdate? = null
        private set

    /** 깊이 한 장을 처리해 스냅샷을 만든다. */
    fun process(depth: DepthFrame, userPosW: Vec3, headingW: Vec3): ObstacleSnapshot {
        val u = map.update(depth, userPosW, headingW)
        lastMapUpdate = u
        val floorY = u.floorY
            ?: return ObstacleSnapshot(depth.tCaptureNs, tracker.update(emptyList()), null, u.mapHealth)

        val corridor = Corridor(userPosW, headingW, floorY, config.corridor)
        val voxels = map.voxels.occupied()
        val centers = voxels.map { it.centerW }
        val half = config.map.voxelSizeM / 2
        val detections = Cluster.dbscanXZ(centers, config.cluster.epsM, config.cluster.minSamples).map { idx ->
            val pts = idx.map { centers[it] }
            val inCorridorHeights = pts.filter { corridor.contains(it) }.map { it.y - floorY }
            // 통로 안 점이 없으면 분류는 통로 밖 물체의 참고값(군집 전체 최저 높이)
            val cls = HeightClassifier.classify(inCorridorHeights, config.cluster)
                ?: HeightClassifier.classify(pts.map { it.y - floorY }, config.cluster)!!
            Detection(
                repCandidatesW = RepPoint.candidates(pts, corridor),
                aabbMinW = Vec3(pts.minOf { it.x } - half, pts.minOf { it.y } - half, pts.minOf { it.z } - half),
                aabbMaxW = Vec3(pts.maxOf { it.x } + half, pts.maxOf { it.y } + half, pts.maxOf { it.z } + half),
                heightClass = cls,
                inCorridor = inCorridorHeights.isNotEmpty(),
                confidence = idx.map { voxels[it].score }.average().toFloat(),
                lastSeenNs = idx.maxOf { voxels[it].lastSeenNs },
            )
        }
        return ObstacleSnapshot(depth.tCaptureNs, tracker.update(detections), floorY, u.mapHealth)
    }

    /** 맵·바닥·추적을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        map.reset()
        tracker.reset()
    }
}
