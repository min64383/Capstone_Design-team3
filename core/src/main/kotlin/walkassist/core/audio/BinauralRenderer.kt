package walkassist.core.audio

import walkassist.core.types.AudioCmd
import walkassist.core.types.Config
import walkassist.core.types.GuidanceOutput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HRTF 바이노럴 렌더러 (§7.6). 블록(`audio.blockSize`)마다 그 블록의 [GuidanceOutput](최신 자세로 방금 계산한 값)을 받아
 * 스테레오 블록을 만든다.
 * - 음원마다 버스트 열(구간·거리에 따른 주기)을 만들어 HRIR과 시간 영역 합성곱. 합성곱 이력(taps − 1)을 블록 사이에 이어 붙인다.
 * - 방위각이 바뀌면 그 블록 안에서 이전 HRIR 결과 → 새 HRIR 결과로 선형 교차 페이드(클릭 방지).
 * - 음원이 명령에서 빠지면 새 버스트는 시작하지 않고, 진행 중 버스트와 합성곱 꼬리만 끝까지 낸다(급한 끊김 없음).
 * - 정보 나이가 `policy.maxInfoAgeMs`를 넘는 명령은 다시 한번 버린다(§2.2-4 이중 방어).
 * - 알림음은 비공간(양쪽 같음)으로 섞고, 마지막에 주 음량과 리미터(블록 사이 선형 이득 램프).
 * 스레드를 모르고 같은 입력이면 같은 출력이다.
 */
class BinauralRenderer(private val config: Config, private val hrtf: Hrtf) {

    private val audio = config.audio
    private val n = audio.blockSize
    private val taps = hrtf.taps
    private val sounds = Sounds(audio, config.policy)
    private val master = Sounds.db(audio.masterGainDb)

    private inner class Source {
        val history = FloatArray(taps - 1) // 직전 입력의 끝(합성곱 이력)
        var az = Float.NaN
        var sinceOnset = Int.MAX_VALUE / 2 // 마지막 버스트 시작 후 샘플 수
        var burst: FloatArray? = null
        var burstPos = 0
        var gain = 1f
        var active = false
        var quietBlocks = 0
    }

    private val sources = HashMap<Int, Source>()
    private val alerts = ArrayDeque<FloatArray>()
    private var alertPos = 0
    private var limiterGain = 1f

    // 블록 작업 버퍼(할당 없이 재사용)
    private val mono = FloatArray(n)
    private val ext = FloatArray(taps - 1 + n)
    private val hL = FloatArray(taps); private val hR = FloatArray(taps)
    private val pL = FloatArray(taps); private val pR = FloatArray(taps)
    private val outA = FloatArray(2 * n); private val outB = FloatArray(2 * n)

    init {
        require(hrtf.sampleRate == audio.sampleRate) { "HRTF ${hrtf.sampleRate} Hz != audio.sampleRate ${audio.sampleRate}" }
    }

    /** 한 블록을 [out](길이 2 × blockSize, L R 교차)에 쓴다. */
    fun render(g: GuidanceOutput, out: FloatArray) {
        require(out.size == 2 * n) { "out must be 2 × blockSize" }
        out.fill(0f)
        g.alert?.let { alerts.addLast(sounds.alert(it)) }

        val cmds = g.commands.filter { it.infoAgeMs <= config.policy.maxInfoAgeMs }
        for (s in sources.values) s.active = false
        for (c in cmds) sources.getOrPut(c.obstacleId) { Source() }.let { it.active = true; renderSource(it, c, out) }
        // 명령에서 빠진 음원: 새 버스트 없이 꼬리만
        val it = sources.entries.iterator()
        while (it.hasNext()) {
            val (_, s) = it.next()
            if (s.active) continue
            renderSource(s, null, out)
            if (s.quietBlocks * n > taps) it.remove()
        }
        mixAlerts(out)
        limit(out)
    }

