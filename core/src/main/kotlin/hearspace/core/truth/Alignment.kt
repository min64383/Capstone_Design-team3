package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.session.FrameRow
import hearspace.core.types.TrackingState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 정답 정렬 (MVP §10.3): 월드 좌표 ↔ 정답 좌표. `tools/analysis/align.py`의 `fit`·`to_truth`와 같은 계산이다
 * (평가 GUI가 정답 상자를 월드에 그릴 때 쓴다. 바꾸면 두 곳을 같이 바꾼다).
 *
 * 카메라 궤적(수평)의 처음 `align.fitLengthM`에 직선을 맞춰 그 방향을 +z(보행선)로, 시작 때 **머리**(카메라 + 오프셋)
 * 아래 바닥을 원점으로 둔다. 궤적이 짧으면 전체에 맞추고 [fitShort]로 표시한다.
 */
data class Alignment(
    val originX: Double,
    val originZ: Double,
    /** 보행선 방향(월드 XZ 단위 벡터). */
    val dirX: Double,
    val dirZ: Double,
    /** 바닥 높이(월드 y). 정답 y = 월드 y − floorY. */
    val floorY: Double,
    val fitLengthM: Double,
    val fitShort: Boolean,
    val nPoints: Int,
    /** 직선에서 옆으로 벗어난 정도(RMS, m). */
    val residualRmsM: Double,
    /** 직선 방향의 대략적 불확실성(°). */
    val angleUncertaintyDeg: Double,
) {
    // 오른쪽 = (−dz, dx): 월드 +Y 위, 진행 방향을 보고 선 사람의 오른손 쪽
    private val rightX get() = -dirZ
    private val rightZ get() = dirX

    /** 월드 → 정답 좌표(x 오른쪽, y 바닥 위, z 앞). */
    fun toTruth(w: Vec3): Vec3 {
        val px = w.x - originX
        val pz = w.z - originZ
        return Vec3((px * rightX + pz * rightZ).toFloat(), (w.y - floorY).toFloat(), (px * dirX + pz * dirZ).toFloat())
    }

    /** 정답 좌표 → 월드. */
    fun toWorld(t: Vec3): Vec3 = Vec3(
        (originX + t.x * rightX + t.z * dirX).toFloat(),
        (t.y + floorY).toFloat(),
        (originZ + t.x * rightZ + t.z * dirZ).toFloat(),
    )

    companion object {
        /**
         * [rows]의 카메라 궤적으로 정렬을 맞춘다. 같은 `tNs`가 두 번 나온 행은 첫 행만, 추적 중(`TRACKING`)인 행만 쓴다.
         * [floorY]는 느린 경로 바닥 추정의 중앙값([medianFloorY]).
         */
        fun fit(rows: List<FrameRow>, fitLengthM: Float, headOffsetFromCameraM: Vec3, floorY: Double): Alignment {
            val xz = rows.distinctBy { it.tNs }.filter { it.tracking == TrackingState.TRACKING }
                .map { doubleArrayOf(it.pose.tx.toDouble(), it.pose.tz.toDouble()) }
            require(xz.size >= 2) { "need at least 2 tracking frames" }
            val sx = xz[0][0]
            val sz = xz[0][1]
            val reach = xz.indexOfFirst { hypot(it[0] - sx, it[1] - sz) >= fitLengthM }
            val short = reach < 0
            val pts = if (short) xz else xz.subList(0, reach + 1)
            val cx = pts.sumOf { it[0] } / pts.size
            val cz = pts.sumOf { it[1] } / pts.size
            // 주축(공분산 최대 고유벡터) = align.py의 SVD 첫 오른쪽 특이벡터
            var sxx = 0.0
            var sxz = 0.0
            var szz = 0.0
            for (p in pts) {
                val dx = p[0] - cx
                val dz = p[1] - cz
                sxx += dx * dx; sxz += dx * dz; szz += dz * dz
            }
            val theta = 0.5 * atan2(2 * sxz, sxx - szz)
            var dx = cos(theta)
            var dz = sin(theta)
            val ex = pts.last()[0] - pts.first()[0]
            val ez = pts.last()[1] - pts.first()[1]
            if (ex * dx + ez * dz < 0) { dx = -dx; dz = -dz }
            val rx = -dz
            val rz = dx
            val rms = sqrt(pts.sumOf { val l = (it[0] - cx) * rx + (it[1] - cz) * rz; l * l } / pts.size)
            val span = hypot(ex, ez)
            val ox = headOffsetFromCameraM.x.toDouble()
            val oz = headOffsetFromCameraM.z.toDouble()
            return Alignment(
                originX = sx + rx * ox + dx * oz,
                originZ = sz + rz * ox + dz * oz,
                dirX = dx, dirZ = dz,
                floorY = floorY,
                fitLengthM = if (short) span else fitLengthM.toDouble(),
                fitShort = short,
                nPoints = pts.size,
                residualRmsM = rms,
                angleUncertaintyDeg = Math.toDegrees(atan2(2 * rms, span)),
            )
        }

        /** 바닥 추정값들의 중앙값(짝수 개면 가운데 두 값의 평균, numpy와 같음). 비었으면 null. */
        fun medianFloorY(values: List<Float?>): Double? {
            val v = values.filterNotNull().map { it.toDouble() }.sorted()
            if (v.isEmpty()) return null
            return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
        }
    }
}
