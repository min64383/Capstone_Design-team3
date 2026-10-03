package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.types.HeadingConfig
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState

/**
 * 진행 방향 추정 (§7.5): 최근 `heading.windowS` 동안의 카메라 **수평 이동** 방향(창 자체가 평활 역할).
 * 이동량이 `heading.minTravelM` 미만이면 직전 값 유지, 초기값은 첫 TRACKING 프레임의 카메라 정면(C_cv +Z) 수평 투영.
 * 카메라 회전(손목 요)은 쓰지 않는다. 과거 자세만 쓴다.
 */
class Heading(private val cfg: HeadingConfig) {

    private val history = ArrayDeque<Pair<Long, Vec3>>()

    /** 현재 진행 방향(수평 단위 벡터). 아직 모르면 null. */
    var headingW: Vec3? = null
        private set

    /** 자세 하나로 갱신한다. TRACKING이 아니면 아무것도 바꾸지 않는다. */
    fun update(pose: PoseFrame): Vec3? {
        if (pose.tracking != TrackingState.TRACKING) return headingW
        val p = pose.worldFromCam.translation()
        if (headingW == null) {
            val f = pose.worldFromCam.axis(2).horizontal()
            if (f.norm() > 1e-3f) headingW = f.normalized()
        }
        history.addLast(pose.tCaptureNs to p)
        val windowStart = pose.tCaptureNs - (cfg.windowS * 1e9).toLong()
        // 창 시작 시각 이전의 가장 최근 항목 하나만 남긴다(창 전체 이동량 기준점)
        while (history.size > 1 && history[1].first <= windowStart) history.removeFirst()
        val move = (p - history.first().second).horizontal()
        if (move.norm() >= cfg.minTravelM) headingW = move.normalized()
        return headingW
    }

    /** 잊는다(자세 불연속: 이전 월드 좌표의 궤적은 쓸 수 없다). */
    fun reset() {
        history.clear()
        headingW = null
    }

    companion object {
        /** 진행 방향을 도 단위로: 월드 −Z에서 +X 쪽으로 잰 각(합성 장면 `Walk.headingDeg`와 같은 규약). */
        fun toDeg(headingW: Vec3): Float = Math.toDegrees(kotlin.math.atan2(headingW.x, -headingW.z).toDouble()).toFloat()
    }
}