    private fun renderSource(s: Source, c: AudioCmd?, out: FloatArray) {
        // 1) 입력 버스트 열
        val period = c?.let { sounds.periodSamples(it.band, it.distanceM) }
        var any = false
        for (i in 0 until n) {
            if (s.burst == null && period != null && s.sinceOnset >= period) {
                s.burst = sounds.burst(c.sound)
                s.burstPos = 0
                s.gain = sounds.bandGain(c.band)
                s.sinceOnset = 0
            }
            val b = s.burst
            mono[i] = if (b != null) {
                val v = b[s.burstPos++] * s.gain
                if (s.burstPos >= b.size) s.burst = null
                v
            } else 0f
            if (mono[i] != 0f) any = true
            s.sinceOnset++
        }
        s.quietBlocks = if (any) 0 else s.quietBlocks + 1
        spatialize(s, c?.azimuthDeg ?: s.az, mono, out)
    }

    /** 모노 블록 [input]을 방위각 [az]로 공간화해 [out]에 더한다(이전 방위각과 다르면 블록 안 교차 페이드). */
    private fun spatialize(s: Source, az: Float, input: FloatArray, out: FloatArray) {
        System.arraycopy(s.history, 0, ext, 0, taps - 1)
        System.arraycopy(input, 0, ext, taps - 1, n)
        if (s.az.isNaN()) s.az = az
        hrtf.hrir(az, hL, hR)
        convolve(hL, hR, outB)
        if (az != s.az) {
            hrtf.hrir(s.az, pL, pR)
            convolve(pL, pR, outA)
            for (i in 0 until n) {
                val t = (i + 1f) / n
                out[2 * i] += outA[2 * i] * (1 - t) + outB[2 * i] * t
                out[2 * i + 1] += outA[2 * i + 1] * (1 - t) + outB[2 * i + 1] * t
            }
        } else {
            for (i in 0 until 2 * n) out[i] += outB[i]
        }
        s.az = az
        System.arraycopy(ext, n, s.history, 0, taps - 1)
    }

    private val testSource by lazy { Source() }

    /** 테스트용: 연속 모노 신호 한 블록을 음원 하나로 공간화(주 음량·리미터 없음). 클릭·좌우 에너지 검증에 쓴다. */
    internal fun spatializeForTest(input: FloatArray, azimuthDeg: Float, out: FloatArray) {
        out.fill(0f)
        spatialize(testSource, azimuthDeg, input, out)
    }

    /** ext(이력 + 이번 블록)를 좌우 HRIR과 합성곱해 교차 스테레오로. */
    private fun convolve(l: FloatArray, r: FloatArray, dst: FloatArray) {
        for (i in 0 until n) {
            var sl = 0f
            var sr = 0f
            val base = i + taps - 1
            for (k in 0 until taps) {
                val x = ext[base - k]
                if (x == 0f) continue
                sl += l[k] * x
                sr += r[k] * x
            }
            dst[2 * i] = sl
            dst[2 * i + 1] = sr
        }
    }

    private fun mixAlerts(out: FloatArray) {
        var i = 0
        while (i < n) {
            val a = alerts.firstOrNull() ?: return
            val m = min(n - i, a.size - alertPos)
            for (k in 0 until m) {
                out[2 * (i + k)] += a[alertPos + k]
                out[2 * (i + k) + 1] += a[alertPos + k]
            }
            i += m
            alertPos += m
            if (alertPos >= a.size) {
                alerts.removeFirst()
                alertPos = 0
            }
        }
    }

    /**
     * 주 음량 적용 후 리미터: 블록 최댓값이 상한 안에 들도록 목표 이득을 정하고, 블록 안에서 직전 이득 → 목표로 선형 램프
     * (블록 경계에서 이득이 튀지 않음). 램프 초반에 그래도 상한을 넘는 드문 샘플만 잘라 상한을 보장한다.
     */
    private fun limit(out: FloatArray) {
        var peak = 0f
        for (v in out) peak = max(peak, abs(v * master))
        val target = if (peak > audio.limiterCeiling) audio.limiterCeiling / peak else min(1f, limiterGain + 0.05f)
        val start = limiterGain
        val c = audio.limiterCeiling
        for (i in 0 until n) {
            val gi = master * (start + (target - start) * (i + 1f) / n)
            out[2 * i] = (out[2 * i] * gi).coerceIn(-c, c)
            out[2 * i + 1] = (out[2 * i + 1] * gi).coerceIn(-c, c)
        }
        limiterGain = target
    }
}
