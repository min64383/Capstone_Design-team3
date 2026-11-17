package hearspace.core.synth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.SituationListener
import hearspace.core.session.RunLog
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.situation.Speech
import hearspace.core.situation.SpeechRequest
import hearspace.core.situation.SurfaceClass
import hearspace.core.situation.Utterance
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import hearspace.core.types.DepthFrame
import hearspace.core.types.HeightClass
import hearspace.core.types.Intrinsics
import hearspace.core.types.LabelImage
import hearspace.core.types.MapHealth
import hearspace.core.types.Obstacle
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.SemanticLabel
import hearspace.core.types.SoundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * M50 합성 시험 SP-1~SP-9(설계 표 11)와 첫 평가 뒤 고침의 SP-10~SP-12. 카메라는 높이 1.3 m, 아래로 9° 숙인 세로 영상, 진행 방향 −Z. 노면 영상과 깊이 장은 월드 장면에서 직접 그린다.
 */
class SituationTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val config = ConfigLoader.load(base, """{ "situation": { "enabled": true } }""")
    private val heading = Vec3(0f, 0f, -1f)
    private val kS = Intrinsics(250f, 250f, 120f, 160f, 240, 320)
    private val kD = Intrinsics(125f, 125f, 45f, 80f, 90, 160)
    private val camH = 1.3f
    private val stepNs = 100_000_000L

    private fun pose(x: Float, z: Float = 0f, pitchDeg: Float = 9f): Mat4 {
        val p = Math.toRadians(pitchDeg.toDouble())
        val s = sin(p).toFloat()
        val c = cos(p).toFloat()
        return Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -c, s), Vec3(0f, -s, -c), Vec3(x, camH, z))
    }

    /** 노면 영상: 화소 광선 ∩ 바닥(y = 0)의 월드 (x, z)를 [cls]로 칠한다(255 = 계산 안 함). */
    private fun surface(tNs: Long, w: Mat4, cls: (Float, Float) -> Int): LabelImage {
        val o = w.translation()
        val px = ByteArray(kS.width * kS.height) { i ->
            val u = i % kS.width
            val v = i / kS.width
            val d = w.transformDir(Vec3((u + 0.5f - kS.cx) / kS.fx, (v + 0.5f - kS.cy) / kS.fy, 1f))
            if (d.y >= -1e-4f) 255.toByte() else {
                val t = -o.y / d.y
                cls(o.x + d.x * t, o.z + d.z * t).toByte()
            }
        }
        return LabelImage(tNs, px, kS, w)
    }

    /** 깊이 장: 앞 거리(−z) [edgeM]부터 바닥이 [dropM] 낮다(null이면 평평). [ceilingM]이 있으면 그 높이에 천장. */
    private fun depth(tNs: Long, w: Mat4, edgeM: Float? = null, dropM: Float = 0f, labels: LabelImage? = null, ceilingM: Float? = null): DepthFrame {
        val o = w.translation()
        val inv = w.rigidInverse()
        val mm = ShortArray(kD.width * kD.height) { i ->
            val u = i % kD.width
            val v = i / kD.width
            val d = w.transformDir(Vec3((u + 0.5f - kD.cx) / kD.fx, (v + 0.5f - kD.cy) / kD.fy, 1f))
            if (d.y >= -1e-4f) {
                if (ceilingM == null || d.y <= 1e-4f) return@ShortArray 0
                val z = inv.transformPoint(o + d * ((ceilingM - o.y) / d.y)).z
                return@ShortArray if (z > 30f) 0 else (z * 1000f).toInt().toShort()
            }
            var p = o + d * (-o.y / d.y)
            if (edgeM != null && -p.z >= edgeM) p = o + d * ((-dropM - o.y) / d.y)
            val z = inv.transformPoint(p).z
            if (z > 30f) 0 else (z * 1000f).toInt().toShort()
        }
        return DepthFrame(tNs, mm, null, kD, w, "synthetic", labels = labels)
    }

    private fun obstacle(id: Int, rep: Vec3, half: Vec3 = Vec3(0.2f, 0.4f, 0.2f), vel: Vec3? = null, moving: Boolean = false) = Obstacle(
        id, rep, emptyMap(), rep - half, rep + half, HeightClass.BODY, true, 1f, 0L, 10, velocityW = vel, moving = moving,
    )

    private fun cmd(o: Obstacle, userZ: Float = 0f) = AudioCmd(o.id, 0f, -(o.repPointW.z - userZ), Band.WARN, SoundKind.FLOOR_PULSE, 50f)

    /** [n]차례 돌린다(깊이 10 Hz, 노면 5 Hz). [scene]은 차례 번호마다 (자세, 노면 칠하기, 깊이, 장애물). */
    private fun run(
        n: Int,
        userX: (Int) -> Float = { 0f },
        userZ: (Int) -> Float = { 0f },
        cls: (Float, Float) -> Int,
        depthOf: (Long, Mat4) -> DepthFrame = { t, w -> depth(t, w) },
        obstacles: List<Obstacle> = emptyList(),
        sc: hearspace.core.types.SituationConfig = config.situation,
    ): List<Utterance> {
        val sit = Situations(sc, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, 500_000_000L)
        val out = ArrayList<Utterance>()
        var surf: LabelImage? = null
        for (i in 0 until n) {
            val t = 1_000_000_000L + i * stepNs
            val w = pose(userX(i), userZ(i))
            if (i % 2 == 0) surf = surface(t, w, cls)
            val snap = ObstacleSnapshot(t, obstacles, 0f, MapHealth.OK)
            val (u, _) = sit.step(SituationInput(t + 30_000_000L, depthOf(t, w), snap, obstacles.map { cmd(it) }, heading, surf))
            out += u
        }
        return out
    }

    private val S = SurfaceClass.SIDEWALK.pixel
    private val B = SurfaceClass.BLOCK.pixel

    /** 블록 줄: x ∈ [−0.15, 0.15], 앞 거리 ∈ [from, to], 그 밖은 인도. */
    private fun blockLine(from: Float = 0.5f, to: Float = 3.5f): (Float, Float) -> Int = { x, z -> if (x in -0.15f..0.15f && -z in from..to) B else S }

    @Test
    fun `SP-1 situation on and off give byte-identical run logs`() {
        val root = File(System.getProperty("hearspace.testOutput"), "situation/sp1")
        val session = File(root, "session").also { it.deleteRecursively() }
        SyntheticSessionWriter.write(SceneSpec("SP1", Scene(Scenes.SC01.scene.items + Scenes.boxOnLine(4f)), Walk(durationS = 5.5f)).generate(), session, "SYN")
        val off = File(root, "off")
        val on = File(root, "on")
        OfflineReplay(ConfigLoader.load(base), OfflineReplay.fixed(10f)).run(session, off)
        OfflineReplay(config, OfflineReplay.fixed(10f)).run(session, on)
        for (f in listOf(RunLog.SLOW_PATH_FILE, RunLog.GUIDANCE_FILE, RunLog.OBSTACLES_FILE, RunLog.CLUSTER_DEBUG_FILE)) {
            assertEquals(File(off, f).readText(), File(on, f).readText(), f)
        }
        assertTrue(File(on, SituationListener.SPEECH_FILE).isFile)
        assertFalse(File(off, SituationListener.SPEECH_FILE).exists())
    }

    @Test
    fun `SP-2 a block end seen for 10 s is spoken once`() {
        val said = run(100, cls = blockLine())
        assertEquals(1, said.count { it.text.startsWith("블록 끝") }, said.toString())
    }

    @Test
    fun `SP-3 an approaching vehicle interrupts info speech`() {
        val sp = Speech(config.situation, 500_000_000L)
        val t0 = 1_000_000_000L
        assertEquals(1, sp.tick(t0, listOf(SpeechRequest("env:SIDEWALK", "점자블록 인도", 3, t0))).size)
        val u = sp.tick(t0 + 300_000_000L, listOf(SpeechRequest("veh#1", "차 다가옴", 1, t0 + 250_000_000L)))
        assertEquals(listOf("차 다가옴"), u.map { it.text })
        assertTrue(u.single().interrupted)
    }

    @Test
    fun `SP-4 evidence older than the info age is not spoken`() {
        val sp = Speech(config.situation, 500_000_000L)
        assertTrue(sp.tick(1_600_000_000L, listOf(SpeechRequest("veh#1", "차 다가옴", 1, 1_000_000_000L))).isEmpty())
    }

    @Test
    fun `SP-5 no block end when the ground beyond is unobserved`() {
        val said = run(100, cls = { x, z -> if (-z > 3.5f) 255 else blockLine()(x, z) })
        assertEquals(0, said.count { it.text.startsWith("블록 끝") }, said.toString())
    }

    @Test
    fun `SP-6 only the obstacle within the block band is on the block`() {
        val near = obstacle(1, Vec3(0.4f, 0.4f, -3f))
        val far = obstacle(2, Vec3(-0.8f, 0.4f, -3f))
        val said = run(100, cls = blockLine(to = 6f), obstacles = listOf(near, far))
        assertEquals(listOf("obj#1"), said.filter { it.key.startsWith("obj#") }.map { it.key }.distinct(), said.toString())
    }

    @Test
    fun `SP-7 stairs direction needs positive evidence`() {
        val stairs: (Float, Float) -> Int = { x, z -> if (-z >= 2.0f && x in -0.6f..0.6f) SurfaceClass.STAIRS.pixel else S }
        val up = run(40, cls = stairs, obstacles = listOf(obstacle(5, Vec3(0f, 0.3f, -2.5f), half = Vec3(0.6f, 0.3f, 0.5f))))
        assertTrue(up.any { it.text == "올라가는 계단" } && up.none { it.text.startsWith("내려가는") }, up.toString())
        val down = run(40, cls = stairs, depthOf = { t, w -> depth(t, w, edgeM = 2.0f, dropM = 0.25f) })
        assertTrue(down.any { it.text.startsWith("내려가는 계단") }, down.toString())
        val flat = run(40, cls = stairs)
        assertTrue(flat.any { it.text.startsWith("계단,") } && flat.none { it.text.startsWith("내려가는") }, flat.toString())
    }

    @Test
    fun `SP-8 standing and crossing vehicles are not approaching`() {
        val labels = LabelImage(0L, ByteArray(kS.width * kS.height) { SemanticLabel.VEHICLE.pixel.toByte() }, kS, pose(0f))
        fun said(o: Obstacle) = run(30, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, labels = labels) }, obstacles = listOf(o))
        assertTrue(said(obstacle(7, Vec3(0f, 0.6f, -4f))).none { it.text == "차 다가옴" })
        assertTrue(said(obstacle(8, Vec3(0f, 0.6f, -4f), vel = Vec3(1.5f, 0f, 0f), moving = true)).none { it.text == "차 다가옴" })
        assertTrue(said(obstacle(9, Vec3(0f, 0.6f, -4f), vel = Vec3(0f, 0f, 1.5f), moving = true)).any { it.text == "차 다가옴" })
    }

    @Test
    fun `SP-9 drift speaks once past the on threshold and releases inside the off threshold`() {
        // 블록은 x = 0, 사용자가 왼쪽으로 옮겨 가 블록이 오른쪽에 남는다: 횡 거리 0.5 → 0.7 → 0.5 → 0.2 → 0.7 m, 각 2 s
        val xs = listOf(-0.5f, -0.7f, -0.5f, -0.2f, -0.7f)
        val said = run(100, userX = { xs[it / 20] }, cls = blockLine(to = 8f), sc = config.situation.copy(infoSpeech = false))
        val drift = said.filter { it.key.startsWith("drift#") }
        assertEquals(listOf("블록 오른쪽", "블록 오른쪽"), drift.map { it.text }, said.toString())
        assertTrue(drift[0].tNs in 1_000_000_000L + 30 * stepNs..1_000_000_000L + 40 * stepNs + 30_000_000L, drift.toString())
        assertTrue(drift[1].tNs >= 1_000_000_000L + 80 * stepNs)
    }

    @Test
    fun `SP-10 with drop speech on, a drop deeper than the threshold is spoken with its distance, a curb is not`() {
        val on = config.situation.copy(dropEnabled = true)
        val deep = run(40, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, edgeM = 2.0f, dropM = 1.0f) }, sc = on)
        assertTrue(deep.any { it.text == "내려가는 곳, 2미터" && it.priority == 1 }, deep.toString())
        val curb = run(40, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, edgeM = 2.0f, dropM = 0.25f) }, sc = on)
        assertTrue(run(40, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, edgeM = 2.0f, dropM = 1.0f) }).none { it.text.startsWith("내려가는") }, "default off")
        assertTrue(curb.none { it.text.startsWith("내려가는") }, curb.toString())
    }

    @Test
    fun `SP-11 a ceiling overhead keeps the environment unknown`() {
        val envOn = config.situation.copy(envSpeech = true)
        val open = run(60, cls = { _, _ -> S }, sc = envOn)
        assertTrue(open.any { it.text == "인도" }, open.toString())
        assertTrue(run(60, cls = { _, _ -> S }).none { it.key.startsWith("env:") }, "environment speech default off")
        val indoor = run(60, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, ceilingM = 2.6f) }, sc = envOn)
        assertTrue(indoor.none { it.key.startsWith("env:") }, indoor.toString())
    }

    @Test
    fun `SP-12 one long speed bump area is spoken once while walking along it`() {
        val bump: (Float, Float) -> Int = { _, z -> if (-z in 2f..30f) SurfaceClass.BUMP.pixel else S }
        val said = run(100, userZ = { -0.12f * it }, cls = bump, sc = config.situation.copy(bumpSpeech = true))
        assertEquals(1, said.count { it.text == "방지턱" }, said.toString())
        assertTrue(run(100, userZ = { -0.12f * it }, cls = bump).none { it.text == "방지턱" }, "bump speech default off")
    }
}
