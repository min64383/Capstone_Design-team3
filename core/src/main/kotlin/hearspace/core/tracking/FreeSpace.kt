package hearspace.core.tracking

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.DepthFrame
import hearspace.core.types.TrackConfig
import kotlin.math.floor

/**
 * 빈 곳 위반(M57 설계 3.3절, prototypes/python/motion_judge/free_space.py·stage0.py 이식): 지금 원시 장애물 점을 그 점의 카메라 거리 구간
 * (`track.freeSpaceZEdgesM`)에 맞춘 시간(`freeSpaceLagFrames` × `freeSpaceFrameS`)만큼 앞의 가장 최근 원시 장에 그 장의 자세로 투영해, 그 화소의
 * 깊이(신뢰도 ≥ `freeSpaceMinConfidence`)가 점의 그 카메라 깊이보다 `freeSpaceMarginM` 넘게 멀면 위반(그때 그 자리는 비어 있었다). 과거 원시 장만 쓴다.
 */
class FreeSpace(private val cfg: TrackConfig) {
    private class Past(val tNs: Long, val depthMm: ShortArray, val confidence: ByteArray?, val k: hearspace.core.types.Intrinsics, val camFromWorld: Mat4, val worldFromCam: Mat4)

    private val past = ArrayDeque<Past>()
    private val keepNs = ((cfg.freeSpaceLagFrames.maxOrNull() ?: 0) + 1) * (cfg.freeSpaceFrameS * 1e9).toLong()

    /**
     * 새 원시 장 [raw]: 그 전까지의 원시 장으로 비교하는 셈 함수와 앞 원시 장에서 이 장까지의 회전 속도(°/s, 앞 장이 없으면 NaN)를 돌려주고
     * [raw]를 기록에 넣는다.
     */
    fun advance(raw: DepthFrame): Pair<(List<Vec3>) -> IntArray, Float> {
        val prev = past.toList()
        val nowCam = raw.worldFromCam.rigidInverse()
        val rot = prev.lastOrNull()?.let { p ->
            val dt = (raw.tCaptureNs - p.tNs) / 1e9f
            var tr = 0f
            for (j in 0 until 3) tr += p.worldFromCam.axis(j) dot raw.worldFromCam.axis(j)
            val ang = Math.toDegrees(kotlin.math.acos(((tr - 1f) / 2f).coerceIn(-1f, 1f).toDouble())).toFloat()
            if (dt > 0f) ang / dt else Float.NaN
        } ?: Float.NaN
        past.addLast(Past(raw.tCaptureNs, raw.depthMm, raw.confidence, raw.K, nowCam, raw.worldFromCam))
        while (past.isNotEmpty() && raw.tCaptureNs - past.first().tNs > keepNs) past.removeFirst()
        val frameNs = (cfg.freeSpaceFrameS * 1e9).toLong()
        val edges = cfg.freeSpaceZEdgesM
        val count: (List<Vec3>) -> IntArray = { points ->
            var nCmp = 0
            var nViol = 0
            for (p in points) {
                val z = nowCam.transformPoint(p).z
                var b = edges.count { z >= it } - 1
                b = b.coerceIn(0, edges.size - 2)
                val target = raw.tCaptureNs - cfg.freeSpaceLagFrames[b] * frameNs
                val q = prev.lastOrNull { it.tNs <= target } ?: continue
                val c = q.camFromWorld.transformPoint(p)
                if (c.z <= 0.1f) continue
                val u = floor(q.k.fx * c.x / c.z + q.k.cx).toInt()
                val v = floor(q.k.fy * c.y / c.z + q.k.cy).toInt()
                if (u < 0 || v < 0 || u >= q.k.width || v >= q.k.height) continue
                val i = v * q.k.width + u
                val mm = q.depthMm[i].toInt() and 0xFFFF
                val conf = q.confidence?.get(i)?.toInt()?.and(0xFF) ?: 255
                if (mm == 0 || conf < cfg.freeSpaceMinConfidence) continue
                nCmp++
                if (mm / 1000f - c.z > cfg.freeSpaceMarginM[b]) nViol++
            }
            intArrayOf(nCmp, nViol)
        }
        return count to rot
    }

    fun reset() = past.clear()
}

/** 이번 장에 새 원시 장이 있을 때 빈 곳 위반 재료(M57): 원시 장애물 점(신뢰도 ≥ `freeSpaceMinConfidence`), 셈 함수, 회전 속도, 칸 라벨. */
class FreeSpaceFrame(
    val tRawNs: Long,
    val points: List<Vec3>,
    val count: (List<Vec3>) -> IntArray,
    val rotDegS: Float,
    /** 점이 든 복셀 지도 칸의 과반 라벨(-1 = 없음). */
    val labelAt: (Vec3) -> Int,
)

/** 빈 곳 위반 기록(`motion_evidence.csv`) 한 줄: track 하나 × 새 원시 장 하나(M57 2단계 값 유도용). */
class EvidenceDebug(
    val tRawNs: Long,
    val tNs: Long,
    val id: Int,
    val candidate: Boolean,
    val moving: Boolean,
    val ageS: Float,
    val nPts: Int,
    val nCmp: Int,
    val nViol: Int,
    val zMedM: Float,
    val rotDegS: Float,
    /** 창 상자(월드). */
    val lo: Vec3,
    val hi: Vec3,
    /** 창 안 원시 점이 든 칸의 과반 라벨 수(0~11). */
    val labels: IntArray,
    /** 움직이는 track의 움직이는 칸 라벨 수(0~11, M57 A-3). 아니면 빈 배열. */
    val ownLabels: IntArray,
)
