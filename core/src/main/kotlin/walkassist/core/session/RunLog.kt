package walkassist.core.session

import walkassist.core.mapping.MapUpdate
import walkassist.core.pipeline.ClusterDebug
import walkassist.core.pipeline.ClusterDebugCsv
import walkassist.core.types.GuidanceOutput
import walkassist.core.types.ObstacleSnapshot
import walkassist.core.types.RepStrategy

/**
 * 실행 로그 CSV 형식 (§10.1). 앱(M7)과 PC 오프라인 재생(M8)이 같은 형식을 쓴다. `device.csv`는 [DeviceCsv].
 * 모든 시각은 같은 단조 시계 기준(ns). 값이 없으면 빈 칸.
 */
object RunLog {
    const val SLOW_PATH_FILE = "slow_path.csv"
    const val GUIDANCE_FILE = "guidance.csv"
    const val OBSTACLES_FILE = "obstacles.csv"

    /** 군집 통계(오인식 분석용, 명세 §10.1 추가 로그, v0.2.10). 형식은 [ClusterDebugCsv]. */
    const val CLUSTER_DEBUG_FILE = "cluster_debug.csv"

    val SLOW_PATH_HEADER = listOf("tCaptureNs", "tStartNs", "tDoneNs", "nPoints", "nVoxels", "nObstacles", "floorY", "mapHealth")

    val GUIDANCE_HEADER = listOf(
        "tBlockNs", "poseTNs", "snapshotTNs", "state", "obstacleId", "azimuthDeg", "distanceM", "band", "sound", "infoAgeMs", "headingDeg",
    )

    val OBSTACLES_HEADER = listOf("tCaptureNs", "id", "heightClass", "inCorridor") +
        RepStrategy.entries.flatMap { s -> listOf("x", "y", "z").map { "rep${s.name}_$it" } } +
        listOf("aabbMinX", "aabbMinY", "aabbMinZ", "aabbMaxX", "aabbMaxY", "aabbMaxZ")

    /** 느린 경로 한 번 → `slow_path.csv` 한 줄. */
    fun slowPathLine(u: MapUpdate, tStartNs: Long, tDoneNs: Long, snapshot: ObstacleSnapshot): String =
        row(u.tCaptureNs, tStartNs, tDoneNs, u.nPoints, u.nVoxels, snapshot.obstacles.size, snapshot.floorY, snapshot.mapHealth)

    /**
     * 오디오 블록 하나 → `guidance.csv` 줄(음원마다 한 줄). 음원이 없으면 음원 칸이 빈 한 줄:
     * 빈 칸은 "이 블록에 낸 음원 없음"일 뿐 "장애물 없음"이 아니다(§2.2-5, 상태 열과 함께 읽는다).
     */
    fun guidanceLines(g: GuidanceOutput, poseTNs: Long, snapshotTNs: Long?): List<String> {
        val head = listOf<Any?>(g.tBlockNs, poseTNs, snapshotTNs, g.state)
        val tail = g.headingDeg.takeUnless { it.isNaN() }
        if (g.commands.isEmpty()) return listOf(row(*(head + List(6) { null } + tail).toTypedArray()))
        return g.commands.map { c ->
            row(*(head + listOf(c.obstacleId, c.azimuthDeg, c.distanceM, c.band, c.sound, c.infoAgeMs, tail)).toTypedArray())
        }
    }

    /** 스냅샷 하나 → `obstacles.csv` 줄(물체마다 한 줄). */
    fun obstacleLines(s: ObstacleSnapshot): List<String> = s.obstacles.map { o ->
        val reps = RepStrategy.entries.flatMap { st -> o.repCandidatesW[st]?.let { listOf(it.x, it.y, it.z) } ?: List(3) { null } }
        row(
            *(listOf<Any?>(s.tCaptureNs, o.id, o.heightClass, o.inCorridor) + reps +
                listOf(o.aabbMinW.x, o.aabbMinW.y, o.aabbMinW.z, o.aabbMaxW.x, o.aabbMaxW.y, o.aabbMaxW.z)).toTypedArray(),
        )
    }

    /** 느린 경로 한 번의 군집 통계 → `cluster_debug.csv` 줄. */
    fun clusterDebugLines(items: List<ClusterDebug>): List<String> = items.map(ClusterDebugCsv::row)

    fun header(cols: List<String>): String = cols.joinToString(",")

    private fun row(vararg v: Any?): String = v.joinToString(",") { it?.toString() ?: "" }
}
