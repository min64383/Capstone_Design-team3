package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelMap
import hearspace.core.types.HeightClass
import hearspace.core.types.RepStrategy

/**
 * 이번 깊이 장의 정보(M48 정지·움직임 분해, 설계 3.1절). 느린 경로가 지도 갱신 뒤 만들어 [Tracker.update]에 넘긴다.
 * 지도 연산은 람다로 받아 추적이 지도를 직접 알지 않게 한다.
 */
class MotionFrame(
    /** 깊이 촬영 시각. */
    val tNs: Long,
    /** 장 번호(같은 장 한 번 판정). */
    val frame: Long,
    /** 이번 장 장애물 점과 관측 가중치(정지 지도와 같은 거리 가중, 지도에 넣었든 뺐든). */
    val points: List<Pair<Vec3, Float>>,
    /** 이번 장 칸(점이 든 칸의 중심, 중복 없음) 중 통로 안 칸과, 그중 정지 지도가 이미 점유로 만든 칸인지. */
    val corridorCells: List<Pair<Vec3, Boolean>>,
    /** 칸 목록의 대표점 후보(지금과 같은 규칙: 통로·복셀·띠). 빈 목록이면 null. */
    val reps: (List<Vec3>) -> Map<RepStrategy, Vec3>?,
    /** 설정의 대표점 방식. */
    val strategy: RepStrategy,
    /** 측정 잡음 σ_m(Z)(점까지 카메라 거리로). */
    val sigmaAt: (Vec3) -> Float,
    /** 이번 장에서 그 칸 자리가 빈 곳으로 보이는가(정지 지도의 감쇠 판정과 같음). */
    val seenFree: (Vec3) -> Boolean,
    /** 칸들의 높이 분류(바닥 기준). */
    val heightClass: (List<Vec3>) -> HeightClass?,
    /** 상자 안 칸 중 그 시각 이후 생긴 칸을 정지 지도에서 빼 온다(움직임 판정 순간). */
    val take: (Vec3, Vec3, Long) -> List<VoxelMap.CellEvidence>,
    /** 칸을 정지 지도에 넣는다(넘겨주기). */
    val seed: (VoxelMap.CellEvidence) -> Unit,
    /** 후보 묶음(지금과 같은 DBSCAN). */
    val clusterXZ: (List<Vec3>) -> List<List<Vec3>>,
    /** 칸 하나 크기(m). */
    val voxelM: Float,
    /** 점이 통로 안인지(출력은 통로 안 점유 칸만, 지금과 같음). */
    val inCorridor: (Vec3) -> Boolean,
)

/** track 하나의 움직임 상태(M48). */
class MotionState(accelSigmaMps2: Float, val cells: MovingCells, val birthNs: Long, var candidate: Boolean) {
    val filter = MotionFilter(accelSigmaMps2)
    var moving = false
    /** 마지막 측정 칸의 상자(없으면 짝지은 지도 군집의 상자). */
    var boxMin: Vec3 = Vec3.ZERO
    var boxMax: Vec3 = Vec3.ZERO
    /** 연속으로 측정이 없던 장 수. */
    var missed = 0
    /** 마지막 측정(대표점)과 그 시각. */
    var lastMeasW: Vec3? = null
    var lastMeasNs: Long = Long.MIN_VALUE
    /** 마지막 장의 측정 칸 수, 이번 장에 지운 칸 수(기록용). */
    var nMeasCells = 0
    var nRemoved = 0
    var lastChi2 = 0f

    /** 원점 = 칼만 위치(준비 전이면 마지막 측정). */
    fun origin(): Vec3? = filter.positionW ?: lastMeasW

    /** 시각 [tNs]의 추적 창: 측정 상자를 예측 속도 × 경과만큼 옮기고 [marginM]만큼 넓힌다. */
    fun gate(tNs: Long, marginM: Float): Pair<Vec3, Vec3> {
        val v = filter.velocityW ?: Vec3.ZERO
        val dt = if (filter.tNs == Long.MIN_VALUE) 0f else ((tNs - filter.tNs) / 1e9f).coerceAtLeast(0f)
        val d = v * dt
        val m = Vec3(marginM, marginM, marginM)
        return (boxMin + d - m) to (boxMax + d + m)
    }
}

/** 움직임 기록(`motion.csv`) 한 줄. */
data class MotionDebug(
    val tCaptureNs: Long,
    val id: Int,
    val candidate: Boolean,
    val moving: Boolean,
    val measW: Vec3?,
    val posW: Vec3?,
    val velW: Vec3?,
    val posSigmaM: Float,
    val velSigmaMps: Float,
    val chi2: Float,
    val nMeasCells: Int,
    val nCells: Int,
    val nOccupied: Int,
    val nRemoved: Int,
)

internal fun inBox(p: Vec3, lo: Vec3, hi: Vec3) = p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z in lo.z..hi.z
