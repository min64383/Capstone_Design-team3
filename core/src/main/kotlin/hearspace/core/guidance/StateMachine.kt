package hearspace.core.guidance

import hearspace.core.types.AlertKind
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
import hearspace.core.types.StateConfig
import hearspace.core.types.TrackingState

/** 느린 경로 맵에 대한 명령(§7.5 복귀·자세 불연속). */
enum class MapAction { NONE, SCALE, RESET }

/** 상태 기계 한 번의 결과. */
data class StateStep(val state: GuidanceState, val alert: AlertKind?, val mapAction: MapAction)

/**
 * 안내 상태 기계 (§7.5).
 * - 확인 불가(추적 아님·자세 불연속·정보 없음/만료) → `UNKNOWN`: 음원 중단, 알림 1회 후 `unknownRepeatS` 간격 반복.
 *   자세 불연속이면 즉시 맵 초기화(RESET).
 * - 복귀: 좋은 프레임(추적 + 정보 정상)이 `recoverFrames` 연속 → `NORMAL` + READY. 맵은 초기화한 경우가 아니면 SCALE.
 * - 시작 직후와 일시정지 해제 뒤에도 같은 복귀 규칙으로 기다린다(이때는 UNKNOWN 알림을 내지 않는다: 아직 안내 전).
 *   대신 기다리는 동안 `unknownRepeatS` 간격으로 대기음(WAITING)을 낸다(§11.2 "추적 준비 중 대기음", M9).
 * - `DEGRADED`: 정보 나이 > 허용치의 50%, 또는 맵 상태 DEGRADED. 음원은 유지.
 */
class StateMachine(private val cfg: StateConfig, private val maxInfoAgeMs: Float) {

    /** 현재 상태. */
    var state: GuidanceState = GuidanceState.UNKNOWN
        private set

    private var goodFrames = 0
    private var announcedUnknown = false // 안내 도중 확인 불가로 들어왔는지(반복 알림 대상)
    private var lastUnknownAlertNs = 0L
    private var resetSinceLoss = false
    private var lastWaitingAlertNs: Long? = null // 준비 대기 중(시작·재개 뒤) 대기음 기준 시각

    /**
     * 한 번 진행. [infoAgeMs]는 스냅샷이 없으면 null. [newFrame]은 이번 자세가 새 ARCore 프레임인지:
     * 오디오 블록마다 같은 자세로 불려도 복귀용 좋은 프레임은 새 프레임일 때만 센다(M7).
     */
    fun step(
        nowNs: Long,
        newFrame: Boolean,
        tracking: TrackingState,
        discontinuity: Boolean,
        infoAgeMs: Float?,
        mapHealth: MapHealth?,
        paused: Boolean,
    ): StateStep {
        if (paused) {
            val alert = if (state != GuidanceState.PAUSED) AlertKind.PAUSE else null
            state = GuidanceState.PAUSED
            goodFrames = 0
            announcedUnknown = false
            lastWaitingAlertNs = null
            return StateStep(state, alert, MapAction.NONE)
        }
        if (state == GuidanceState.PAUSED) state = GuidanceState.UNKNOWN // 해제: 복귀 규칙으로 대기
        if (state == GuidanceState.UNKNOWN && !announcedUnknown && lastWaitingAlertNs == null) lastWaitingAlertNs = nowNs

        val good = tracking == TrackingState.TRACKING && !discontinuity && infoAgeMs != null && infoAgeMs <= maxInfoAgeMs
        if (!good) {
            goodFrames = 0
            var action = MapAction.NONE
            if (discontinuity) {
                action = MapAction.RESET
                resetSinceLoss = true
            }
            var alert: AlertKind? = null
            if (state == GuidanceState.NORMAL || state == GuidanceState.DEGRADED) {
                alert = AlertKind.UNKNOWN
                announcedUnknown = true
                lastUnknownAlertNs = nowNs
            } else if (announcedUnknown && nowNs - lastUnknownAlertNs >= (cfg.unknownRepeatS * 1e9).toLong()) {
                alert = AlertKind.UNKNOWN // 짧은 반복 알림
                lastUnknownAlertNs = nowNs
            } else {
                alert = waitingAlert(nowNs)
            }
            state = GuidanceState.UNKNOWN
            return StateStep(state, alert, action)
        }

        if (state == GuidanceState.UNKNOWN) {
            if (newFrame) goodFrames++
            if (goodFrames < cfg.recoverFrames) return StateStep(state, waitingAlert(nowNs), MapAction.NONE)
            lastWaitingAlertNs = null
            val action = if (resetSinceLoss) MapAction.NONE else MapAction.SCALE
            resetSinceLoss = false
            announcedUnknown = false
            state = GuidanceState.NORMAL
            return StateStep(degradedOrNormal(infoAgeMs, mapHealth), AlertKind.READY, action)
        }
        return StateStep(degradedOrNormal(infoAgeMs, mapHealth), null, MapAction.NONE)
    }

    /** 준비 대기 중이면 `unknownRepeatS`마다 WAITING. 안내 도중 확인 불가(UNKNOWN 반복 대상)에는 내지 않는다. */
    private fun waitingAlert(nowNs: Long): AlertKind? {
        val since = lastWaitingAlertNs ?: return null
        if (announcedUnknown || nowNs - since < (cfg.unknownRepeatS * 1e9).toLong()) return null
        lastWaitingAlertNs = nowNs
        return AlertKind.WAITING
    }

    private fun degradedOrNormal(infoAgeMs: Float?, mapHealth: MapHealth?): GuidanceState {
        state = if ((infoAgeMs ?: 0f) > 0.5f * maxInfoAgeMs || mapHealth == MapHealth.DEGRADED) {
            GuidanceState.DEGRADED
        } else {
            GuidanceState.NORMAL
        }
        return state
    }
}
