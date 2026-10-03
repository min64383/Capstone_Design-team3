package hearspace.core.audio

import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.SoundKind
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** 물체 하나의 과거 거리 이력과 연속 복합음. 오디오 스레드 한 곳에서 사용한다. */
class RiskSoundGenerator(config: Config) {
    private val p = config.sonify
    private val sr = config.audio.sampleRate
    private val maxInfoAgeMs = config.policy.maxInfoAgeMs
    private val capacity = ceil(p.historyS * sr / config.audio.blockSize).toInt() + 3
    private val timesNs = LongArray(capacity)
    private val distancesM = FloatArray(capacity)
    private var first = 0
    private var count = 0
    private var lastNs = Long.MIN_VALUE
    private var previousBand: Band? = null
    private var eventUntilNs = Long.MIN_VALUE
    private var approaching = false
    private var phase = 0.0
    private var amplitude = 0f
    private var frequencyHz = p.minHz
    private var secondHarmonic = p.minSecondHarmonic
    private val pitchAlpha = (1 - exp(-1.0 / (sr * p.pitchSmoothS))).toFloat()
    private val attackAlpha = (1 - exp(-1.0 / (sr * p.attackS))).toFloat()
    private val releaseAlpha = (1 - exp(-1.0 / (sr * p.releaseS))).toFloat()

    /** 진단값. 유효한 접근이 없으면 TTC는 무한대. */
    var closingMps = 0f
        private set
    var ttcS = Float.POSITIVE_INFINITY
        private set
    var risk = 0f
        private set
    var active = false
        private set
    val quiet: Boolean get() = amplitude == 0f

    /** 재생 시각 역행/월드 좌표 초기화에도 사용하는 전체 상태 초기화. */
    fun reset() {
        first = 0; count = 0; lastNs = Long.MIN_VALUE
        previousBand = null; eventUntilNs = Long.MIN_VALUE; approaching = false
        phase = 0.0; amplitude = 0f; frequencyHz = p.minHz
        secondHarmonic = p.minSecondHarmonic
        closingMps = 0f; ttcS = Float.POSITIVE_INFINITY; risk = 0f; active = false
    }

    /** 물체의 최신 명령으로 모노 한 블록 생성. null은 새 소리 없이 release만 처리. */
    fun render(command: AudioCmd?, timeNs: Long, duckGain: Float, output: FloatArray) {
        if (lastNs != Long.MIN_VALUE && timeNs < lastNs) reset()
        val valid = command != null && command.distanceM.isFinite() && command.distanceM > 0f &&
            command.infoAgeMs.isFinite() && command.infoAgeMs >= 0f && command.infoAgeMs <= maxInfoAgeMs
        if (command != null && !valid) {
            reset(); output.fill(0f); return
        }
        var targetHz = frequencyHz
        var targetGain = 0f
        var targetSecond = secondHarmonic
        active = false
        if (valid) {
            val c = command!!
            if (timeNs != lastNs) {
                val oldestNs = timeNs - (p.historyS * 1e9).toLong()
                while (count > 0 && timesNs[first] < oldestNs) {
                    first = (first + 1) % capacity; count--
                }
                if (count == capacity) { first = (first + 1) % capacity; count-- }
                val index = (first + count) % capacity
                timesNs[index] = timeNs; distancesM[index] = c.distanceM; count++
                lastNs = timeNs
            }
            val elapsedS = (timeNs - timesNs[first]) / 1e9f
            closingMps = if (elapsedS >= p.minHistoryS) (distancesM[first] - c.distanceM) / elapsedS else 0f
            approaching = if (approaching) closingMps > p.approachOffMps else closingMps >= p.approachOnMps
            ttcS = if (closingMps >= p.approachOnMps) c.distanceM / closingMps else Float.POSITIVE_INFINITY
            val proximity = ((p.farM - c.distanceM) / (p.farM - p.nearM)).coerceIn(0f, 1f)
            val ttcRisk = if (c.inCorridor) (1 - ttcS / p.ttcHorizonS).coerceIn(0f, 1f) else 0f
            val stop = c.inCorridor && c.band == Band.STOP
            risk = if (stop) 1f else (proximity + (1 - proximity) * p.ttcWeight * ttcRisk).coerceIn(0f, 1f)
            if (previousBand == null || previousBand != c.band) eventUntilNs = timeNs + (p.onceS * 1e9).toLong()
            previousBand = c.band
            active = c.band != Band.SILENT && (stop || approaching || timeNs < eventUntilNs)
            targetHz = p.minHz * (p.maxHz / p.minHz).pow(proximity)
            if (c.sound == SoundKind.HEAD_TONE) targetHz *= p.headPitchRatio
            targetGain = if (active) (p.minGain + (p.maxGain - p.minGain) * risk) * p.prototypeGain * duckGain else 0f
            targetSecond = p.minSecondHarmonic + (p.maxSecondHarmonic - p.minSecondHarmonic) * risk
        } else {
            // 명령 소실 뒤 재등장하면 새 안내가 가능하도록 이력을 버린다. 위상/진폭은 release 동안 유지.
            first = 0; count = 0; previousBand = null; approaching = false
            eventUntilNs = Long.MIN_VALUE; closingMps = 0f; ttcS = Float.POSITIVE_INFINITY
        }
        val ampAlpha = if (targetGain > amplitude) attackAlpha else releaseAlpha
        for (i in output.indices) {
            frequencyHz += pitchAlpha * (targetHz - frequencyHz)
            secondHarmonic += pitchAlpha * (targetSecond - secondHarmonic)
            amplitude += ampAlpha * (targetGain - amplitude)
            if (targetGain == 0f && amplitude < 1e-7f) amplitude = 0f
            phase = (phase + 2 * PI * frequencyHz / sr) % (2 * PI)
            val wave = (sin(phase) + secondHarmonic * sin(2 * phase) + p.thirdHarmonic * sin(3 * phase)) /
                (1 + secondHarmonic + p.thirdHarmonic)
            output[i] = amplitude * wave.toFloat()
        }
    }
}
