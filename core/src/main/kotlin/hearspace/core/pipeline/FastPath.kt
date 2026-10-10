package hearspace.core.pipeline

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Quaternion
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Heading
import hearspace.core.guidance.MapAction
import hearspace.core.guidance.Policy
import hearspace.core.guidance.StateMachine
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState
import kotlin.math.acos
import kotlin.math.min

/**
 * 빠른 경로 (§7.7): 자세 → 진행 방향 → 머리 자세 → 상태 기계 → 거리 구간·음원.
 * 스레드를 모르고, 입력 자세와 스냅샷, 현재 시각만 쓴다(미래 값 없음).
 * 느린 경로 맵에 대한 명령(복귀 시 SCALE, 자세 불연속 시 RESET)은 [takeMapAction]으로 꺼낸다(M7: 최신 값 슬롯으로 전달).
 */
class FastPath(
    private val config: Config,
    /** 진행 방향 대입(정답 대입 H, M12.3 평가 전용): 추정한 진행 방향을 바꿔 쓴다. 앱은 넘기지 않는다(null = 그대로). */
    private val headingOverride: ((Vec3) -> Vec3)? = null,
) {

    private val heading = Heading(config.heading)
    private val stateMachine = StateMachine(config.state, config.policy.maxInfoAgeMs)
    private val policy = Policy(config.policy, config.corridor.behindM)
    private var lastTracked: PoseFrame? = null
    private var pendingMapAction = MapAction.NONE
    private var lastPoseTNs = Long.MIN_VALUE

    /** 사용자 일시정지(§7.5 PAUSED). */
    var paused = false

    /** 현재 진행 방향(느린 경로의 통로·삭제 기준으로 넘긴다). 모르면 null. */
    val headingW: Vec3? get() = adjusted(heading.headingW)

    /** 마지막 머리 자세. */
    var head: HeadPose? = null
        private set

    /** 쌓인 맵 명령을 꺼낸다(RESET이 SCALE보다 우선). 꺼내면 NONE으로 돌아간다. */
    fun takeMapAction(): MapAction = pendingMapAction.also { pendingMapAction = MapAction.NONE }

    /**
     * 오디오 블록 하나(또는 자세 하나)마다 호출. [nowNs]는 자세 시각과 같은 시계.
     * 같은 자세로 여러 블록을 계산해도 진행 방향·불연속·복귀 판정은 새 자세일 때만 진행한다(정보 나이·음원은 매번).
     */
    fun compute(pose: PoseFrame, snapshot: ObstacleSnapshot?, nowNs: Long): GuidanceOutput {
        val newFrame = pose.tCaptureNs != lastPoseTNs
        lastPoseTNs = pose.tCaptureNs
        val discontinuity = newFrame && isDiscontinuous(pose)
        if (discontinuity) heading.reset()
        if (pose.tracking == TrackingState.TRACKING) lastTracked = pose

        val h = adjusted(if (newFrame) heading.update(pose) else heading.headingW)
        head = h?.let { HeadPose.fromCamera(pose.worldFromCam.translation(), it, config.head.offsetFromCameraM) }
        val infoAgeMs = snapshot?.let { (nowNs - it.tCaptureNs) / 1e6f }
        val step = stateMachine.step(nowNs, newFrame, pose.tracking, discontinuity, infoAgeMs, snapshot?.mapHealth, paused)
        if (step.mapAction == MapAction.RESET || (step.mapAction == MapAction.SCALE && pendingMapAction == MapAction.NONE)) {
            pendingMapAction = step.mapAction
        }

        val active = step.state == GuidanceState.NORMAL || step.state == GuidanceState.DEGRADED
        val hd = head
        val commands = if (active && hd != null && snapshot != null && infoAgeMs != null) {
            policy.commands(snapshot, hd, infoAgeMs)
        } else {
            policy.clear() // 확인 불가·정지 중에는 음원 없음(§2.2-5), 구간 기억도 버린다
            emptyList()
        }
        return GuidanceOutput(nowNs, step.state, commands, h?.let { Heading.toDeg(it) } ?: Float.NaN, step.alert)
    }

    private fun adjusted(h: Vec3?): Vec3? = h?.let { headingOverride?.invoke(it) ?: it }

    /** 직전 TRACKING 자세와 비교해 속도·각속도가 한계를 넘으면 자세 불연속(§7.5, v0.2.1). */
    private fun isDiscontinuous(pose: PoseFrame): Boolean {
        val prev = lastTracked ?: return false
        if (pose.tracking != TrackingState.TRACKING) return false
        val dtS = (pose.tCaptureNs - prev.tCaptureNs) / 1e9f
        if (dtS <= 0f) return false
        val speed = (pose.worldFromCam.translation() - prev.worldFromCam.translation()).norm() / dtS
        val qa = Quaternion.fromMat4(prev.worldFromCam)
        val qb = Quaternion.fromMat4(pose.worldFromCam)
        val dot = min(1f, kotlin.math.abs(qa.x * qb.x + qa.y * qb.y + qa.z * qb.z + qa.w * qb.w))
        val angDeg = Math.toDegrees(2.0 * acos(dot.toDouble())).toFloat()
        return speed > config.state.maxSpeedMps || angDeg / dtS > config.state.maxAngularSpeedDps
    }
}
