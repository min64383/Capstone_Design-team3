package walkassist.core.audio

import walkassist.core.types.AudioCmd
import walkassist.core.types.AudioConfig
import walkassist.core.types.PolicyConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * 방향은 스테레오 좌우 밸런스 + 두 귀 시간차(ITD), 뒤쪽은 다른 음높이,
 * 거리는 비프 반복 주기로 표현하는 단순 프로토타입 렌더러.
 * M6 HRTF 전까지 쓰는 임시판이다.
 *
 * 출력 배열은 L, R, L, R... 순서의 PCM 16-bit stereo이다.
 */
class SimpleBeepRenderer(
    private val audio: AudioConfig,
    private val policy: PolicyConfig,
) {

    private var oscillatorPhaseRad = 0.0
    private var cycleFrame = 0L

    private var lastObstacleId: Int? = null

    // ponytail: ITD는 정수 프레임 지연(48 kHz면 약 21 µs 단위)이고, 방위각이 비프 도중 바뀌면 지연이 튀어 click이 날 수 있다. M6 HRTF에서 대체
    private val maxItdFrames =
        (audio.maxItdMs * audio.sampleRate / 1000f)
            .roundToInt()

    /** ITD용 mono 이력(원형 버퍼). */
    private val history = DoubleArray(maxItdFrames + 1)
    private var historyIndex = 0

    /**
     * 현재 장애물 명령을 stereo PCM 한 블록으로 변환한다.
     *
     * command가 null이거나 너무 오래됐거나 너무 멀면 무음을 출력한다.
     */
    fun render(
        command: AudioCmd?,
        output: ShortArray,
    ) {
        require(output.size == audio.blockSize * 2) {
            "output must contain ${audio.blockSize * 2} shorts"
        }

        if (!isPlayable(command)) {
            output.fill(0)

            lastObstacleId = null
            cycleFrame = 0L

            return
        }

        command!!

        if (command.obstacleId != lastObstacleId) {
            cycleFrame = 0L
            lastObstacleId = command.obstacleId
        }

        val periodMs = beepPeriodMs(command.distanceM)

        val periodFrames =
            (periodMs * audio.sampleRate / 1000f)
                .roundToInt()
                .coerceAtLeast(1)

        val onFrames =
            (audio.beepOnMs * audio.sampleRate / 1000f)
                .roundToInt()
                .coerceIn(1, periodFrames)

        val cue = stereoCue(command.azimuthDeg)

        /*
         * Constant-power stereo panning.
         */
        val panAngle =
            (cue.lateral + 1.0) * PI / 4.0

        val leftGain = cos(panAngle)
        val rightGain = sin(panAngle)

        /*
         * 두 귀 시간차(ITD): 먼 쪽 귀를 늦게 들리게 한다.
         * lateral > 0(오른쪽)이면 왼쪽 귀가 늦다.
         */
        val itdFrames =
            (abs(cue.lateral) * maxItdFrames)
                .roundToInt()

        val leftDelay = if (cue.lateral > 0) itdFrames else 0
        val rightDelay = if (cue.lateral < 0) itdFrames else 0

        val masterGain =
            10.0.pow(audio.masterGainDb / 20.0)
                .coerceIn(0.0, 1.0)

        val toneHz =
            if (cue.rear) audio.rearToneHz else audio.toneHz

        val phaseStep =
            2.0 * PI * toneHz / audio.sampleRate

        for (frame in 0 until audio.blockSize) {

            val positionInPeriod =
                (cycleFrame % periodFrames).toInt()

            val beepActive =
                positionInPeriod < onFrames

            val envelope =
                if (beepActive) {

                    if (onFrames == 1) {
                        1.0
                    } else {
                        /*
                         * 비프 처음과 끝을 0으로 만들어
                         * 갑작스러운 click을 줄인다.
                         */
                        val t =
                            positionInPeriod.toDouble() /
                                    (onFrames - 1)

                        sin(PI * t).pow(2)
                    }

                } else {
                    0.0
                }

            history[historyIndex] =
                sin(oscillatorPhaseRad) *
                        envelope *
                        masterGain

            val left =
                (delayed(leftDelay) * leftGain * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    )

            val right =
                (delayed(rightDelay) * rightGain * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    )

            output[frame * 2] =
                left.toShort()

            output[frame * 2 + 1] =
                right.toShort()

            oscillatorPhaseRad += phaseStep

            if (oscillatorPhaseRad >= 2.0 * PI) {
                oscillatorPhaseRad -= 2.0 * PI
            }

            historyIndex = (historyIndex + 1) % history.size

            cycleFrame++
        }
    }

    /** [frames] 프레임 전의 mono 샘플. 현재 프레임은 이미 기록돼 있어야 한다. */
    private fun delayed(
        frames: Int,
    ): Double =
        history[(historyIndex - frames + history.size) % history.size]

    /**
     * 방위각을 좌우 위치와 앞/뒤로 나눈다.
     *
     * lateral은 방위각에 선형이다(sin보다 옆쪽 각도 차이가 크다).
     * 뒤쪽(|방위각| > 90°)은 앞쪽 거울 위치로 접고 rear로 표시한다.
     *
     *  -90° → -1, 0° → 0, +90° → +1, +135° → +0.5(rear), 180° → 0(rear)
     */
    internal fun stereoCue(
        azimuthDeg: Float,
    ): StereoCue {

        // (-180, 180]으로 정규화
        var a = azimuthDeg.toDouble() % 360.0
        if (a > 180.0) a -= 360.0
        if (a <= -180.0) a += 360.0

        val rear = abs(a) > 90.0

        val folded =
            if (rear) sign(a) * (180.0 - abs(a)) else a

        return StereoCue(
            lateral = (folded / 90.0).coerceIn(-1.0, 1.0),
            rear = rear,
        )
    }

    internal data class StereoCue(
        val lateral: Double,
        val rear: Boolean,
    )

    /**
     * 거리에서 비프 반복 간격을 계산한다.
     */
    internal fun beepPeriodMs(
        distanceM: Float,
    ): Float {

        val ratio =
            (distanceM / policy.silentMaxM)
                .coerceIn(0f, 1f)

        return audio.nearPeriodMs +
                (audio.farPeriodMs - audio.nearPeriodMs) *
                ratio
    }

    private fun isPlayable(
        command: AudioCmd?,
    ): Boolean {

        if (command == null) {
            return false
        }

        if (!command.distanceM.isFinite()) {
            return false
        }

        if (command.distanceM < 0f) {
            return false
        }

        if (command.distanceM > policy.silentMaxM) {
            return false
        }

        if (command.infoAgeMs > policy.maxInfoAgeMs) {
            return false
        }

        return true
    }
}