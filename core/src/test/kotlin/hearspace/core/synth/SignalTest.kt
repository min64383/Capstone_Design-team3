package hearspace.core.synth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.SignalReplay
import hearspace.core.replay.SituationListener
import hearspace.core.session.RunLog
import hearspace.core.situation.ColorImage
import hearspace.core.situation.SignalOutput
import hearspace.core.situation.Signals
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.situation.Utterance
import hearspace.core.types.ConfigLoader
import hearspace.core.types.DepthFrame
import hearspace.core.types.Intrinsics
import hearspace.core.types.MapHealth
import hearspace.core.types.ObstacleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M71 합성 시험 SP-23~SP-28(설계 5.3절). 카메라는 높이 1.25 m에서 수평으로 −Z를 본다. 보행 신호는 [DIST_M] 앞 평면에 등틀(0.3 × 0.6 m, 어두움),
 * 위 칸 빨간 사람 꼴·아래 칸 초록 사람 꼴(0.10 × 0.20 m, 칸 간격 0.25 m = 높이 × 1.25)을 그린다. RGB는 10 Hz이고 처음 1 s는 멈춤 판정이 쌓이는 시간이다.
 */
class SignalTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val config = ConfigLoader.load(base, """{ "situation": { "enabled": true }, "signal": { "enabled": true } }""")
    private val k = Intrinsics(500f, 500f, 100f, 120f, 200, 240)
    private val camH = 1.25f
    private val frameNs = 100_000_000L
    private val t0 = 1_000_000_000L

    private fun pose(z: Float = 0f): Mat4 = Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, camH, z))

    /** 화소마다 [DIST_M] 앞 평면의 (x, y)를 [paint]로 칠한다(null = 배경 회색). */
    private fun image(tNs: Long, w: Mat4, paint: (Float, Float) -> Int?): ColorImage {
        val o = w.translation()
        val rgb = ByteArray(k.width * k.height * 3)
        for (i in 0 until k.width * k.height) {
            val d = w.transformDir(Vec3((i % k.width + 0.5f - k.cx) / k.fx, (i / k.width + 0.5f - k.cy) / k.fy, 1f))
            val c = if (d.z >= 0f) GRAY else ((-DIST_M - o.z) / d.z).let { s -> paint(o.x + d.x * s, o.y + d.y * s) ?: GRAY }
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
        }
        return ColorImage(tNs, k, w, rgb = rgb)
    }

    /** 보행 신호(등틀 가운데 높이 [cy]): 위 칸 빨강, 아래 칸 초록. */
    private fun signal(red: Boolean, green: Boolean, cy: Float = 3.0f): (Float, Float) -> Int? = { x, y ->
        val redY = cy + CELL_M / 2f
        val greenY = cy - CELL_M / 2f
        when {
            red && kotlin.math.abs(x) <= FIG_W / 2f && kotlin.math.abs(y - redY) <= FIG_H / 2f -> RED
            green && kotlin.math.abs(x) <= FIG_W / 2f && kotlin.math.abs(y - greenY) <= FIG_H / 2f -> GREEN
            kotlin.math.abs(x) <= 0.15f && kotlin.math.abs(y - cy) <= 0.30f -> DARK
            else -> null
        }
    }

    private fun depth(tNs: Long, w: Mat4) = DepthFrame(tNs, ShortArray(16), null, Intrinsics(4f, 4f, 2f, 2f, 4, 4), w, "synthetic")

    /**
     * [n]장: 장마다 [paint]로 그린 RGB를 신호 판단에 넣고, [stepAt]인 장에서 상황 판단(말하기 규칙)을 그 장 시각 + [lagNs]에 돌린다.
     * [z]는 카메라 위치(걷기). 말과 장마다의 신호 출력을 준다.
     */
    private fun run(
        n: Int,
        z: (Int) -> Float = { 0f },
        stepAt: (Int) -> Boolean = { true },
        lagNs: Long = 30_000_000L,
        paint: (Int) -> (Float, Float) -> Int?,
    ): Pair<List<Utterance>, List<SignalOutput>> {
        val sig = Signals(config.signal)
        val sit = Situations(config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong())
        val said = ArrayList<Utterance>()
        val outs = ArrayList<SignalOutput>()
        for (i in 0 until n) {
            val t = t0 + i * frameNs
            val w = pose(z(i))
            val o = sig.process(image(t, w, paint(i)))
            outs += o
            if (!stepAt(i)) continue
            said += sit.step(SituationInput(t + lagNs, depth(t, w), ObstacleSnapshot(t, emptyList(), 0f, MapHealth.OK), emptyList(), null, null, signals = o.speech)).first
        }
        return said to outs
    }

    private fun texts(u: List<Utterance>, text: String) = u.filter { it.text == text }

    @Test
    fun `SP-23 after 2 s of red a switch to green is spoken once within 3 s`() {
        // 0~1 s 멈춤 판정이 쌓임, 1~3 s 빨강(멈춘 채 2 s), 3 s부터 초록
        val (said, _) = run(60) { i -> signal(red = i < 30, green = i >= 30) }
        val green = texts(said, Signals.GREEN_TEXT)
        assertEquals(1, green.size, said.toString())
        val switchNs = t0 + 30 * frameNs
        assertTrue(green.single().tNs - switchNs in 0..3_000_000_000L, "spoken ${(green.single().tNs - switchNs) / 1e9} s after the switch")
        assertEquals(1, texts(said, Signals.RED_TEXT).size, said.toString())
    }

    @Test
    fun `SP-24 green flashing without red says nothing`() {
        // 초록 0.5 s 켜짐 · 0.5 s 꺼짐을 6 s
        val (said, _) = run(70) { i -> signal(red = false, green = (i / 5) % 2 == 0) }
        assertTrue(said.isEmpty(), said.toString())
    }

    @Test
    fun `SP-25 arriving on green gives no green speech, red for 1_2 s gives one red`() {
        val (said, _) = run(70) { i -> signal(red = i >= 35, green = i < 35) }
        assertTrue(texts(said, Signals.GREEN_TEXT).isEmpty(), said.toString())
        val red = texts(said, Signals.RED_TEXT)
        assertEquals(1, red.size, said.toString())
        assertTrue(red.single().tNs >= t0 + 35 * frameNs + 1_200_000_000L - frameNs, "red said ${(red.single().tNs - t0) / 1e9} s")
    }

    @Test
    fun `SP-26 a light below the horizon, a round lamp and a green figure without a dark cell above make no candidate`() {
        val low: (Float, Float) -> Int? = { x, y -> // 차 미등 높이(약 1 m)의 세로 긴 빨간 불빛과 아래 어두운 칸
            when {
                kotlin.math.abs(x) <= FIG_W / 2f && kotlin.math.abs(y - 1.0f) <= FIG_H / 2f -> RED
                kotlin.math.abs(x) <= 0.15f && y in 0.6f..0.9f -> DARK
                else -> null
            }
        }
        val round: (Float, Float) -> Int? = { x, y -> // 차량 신호: 가로 등틀의 둥근 빨간 등(지름 0.2 m), 아래는 등틀
            val dx = x - 0.8f
            val dy = y - 3.0f
            when {
                dx * dx + dy * dy <= 0.01f -> RED
                kotlin.math.abs(dx) <= 0.5f && kotlin.math.abs(dy) <= 0.3f -> DARK
                else -> null
            }
        }
        val bare: (Float, Float) -> Int? = { x, y -> // 등틀 없이 밝은 배경 위 초록 사람 꼴(위 칸이 어둡지 않음)
            if (kotlin.math.abs(x + 0.8f) <= FIG_W / 2f && kotlin.math.abs(y - 2.9f) <= FIG_H / 2f) GREEN else null
        }
        val all: (Float, Float) -> Int? = { x, y -> low(x, y) ?: round(x, y) ?: bare(x, y) }
        val (said, outs) = run(40) { all }
        assertTrue(said.isEmpty(), said.toString())
        val blobs = outs.flatMap { it.blobs }
        assertTrue(blobs.isNotEmpty(), "the round lamp and the bare figure must be seen as lit blobs")
        assertTrue(blobs.none { it.pass }, blobs.filter { it.pass }.toString())
        assertTrue(outs.all { it.tracks.isEmpty() })
    }

    @Test
    fun `SP-27 no speech when the switch reaches speech 4 s late or the user is walking`() {
        val late = run(80, stepAt = { i -> i >= 70 }) { i -> signal(red = i < 30, green = i >= 30) }.first
        assertTrue(texts(late, Signals.GREEN_TEXT).isEmpty(), late.toString())
        // 1 m/s로 걷는 중(앞으로 0.1 m/장)
        val walking = run(60, z = { i -> -0.1f * i }) { i -> signal(red = i < 30, green = i >= 30) }
        assertTrue(walking.first.isEmpty(), walking.first.toString())
        assertTrue(walking.second.none { it.still })
    }

    @Test
    fun `SP-28 evidence older than 500 ms is not spoken, and turning signals on keeps run logs and speech byte-identical`() {
        // 전환이 확인된 장(초록 3장째)의 출력을 600 ms 뒤에 받으면 근거 나이 초과
        val stale = run(33, stepAt = { i -> i == 32 }, lagNs = 600_000_000L) { i -> signal(red = i < 30, green = i >= 30) }.first
        assertTrue(stale.isEmpty(), stale.toString())
        val fresh = run(33, stepAt = { i -> i == 32 }, lagNs = 300_000_000L) { i -> signal(red = i < 30, green = i >= 30) }.first
        assertEquals(listOf(Signals.GREEN_TEXT), fresh.map { it.text })

        val root = File(System.getProperty("hearspace.testOutput"), "signal/sp28")
        val session = File(root, "session").also { it.deleteRecursively() }
        SyntheticSessionWriter.write(SceneSpec("SP28", Scene(Scenes.SC01.scene.items + Scenes.boxOnLine(4f)), Walk(durationS = 5.5f)).generate(), session, "SYN")
        val off = File(root, "off")
        val on = File(root, "on")
        OfflineReplay(ConfigLoader.load(base, """{ "situation": { "enabled": true } }"""), OfflineReplay.fixed(10f)).run(session, off)
        OfflineReplay(config, OfflineReplay.fixed(10f)).run(session, on)
        for (f in listOf(RunLog.SLOW_PATH_FILE, RunLog.GUIDANCE_FILE, RunLog.OBSTACLES_FILE, RunLog.CLUSTER_DEBUG_FILE, SituationListener.SPEECH_FILE, SituationListener.STATE_FILE)) {
            assertEquals(File(off, f).readText(), File(on, f).readText(), f)
        }
        assertTrue(File(on, SignalReplay.LOG_FILE).isFile)
        assertFalse(File(off, SignalReplay.LOG_FILE).exists())
    }

    @Test
    fun `app NV21 input lights the same pixels as the full RGB conversion`() {
        val rnd = java.util.Random(71)
        val w = k.width
        val h = k.height
        val fromRgb = Signals(config.signal)
        val fromNv21 = Signals(config.signal)
        val rgb = ByteArray(w * h * 3)
        var blobs = 0
        for (i in 0 until 15) { // 1 s 넘게 같은 자리(멈춤)여야 덩어리를 찾는다
            // 홀수 장은 색차를 거름 하한 둘레(±60)에 몰아 거름 경계를 시험한다
            val nv21 = ByteArray(w * h * 3 / 2).also { rnd.nextBytes(it); if (i % 2 == 1) for (j in w * h until it.size) it[j] = (128 + rnd.nextInt(121) - 60).toByte() }
            hearspace.core.situation.SurfaceIO.nv21ToRgb(nv21, w, h, rgb)
            val t = t0 + i * frameNs
            // 꺼진 칸 밝기는 원천이 다르다(RGB에서 계산 대 Y 평면): 켜진 화소로 만든 덩어리의 모양만 견준다
            fun shape(o: SignalOutput) = o.blobs.map { listOf(it.red, it.nPx, it.azDeg, it.elDeg, it.heightPx, it.widthPx) }
            val a = shape(fromRgb.process(ColorImage(t, k, pose(), rgb = rgb.copyOf())))
            val b = shape(fromNv21.process(ColorImage(t, k, pose(), nv21 = nv21)))
            assertEquals(a, b)
            blobs += a.size
        }
        assertTrue(blobs > 0, "blobs $blobs")
    }

    private companion object {
        const val DIST_M = 10f
        const val FIG_W = 0.10f
        const val FIG_H = 0.20f
        const val CELL_M = 0.25f
        const val GRAY = 0xB4B4B4
        const val DARK = 0x282828
        const val RED = 0xFF1E1E
        const val GREEN = 0x1EF0B4
    }
}
