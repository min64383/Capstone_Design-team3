package walkassist.core.audio

import walkassist.core.types.AudioCmd
import walkassist.core.types.AudioConfig
import walkassist.core.types.PolicyConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 방향은 스테레오 좌우 밸런스,
 * 거리는 비프 반복 주기로 표현하는 단순 프로토타입 렌더러.
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

        /*
         * sin(azimuth)를 이용하므로:
         *
         * -90° = -1 = 완전 왼쪽
         *   0° =  0 = 가운데
         * +90° = +1 = 완전 오른쪽
         *
         * ±180°는 다시 가운데가 된다.
         *
         * 따라서 현재 버전에서는 앞/뒤는 구분 못하고
         * 좌/우 방향만 전달한다.
         */
        val pan =
            sin(Math.toRadians(command.azimuthDeg.toDouble()))
                .coerceIn(-1.0, 1.0)

        /*
         * Constant-power stereo panning.
         */
        val panAngle =
            (pan + 1.0) * PI / 4.0

        val leftGain = cos(panAngle)
        val rightGain = sin(panAngle)

        val masterGain =
            10.0.pow(audio.masterGainDb / 20.0)
                .coerceIn(0.0, 1.0)

        val phaseStep =
            2.0 * PI * audio.toneHz / audio.sampleRate

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

            val mono =
                sin(oscillatorPhaseRad) *
                        envelope *
                        masterGain

            val left =
                (mono * leftGain * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    )

            val right =
                (mono * rightGain * Short.MAX_VALUE)
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

            cycleFrame++
        }
    }

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