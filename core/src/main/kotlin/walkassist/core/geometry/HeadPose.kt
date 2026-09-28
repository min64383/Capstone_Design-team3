package walkassist.core.geometry

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 머리 좌표계 H (§5): 원점 = 머리 위치(월드), +Z = 진행 방향(수평 단위 벡터), +Y = 월드 위.
 * 방위각 θ는 +Z 기준 **오른쪽이 양수**다.
 */
data class HeadPose(
    val positionW: Vec3,
    /** 수평 단위 벡터(Y = 0). */
    val headingW: Vec3,
) {
    init {
        require(kotlin.math.abs(headingW.y) < 1e-4f) { "heading must be horizontal: $headingW" }
        require(kotlin.math.abs(headingW.norm() - 1f) < 1e-3f) { "heading must be unit: $headingW" }
    }

    /** 진행 방향 기준 오른쪽(수평 단위 벡터) = heading × up. */
    val rightW: Vec3 get() = headingW cross Vec3.UP

    companion object {
        /**
         * 카메라 위치와 진행 방향에 파지 오프셋을 더해 머리 자세를 만든다.
         * [offsetFromCameraM]은 진행 방향 기준 (오른쪽, 위, 앞) 성분이다(`head.offsetFromCameraM`, DECISIONS 2026-09-28).
         * 예: 카메라가 눈 중앙보다 0.5 m 아래·0.3 m 앞이면 `(0, 0.5, −0.3)`.
         */
        fun fromCamera(cameraPosW: Vec3, headingW: Vec3, offsetFromCameraM: Vec3): HeadPose {
            val f = headingW.horizontal().normalized()
            val right = f cross Vec3.UP
            val p = cameraPosW + right * offsetFromCameraM.x + Vec3.UP * offsetFromCameraM.y + f * offsetFromCameraM.z
            return HeadPose(p, f)
        }
    }
}

/** 머리 기준 수평 방위각(도, 오른쪽 +)과 수평 거리(m). 고도각은 쓰지 않는다(§7.1). */
data class HeadRelative(val azimuthDeg: Float, val horizontalDistM: Float)

/** 월드 점 [pW]를 머리 기준 수평 방위각·거리로 (§7.1). 높이 차는 무시한다. */
fun headRelative(pW: Vec3, head: HeadPose): HeadRelative {
    val d = (pW - head.positionW).horizontal()
    val fwd = d dot head.headingW
    val right = d dot head.rightW
    return HeadRelative(Math.toDegrees(atan2(right, fwd).toDouble()).toFloat(), sqrt(fwd * fwd + right * right))
}
