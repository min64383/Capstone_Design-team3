package hearspace.core.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import hearspace.core.types.AlertKind
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.SoundKind
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class BinauralTest {
    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val hrtf = Hrtf.parse(File(System.getProperty("hearspace.hrtfAsset")).readBytes())
    private val n = config.audio.blockSize
    private val sr = config.audio.sampleRate

    private fun out(g: GuidanceOutput? = null, cmds: List<AudioCmd> = emptyList(), alert: AlertKind? = null) =
        g ?: GuidanceOutput(0L, GuidanceState.NORMAL, cmds, 0f, alert)

    private fun cmd(az: Float, band: Band = Band.WARN, d: Float = 1.5f, sound: SoundKind = SoundKind.FLOOR_PULSE) =
        AudioCmd(1, az, d, band, sound, 0f)

    /** 연속 신호(sample index → 값)를 블록 단위로 공간화. [azOf]는 블록 번호 → 방위각. */
    private fun spatialize(signal: (Int) -> Float, blocks: Int, azOf: (Int) -> Float): FloatArray {
        val r = BinauralRenderer(config, hrtf)
        val all = FloatArray(2 * n * blocks)
        val inBuf = FloatArray(n)
        val o = FloatArray(2 * n)
        for (b in 0 until blocks) {
            for (i in 0 until n) inBuf[i] = signal(b * n + i)
            r.spatializeForTest(inBuf, azOf(b), o)
            o.copyInto(all, 2 * n * b)
        }
        return all
    }

    private fun spatializeSine(hz: Float, blocks: Int, azOf: (Int) -> Float) =
        spatialize({ 0.3f * sin(2 * PI * hz * it / sr).toFloat() }, blocks, azOf)

    /** 시드 고정 분홍 잡음(광대역, FLOOR_PULSE와 같은 성질). */
    private fun pinkNoise(len: Int): FloatArray {
        val rnd = kotlin.random.Random(7)
        var b0 = 0f; var b1 = 0f; var b2 = 0f
        return FloatArray(len) {
            val w = rnd.nextFloat() * 2f - 1f
            b0 = 0.99765f * b0 + w * 0.0990460f; b1 = 0.96300f * b1 + w * 0.2965164f; b2 = 0.57000f * b2 + w * 1.0526913f
            (b0 + b1 + b2 + w * 0.1848f) * 0.05f
        }
    }

    /** 채널별 최대 2차 차분(급변 = 클릭 지표). */
    private fun maxSecondDiff(x: FloatArray, ch: Int, skipSamples: Int): Float {
        var m = 0f
        for (i in (skipSamples + 2) until x.size / 2) {
            val d = x[2 * i + ch] - 2 * x[2 * (i - 1) + ch] + x[2 * (i - 2) + ch]
            m = maxOf(m, abs(d))
        }
        return m
    }

    @Test
    fun `hrir asset loads with the extracted grid and convention`() {
        assertEquals(48000, hrtf.sampleRate)
        assertEquals(256, hrtf.taps)
        assertEquals(400, hrtf.count)
        val l = FloatArray(256); val r = FloatArray(256)
        hrtf.hrir(-90f, l, r)
        assertTrue(l.sumOf { (it * it).toDouble() } > 10 * r.sumOf { (it * it).toDouble() }, "−90° = left louder")
        hrtf.hrir(90f, l, r)
        assertTrue(r.sumOf { (it * it).toDouble() } > 10 * l.sumOf { (it * it).toDouble() }, "+90° = right louder")
        assertEquals(-170f, Hrtf.wrap(190f), 1e-4f)
        assertThrows<IllegalArgumentException> { Hrtf.parse(ByteArray(40)) }
    }

    @Test
    fun `left-right energy ratio is monotonic from -60 to +60 degrees`() {
        // 광대역(분홍 잡음) 에너지비. 순음은 주파수마다 두 귀 차이가 달라 단조가 아닐 수 있다
        val noise = pinkNoise(40 * n)
        var prev = Float.NEGATIVE_INFINITY
        for (az in -60..60 step 5) {
            val v = spatialize({ noise[it] }, 40) { az.toFloat() }
            var el = 0.0; var er = 0.0
            for (i in 4 * n until v.size / 2) { el += v[2 * i] * v[2 * i]; er += v[2 * i + 1] * v[2 * i + 1] }
            val r = (10 * log10(er / el)).toFloat()
            assertTrue(r > prev, "R/L ratio must increase: az $az → $r dB (prev $prev)")
            prev = r
        }
    }

    @Test
    fun `azimuth changes every block without clicks`() {
        val fixed = spatializeSine(500f, 60) { 0f }
        val sweep = spatializeSine(500f, 60) { b -> -60f + 2f * b } // 블록마다 2° (초당 약 375°)
        val jump = spatializeSine(500f, 60) { b -> if (b % 10 < 5) -30f else 30f } // 60° 급변
        // 방위각 변화는 두 귀 시간차를 바꿔 위상이 조금 움직인다(2차 차분 약 1.7배). 진짜 클릭(진폭 ~0.2의 불연속)은 1e-2 이상이라
        // "고정 방위각 대비 3배 미만"이면 클릭이 없다고 본다
        for (ch in 0..1) {
            val ref = maxSecondDiff(fixed, ch, 4 * n)
            assertTrue(maxSecondDiff(sweep, ch, 4 * n) < 3f * ref, "sweep ch$ch: ${maxSecondDiff(sweep, ch, 4 * n)} vs $ref")
            assertTrue(maxSecondDiff(jump, ch, 4 * n) < 3f * ref, "jump ch$ch: ${maxSecondDiff(jump, ch, 4 * n)} vs $ref")
            assertTrue(maxSecondDiff(jump, ch, 4 * n) < 5e-3f)
        }
    }

    @Test
    fun `pulse period follows band and distance, silent band makes no sound`() {
        val s = Sounds(config.audio, config.policy)
        assertEquals((0.8 * sr).toInt(), s.periodSamples(Band.WARN, 2.5f))
        assertEquals((0.25 * sr).toInt(), s.periodSamples(Band.WARN, 1.0f))
        assertTrue(s.periodSamples(Band.WARN, 1.75f)!! in (0.525 * sr).toInt() - 2..(0.525 * sr).toInt() + 2) // 250 + 550 × 0.5
        assertEquals((0.1 * sr).toInt(), s.periodSamples(Band.STOP, 0.5f))
        assertEquals(null, s.periodSamples(Band.SILENT, 2.8f))

        val r = BinauralRenderer(config, hrtf)
        val o = FloatArray(2 * n)
        var energy = 0.0
        repeat(40) { r.render(out(cmds = listOf(cmd(0f, Band.SILENT, 2.8f))), o); energy += o.sumOf { (it * it).toDouble() } }
        assertEquals(0.0, energy)
    }

    @Test
    fun `stop is louder and faster than warn, head tone is higher pitched`() {
        fun renderSeconds(c: AudioCmd, seconds: Float): FloatArray {
            val r = BinauralRenderer(config, hrtf)
            val blocks = (seconds * sr / n).toInt()
            val all = FloatArray(2 * n * blocks); val o = FloatArray(2 * n)
            for (b in 0 until blocks) { r.render(out(cmds = listOf(c)), o); o.copyInto(all, 2 * n * b) }
            return all
        }
        fun onsets(x: FloatArray): Int { // 무음 → 소리 전환 수
            var c = 0; var prevQuiet = true
            for (i in 0 until x.size / 2) {
                val quiet = abs(x[2 * i]) + abs(x[2 * i + 1]) < 1e-4f
                if (prevQuiet && !quiet) c++
                prevQuiet = quiet
            }
            return c
        }
        val warn = renderSeconds(cmd(0f, Band.WARN, 2.0f), 2f)
        val stop = renderSeconds(cmd(0f, Band.STOP, 0.5f), 2f)
        assertTrue(onsets(stop) > 2 * onsets(warn), "stop ${onsets(stop)} vs warn ${onsets(warn)} onsets in 2 s")
        assertTrue(stop.maxOf { abs(it) } > warn.maxOf { abs(it) })
        // 음색: HEAD_TONE은 headToneHz 부근(±10%)에 에너지가 모인 순음, FLOOR_PULSE는 광대역 잡음
        val s = Sounds(config.audio, config.policy)
        fun bandFraction(x: FloatArray, lo: Float, hi: Float): Double {
            var inBand = 0.0; var total = 0.0
            for (k in 1 until x.size / 2) { // 단순 DFT(버스트 1440샘플)
                var re = 0.0; var im = 0.0
                for (i in x.indices) { val ph = 2 * PI * k * i / x.size; re += x[i] * kotlin.math.cos(ph); im -= x[i] * sin(ph) }
                val e = re * re + im * im
                total += e
                val hz = k.toFloat() * sr / x.size
                if (hz in lo..hi) inBand += e
            }
            return inBand / total
        }
        val f0 = config.audio.headToneHz
        val headFrac = bandFraction(s.burst(SoundKind.HEAD_TONE), 0.9f * f0, 1.1f * f0)
        val floorFrac = bandFraction(s.burst(SoundKind.FLOOR_PULSE), 0.9f * f0, 1.1f * f0)
        assertTrue(headFrac > 0.8 && floorFrac < 0.2, "head tone tonal $headFrac vs floor pulse $floorFrac")
    }

    @Test
    fun `limiter keeps output under the ceiling and alerts are non-spatial`() {
        val loud = config.copy(audio = config.audio.copy(masterGainDb = 20f))
        val r = BinauralRenderer(loud, hrtf)
        val o = FloatArray(2 * n)
        var peak = 0f
        repeat(100) { r.render(out(cmds = listOf(cmd(80f, Band.STOP, 0.3f))), o); peak = maxOf(peak, o.maxOf { abs(it) }) }
        assertTrue(peak <= loud.audio.limiterCeiling + 1e-6f, "peak $peak")

        val a = BinauralRenderer(config, hrtf)
        a.render(out(alert = AlertKind.UNKNOWN), o)
        val l = FloatArray(n) { o[2 * it] }; val rr = FloatArray(n) { o[2 * it + 1] }
        assertTrue(l.any { it != 0f })
        assertArrayEquals(l, rr)
        // 알림음은 서로 다르다
        val s = Sounds(config.audio, config.policy)
        val sigs = AlertKind.entries.map { s.alert(it).size to s.alert(it).take(2000).sum() }
        assertEquals(AlertKind.entries.size, sigs.toSet().size)
    }

    @Test
    fun `expired commands are dropped and a removed source rings out without a click`() {
        val r = BinauralRenderer(config, hrtf)
        val o = FloatArray(2 * n)
        r.render(out(cmds = listOf(cmd(30f).copy(infoAgeMs = config.policy.maxInfoAgeMs + 1))), o)
        assertTrue(o.all { it == 0f })
        // 버스트 도중 명령이 사라져도 버스트는 포락선대로 끝난다: 끊김 지점의 2차 차분이 작다
        val all = ArrayList<Float>()
        repeat(3) { r.render(out(cmds = listOf(cmd(30f, Band.STOP, 0.5f))), o); all += o.toList() }
        repeat(10) { r.render(out(), o); all += o.toList() }
        val x = all.toFloatArray()
        val cut = 3 * 2 * n
        var jump = 0f
        for (i in cut / 2 - 2 until cut / 2 + 4) jump = maxOf(jump, abs(x[2 * i] - 2 * x[2 * (i - 1)] + x[2 * (i - 2)]))
        assertTrue(jump < 0.1f, "discontinuity at removal $jump")
        assertTrue((x.size / 2 - 50 until x.size / 2).all { abs(x[2 * it]) < 1e-6f }, "tail must decay to silence")
    }

    @Test
    fun `sweep WAV from -60 to +60 degrees for headphone check`() {
        // M6 완료 기준: 사용자가 헤드폰으로 방향 확인. core/build/test-output/sweep_minus60_plus60.wav
        val r = BinauralRenderer(config, hrtf)
        val seconds = 8f
        val blocks = (seconds * sr / n).toInt()
        val all = FloatArray(2 * n * blocks); val o = FloatArray(2 * n)
        for (b in 0 until blocks) {
            val az = -60f + 120f * b / (blocks - 1)
            val alert = if (b == 0) AlertKind.READY else null
            r.render(out(cmds = listOf(cmd(az, Band.WARN, 1.5f)), alert = alert), o)
            o.copyInto(all, 2 * n * b)
        }
        val dir = File(System.getProperty("hearspace.testOutput")).apply { mkdirs() }
        val f = File(dir, "sweep_minus60_plus60.wav")
        writeWav16(f, normalized(all), sr) // 청취 확인용: 최대 −1 dBFS로 정규화(앱에서는 기기 볼륨)
        assertTrue(f.length() > 44 + all.size * 2 - 10)
        // 방향 판단이 쉬운 연속 분홍 잡음 스윕(같은 경로의 공간화)
        val noise = pinkNoise(blocks * n)
        val cont = spatialize({ noise[it] }, blocks) { b -> -60f + 120f * b / (blocks - 1) }
        writeWav16(File(dir, "noise_sweep_minus60_plus60.wav"), normalized(cont), sr)
        // 앞쪽 절반은 왼쪽, 뒤쪽 절반은 오른쪽이 크다
        fun e(from: Int, to: Int, ch: Int) = (from until to).sumOf { (all[2 * it + ch] * all[2 * it + ch]).toDouble() }
        val half = all.size / 4
        assertTrue(e(0, half / 2, 0) > e(0, half / 2, 1))
        assertTrue(e(all.size / 2 - half / 2, all.size / 2, 1) > e(all.size / 2 - half / 2, all.size / 2, 0))
    }

    /** 실사용 생성기로 같은 네 장면을 기존/새 방식 모두 WAV로 만든다. */
    @Test
    fun riskContinuousWavs() {
        val dir = File(System.getProperty("hearspace.testOutput"), "risk-continuous").apply { mkdirs() }
        val seconds = 12f
        val blocks = kotlin.math.ceil(seconds * sr / n).toInt()
        val blockNs = n * 1_000_000_000L / sr
        val names = listOf("static_once", "slow_approach", "fast_approach", "two_objects_different_risk")
        fun scene(name: String, tS: Float): List<AudioCmd> {
            if (tS < 0.5f) return emptyList()
            val movingS = maxOf(0f, tS - 1.5f)
            fun point(id: Int, azDeg: Float, distanceM: Float, corridor: Boolean): AudioCmd {
                val band = when {
                    distanceM < config.policy.stopM -> Band.STOP
                    distanceM < config.policy.warnMaxM -> Band.WARN
                    else -> Band.SILENT
                }
                return AudioCmd(id, azDeg, distanceM, band, SoundKind.FLOOR_PULSE, 0f, corridor)
            }
            return when (name) {
                "static_once" -> listOf(point(1, -30f, 1.8f, false))
                "slow_approach" -> listOf(point(1, 0f, maxOf(0.7f, 2.5f - 0.2f * movingS), true))
                "fast_approach" -> listOf(point(1, 0f, maxOf(0.7f, 2.5f - 0.8f * movingS), true))
                else -> listOf(point(1, -15f, maxOf(0.8f, 2.4f - 0.22f * movingS), true),
                    point(2, 45f, 2.2f, false))
            }
        }
        fun render(name: String, mode: hearspace.core.types.SonifyMode): FloatArray {
            val cfg = config.copy(sonify = config.sonify.copy(mode = mode))
            val r = BinauralRenderer(cfg, hrtf)
            val all = FloatArray(2 * n * blocks)
            val o = FloatArray(2 * n)
            for (b in 0 until blocks) {
                r.render(GuidanceOutput(b * blockNs, GuidanceState.NORMAL,
                    scene(name, b * n.toFloat() / sr), 0f, null), o)
                o.copyInto(all, 2 * b * n)
            }
            val fadeFrames = sr / 10
            val frames = all.size / 2
            for (i in frames - fadeFrames until frames) {
                val gain = (frames - 1 - i).toFloat() / fadeFrames
                all[2 * i] *= gain; all[2 * i + 1] *= gain
            }
            assertTrue(all.all { it.isFinite() && abs(it) <= cfg.audio.limiterCeiling + 1e-6f })
            return all
        }
        for (name in names) {
            val baseline = render(name, hearspace.core.types.SonifyMode.PULSE)
            val risk = render(name, hearspace.core.types.SonifyMode.RISK_CONTINUOUS)
            // 파일별 정규화 없음. 거리/위험도에 따른 음량 차이를 보존한다.
            writeWav16(File(dir, "${name}_baseline.wav"), baseline, sr)
            writeWav16(File(dir, "$name.wav"), risk, sr)
            writeWav16(File(dir, "${name}_AB.wav"), baseline + FloatArray(2 * sr) + risk, sr)
            if (name == "static_once") {
                assertTrue((4 * sr * 2 until risk.size).all { risk[it] == 0f })
                assertTrue((4 * sr * 2 until 5 * sr * 2).any { abs(baseline[it]) > 1e-4f })
            }
        }
    }

    private fun normalized(x: FloatArray): FloatArray {
        val peak = x.maxOf { abs(it) }
        val g = if (peak > 0f) 0.89f / peak else 1f // −1 dBFS
        return FloatArray(x.size) { x[it] * g }
    }

    private fun writeWav16(f: File, stereo: FloatArray, rate: Int) {
        RandomAccessFile(f, "rw").use { w ->
            w.setLength(0)
            val data = stereo.size * 2
            fun le32(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = w.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            w.writeBytes("RIFF"); le32(36 + data); w.writeBytes("WAVEfmt "); le32(16); le16(1); le16(2); le32(rate); le32(rate * 4); le16(4); le16(16)
            w.writeBytes("data"); le32(data)
            val buf = ByteArray(data)
            for (i in stereo.indices) {
                val v = (stereo[i].coerceIn(-1f, 1f) * 32767).toInt()
                buf[2 * i] = v.toByte(); buf[2 * i + 1] = (v shr 8).toByte()
            }
            w.write(buf)
        }
    }
}

