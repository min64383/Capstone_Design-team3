package hearspace.core.pipeline

import hearspace.core.geometry.Vec3
import hearspace.core.types.HeightClass

/**
 * SlowPath가 실제 Detection으로 넘기기 직전의 군집 기하/복셀 통계.
 * false positive 원인 분석용이며 안내 정책에는 사용하지 않는다.
 */
data class ClusterDebug(
    val tCaptureNs: Long,
    val clusterIndex: Int,
    val nVoxels: Int,
    val heightClass: HeightClass,
    val aabbMinW: Vec3,
    val aabbMaxW: Vec3,
    /** 진행 통로 기준 좌우 최소/최대(오른쪽 +). */
    val lateralMinM: Float,
    val lateralMaxM: Float,
    /** 진행 통로 기준 전방 최소/최대(앞 +). */
    val alongMinM: Float,
    val alongMaxM: Float,
    /** 바닥 기준 높이 최소/최대. */
    val heightMinM: Float,
    val heightMaxM: Float,
    val centroidW: Vec3,
    val nearestW: Vec3,
    val corridorNearestW: Vec3,
    val scoreMin: Float,
    val scoreMean: Float,
    val scoreMax: Float,
    val hitsMin: Int,
    val hitsMean: Float,
    val hitsMax: Int,
    /** 해당 cluster voxel 중 가장 오래된 관측의 나이. */
    val oldestVoxelAgeMs: Float,
    /** 해당 cluster voxel 중 가장 최신 관측의 나이. */
    val newestVoxelAgeMs: Float,
    /** 실험용 false-positive 필터에서 제외했는지. */
    val filtered: Boolean = false,
    val filterReason: String = "",
    /** 대표점(설정한 방식) 칸의 이 장 상태([hearspace.core.mapping.CellView] 이름, M20). 대표점이 칸이 아니거나 판정할 깊이가 없으면 "". */
    val repVoxelState: String = "",
    /** 대표점 칸의 마지막 관측 뒤 나이(ms). 없으면 NaN. */
    val repVoxelAgeMs: Float = Float.NaN,
    /** 대표점 칸의 누적 관측 수. 없으면 -1. */
    val repVoxelHits: Int = -1,
) {
    val lateralSpanM: Float get() = lateralMaxM - lateralMinM
    val alongSpanM: Float get() = alongMaxM - alongMinM
    val heightSpanM: Float get() = heightMaxM - heightMinM
}

/** 앱/오프라인 재생이 같은 CSV 형식을 쓰도록 core에는 문자열 포맷만 둔다. 파일 I/O는 호출하는 쪽 책임이다. */
object ClusterDebugCsv {
    const val HEADER =
        "tCaptureNs,clusterIndex,nVoxels,heightClass," +
            "aabbMinX,aabbMinY,aabbMinZ,aabbMaxX,aabbMaxY,aabbMaxZ," +
            "lateralMinM,lateralMaxM,lateralSpanM,alongMinM,alongMaxM,alongSpanM," +
            "heightMinM,heightMaxM,heightSpanM," +
            "centroidX,centroidY,centroidZ,nearestX,nearestY,nearestZ," +
            "corridorNearestX,corridorNearestY,corridorNearestZ," +
            "scoreMin,scoreMean,scoreMax,hitsMin,hitsMean,hitsMax,oldestVoxelAgeMs,newestVoxelAgeMs,filtered,filterReason," +
            "repVoxelState,repVoxelAgeMs,repVoxelHits"

    fun row(d: ClusterDebug): String = listOf(
        d.tCaptureNs,
        d.clusterIndex,
        d.nVoxels,
        d.heightClass.name,
        d.aabbMinW.x, d.aabbMinW.y, d.aabbMinW.z,
        d.aabbMaxW.x, d.aabbMaxW.y, d.aabbMaxW.z,
        d.lateralMinM, d.lateralMaxM, d.lateralSpanM,
        d.alongMinM, d.alongMaxM, d.alongSpanM,
        d.heightMinM, d.heightMaxM, d.heightSpanM,
        d.centroidW.x, d.centroidW.y, d.centroidW.z,
        d.nearestW.x, d.nearestW.y, d.nearestW.z,
        d.corridorNearestW.x, d.corridorNearestW.y, d.corridorNearestW.z,
        d.scoreMin, d.scoreMean, d.scoreMax,
        d.hitsMin, d.hitsMean, d.hitsMax,
        d.oldestVoxelAgeMs, d.newestVoxelAgeMs,
        d.filtered, d.filterReason,
        d.repVoxelState, d.repVoxelAgeMs, d.repVoxelHits,
    ).joinToString(",")
}
