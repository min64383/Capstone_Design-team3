package walkassist.core.audio

import walkassist.core.types.AlertKind
import walkassist.core.types.AudioConfig
import walkassist.core.types.Band
import walkassist.core.types.PolicyConfig
import walkassist.core.types.SoundKind
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 소리 패턴 (§7.6, 모두 가설·설정값).
 * - FLOOR_PULSE: 분홍 잡음 버스트(`audio.pulseMs`, 올림·내림 포락선). WARN은 거리 warnMaxM → stopM로 갈수록 반복 주기가
 *   `warnFarPeriodMs` → `warnNearPeriodMs`로 선형 단축, STOP은 `stopPeriodMs` + `stopGainDb`.
 * - HEAD_TONE: `audio.headToneHz` 사인 버스트(고역, 높이는 음색으로 구분).
 * - 알림음: 비공간 짧은 음, 서로 다른 음높이·리듬.
 * 잡음은 시드 고정이라 같은 입력이면 같은 출력이다.
 */
class Sounds(private val audio: AudioConfig, private val policy: PolicyConfig) {

    private val sr = audio.sampleRate
    private val pulseLen = (audio.pulseMs / 1000f * sr).toInt().coerceAtLeast(8)
    private val floorPulse = pinkBurst(pulseLen)
    private val headPulse = toneBurst(audio.headToneHz, pulseLen)

    /** 한 번의 버스트(모노). */
    fun burst(kind: SoundKind): FloatArray = if (kind == SoundKind.HEAD_TONE) headPulse else floorPulse

    /** 구간·거리의 반복 주기(샘플). SILENT면 null(소리 없음). */
    fun periodSamples(band: Band, distanceM: Float): Int? {
        val ms = when (band) {
            Band.SILENT -> return null
            Band.STOP -> audio.stopPeriodMs
            Band.WARN -> {
                val t = ((distanceM - policy.stopM) / (policy.warnMaxM - policy.stopM)).coerceIn(0f, 1f)
                audio.warnNearPeriodMs + (audio.warnFarPeriodMs - audio.warnNearPeriodMs) * t
            }
        }
        return (ms / 1000f * sr).toInt().coerceAtLeast(pulseLen + 1)
    }

    /** 구간 음량(선형). */
    fun bandGain(band: Band): Float = if (band == Band.STOP) db(audio.stopGainDb) else 1f

    /** 상태 알림음(모노, 비공간). */
    fun alert(kind: AlertKind): FloatArray {
        val g = db(audio.alertGainDb)
        val notes = when (kind) {
            AlertKind.START -> listOf(523f to 120, 659f to 120, 784f to 180) // 오르는 세 음
            AlertKind.READY -> listOf(784f to 90, 1047f to 160) // 짧게 오름
            AlertKind.PAUSE -> listOf(659f to 90, 0f to 60, 659f to 90) // 같은 음 두 번
            AlertKind.UNKNOWN -> listOf(392f to 250, 294f to 350) // 낮게 내려감
            AlertKind.WAITING -> listOf(440f to 60) // 짧고 부드러운 한 음(준비 대기 중 반복, M9)
        }
        val out = ArrayList<Float>()
        for ((hz, ms) in notes) {
            val n = ms * sr / 1000
            val tone = if (hz == 0f) FloatArray(n) else toneBurst(hz, n)
            for (v in tone) out += v * g
        }
        return out.toFloatArray()
    }

    private fun pinkBurst(n: Int): FloatArray {
        // Paul Kellet 분홍 잡음 근사(시드 고정)
        val rnd = Random(20260929)
        var b0 = 0f; var b1 = 0f; var b2 = 0f
        val out = FloatArray(n)
        for (i in 0 until n) {
            val w = rnd.nextFloat() * 2f - 1f
            b0 = 0.99765f * b0 + w * 0.0990460f
            b1 = 0.96300f * b1 + w * 0.2965164f
            b2 = 0.57000f * b2 + w * 1.0526913f
            out[i] = (b0 + b1 + b2 + w * 0.1848f) * 0.25f
        }
        return envelope(normalize(out))
    }

    private fun toneBurst(hz: Float, n: Int): FloatArray {
        val out = FloatArray(n) { i -> sin(2.0 * PI * hz * i / sr).toFloat() }
        return envelope(normalize(out))
    }

    /** 앞뒤 20%에 반 사인 올림·내림(클릭 방지) + 약한 지수 감쇠. */
    private fun envelope(x: FloatArray): FloatArray {
        val n = x.size
        val ramp = (n * 0.2f).toInt().coerceAtLeast(2)
        for (i in 0 until n) {
            val a = when {
                i < ramp -> sin(PI / 2 * i / ramp).toFloat()
                i >= n - ramp -> sin(PI / 2 * (n - 1 - i) / ramp).toFloat()
                else -> 1f
            }
            x[i] *= a * exp(-1.5f * i / n)
        }
        return x
    }

    private fun normalize(x: FloatArray): FloatArray {
        val peak = x.maxOf { kotlin.math.abs(it) }
        if (peak > 0f) for (i in x.indices) x[i] /= peak
        return x
    }

    companion object {
        /** dB → 선형 진폭. */
        fun db(dB: Float): Float = 10f.pow(dB / 20f)
    }
}
