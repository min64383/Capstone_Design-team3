package hearspace.core.synth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.SituationListener
import hearspace.core.session.RunLog
import hearspace.core.situation.SituationInput
import hearspace.core.situation.Situations
import hearspace.core.situation.Speech
import hearspace.core.situation.SpeechRank
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
    fun `SP-13 a situation back after the repeat time is spoken once free even if blocked on its first tick`() {
        val sp = Speech(config.situation, 500_000_000L)
        val s = 1_000_000_000L
        fun end(t: Long) = SpeechRequest("end", "블록 끝, 3미터", 2, t)
        assertEquals(1, sp.tick(1 * s, listOf(end(1 * s))).size)
        assertTrue(sp.tick(2 * s, emptyList()).isEmpty())
        // 10 s 뒤 다시 와서 이어지면 되풀이 시간 안이라 말하지 않는다
        assertTrue(sp.tick(11 * s, listOf(end(11 * s))).isEmpty())
        assertTrue(sp.tick(42 * s, listOf(end(42 * s))).isEmpty())
        assertTrue(sp.tick(43 * s, emptyList()).isEmpty())
        // 마지막 말에서 30 s 넘게 지나 다시 온 첫 차례에 차 다가옴이 먼저 나가도, 그 말이 끝나면 블록 끝을 말한다
        val t = 74 * s
        assertEquals(listOf("차 다가옴"), sp.tick(t, listOf(SpeechRequest("veh#1", "차 다가옴", 1, t), end(t))).map { it.text })
        assertEquals(listOf("블록 끝, 3미터"), sp.tick(t + s, listOf(end(t + s))).map { it.text })
    }

    @Test
    fun `SP-14 with the app speaking level 2 and 3 wait for the real end, 1 interrupts, and an unsounded line is spoken again`() {
        val sp = Speech(config.situation, 500_000_000L)
        val s = 1_000_000_000L
        fun end(t: Long) = SpeechRequest("end", "블록 끝, 3미터", 2, t)
        fun turn(t: Long) = SpeechRequest("turn", "블록 갈림", 2, t)
        fun veh(id: Int, t: Long) = SpeechRequest("veh#$id", "차 다가옴", 1, t)
        assertEquals(listOf("end"), sp.tick(1 * s, listOf(end(1 * s)), 0).map { it.key })
        // 음절 어림(초당 6음절, 약 1 s)이 지나도 앱이 아직 말하면 다른 2순위는 기다린다
        assertTrue(sp.tick(3 * s, listOf(turn(3 * s)), 2).isEmpty())
        // 1순위는 2순위를 끊고, 1순위를 말하는 중에는 다른 1순위도 기다린다
        assertEquals(listOf(true), sp.tick(3 * s + s / 10, listOf(veh(1, 3 * s), turn(3 * s)), 2).map { it.interrupted })
        assertTrue(sp.tick(3 * s + s / 5, listOf(veh(2, 3 * s + s / 5), turn(3 * s + s / 5)), 1).isEmpty())
        // 앱이 쉬게 된 차례부터 간격 2 s를 센다
        assertEquals(listOf("veh#2"), sp.tick(6 * s, listOf(veh(2, 6 * s), turn(6 * s)), 0).map { it.key })
        assertTrue(sp.tick(7 * s, listOf(turn(7 * s)), 0).isEmpty())
        assertTrue(sp.tick(7 * s + 9 * s / 10, listOf(turn(7 * s + 9 * s / 10)), 0).isEmpty())
        val t = 8 * s + s / 2
        assertEquals(listOf("turn"), sp.tick(t, listOf(turn(t)), 0).map { it.key })
        // 앱이 소리를 내지 못하면(시작 전 나이 초과) 되돌리고, 이어지는 상황은 간격 없이 새 근거로 다시 낸다
        sp.unsay("turn")
        assertEquals(listOf("turn"), sp.tick(t + s / 20, listOf(turn(t + s / 20)), 0).map { it.key })
        // 앱 값이 없으면(PC 재생) 음절 어림 그대로
        val pc = Speech(config.situation, 500_000_000L)
        assertEquals(1, pc.tick(1 * s, listOf(end(1 * s))).size)
        assertTrue(pc.tick(1 * s + s / 2, listOf(turn(1 * s + s / 2))).isEmpty())
    }

    @Test
    fun `SP-16 rank table keeps danger lines from being cut and lets user action answers cut guidance`() {
        val sit = SpeechRank.Kind.SITUATION
        val safety = SpeechRank.Kind.STATUS_SAFETY
        val action = SpeechRank.Kind.STATUS_ACTION
        assertEquals(1, SpeechRank.rank(safety))
        assertEquals(2, SpeechRank.rank(action))
        assertEquals(3, SpeechRank.rank(sit, 3))
        // 쉬면 무엇이든 받는다
        assertTrue(SpeechRank.admits(0, sit, 3))
        // 위험 말(1)을 말하는 중에는 상태 말도 다른 위험 말도 받지 않는다
        assertFalse(SpeechRank.admits(1, action, 2))
        assertFalse(SpeechRank.admits(1, safety, 1))
        assertFalse(SpeechRank.admits(1, sit, 1))
        // 1은 2·3을 끊는다
        assertTrue(SpeechRank.admits(2, safety, 1))
        assertTrue(SpeechRank.admits(3, sit, 1))
        // 조작 답은 2·3을 끊고, 길 안내·정보 말은 아무것도 끊지 않는다
        assertTrue(SpeechRank.admits(2, action, 2))
        assertTrue(SpeechRank.admits(3, action, 2))
        assertFalse(SpeechRank.admits(3, sit, 2))
        assertFalse(SpeechRank.admits(2, sit, 2))
    }

    @Test
    fun `SP-17 situation steps on a new surface image at once and otherwise every step interval of depth time`() {
        val sc = config.situation.copy(stepIntervalMs = 100)
        val sit = Situations(sc, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, 500_000_000L)
        val ms = 1_000_000L
        val stepped = ArrayList<Long>()
        for (i in 0 until 30) { // 깊이 장 33 ms마다, 노면 영상 200 ms마다
            val t = i * 33 * ms
            val surfT = (t / (200 * ms)) * 200 * ms
            if (sit.due(t, surfT)) {
                stepped.add(t / ms)
                sit.step(SituationInput(t, depth(t, pose(0f)), ObstacleSnapshot(t, emptyList(), -1.3f, MapHealth.OK), emptyList(), null, surface(surfT, pose(0f)) { _, _ -> S }))
            }
        }
        // 새 노면 영상이 처음 보이는 깊이 장(231·429·627·825 ms)에서는 바로, 그 사이에는 지난 판단에서 100 ms가 지난 깊이 장(132·363·…)에서: 약 10 Hz
        assertEquals(listOf(0L, 132L, 231L, 363L, 429L, 561L, 627L, 759L, 825L, 957L), stepped)
        val every = config.situation.copy(stepIntervalMs = 0)
        val all = Situations(every, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, 500_000_000L)
        assertTrue((0 until 30).all { all.due(it * 33 * ms, null) })
    }

    @Test
    fun `SP-18 incremental grid windows equal a fresh recount`() {
        val inc = hearspace.core.situation.SurfaceGrid(config.situation)
        val ms = 1_000_000L
        val t0 = 1_000_000_000L
        val imgs = (0 until 30).map { i -> surface(t0 + i * 200 * ms, pose(0.05f * (i % 3), -0.1f * i), blockLine(to = 6f)) }
        val added = ArrayList<LabelImage>()
        for (j in 0 until 60) {
            val now = t0 + j * 100 * ms
            for (img in imgs) if (img.tCaptureNs <= now && img !in added) { inc.add(img, 0f); added.add(img) }
            for (w in listOf(0.5f, 1.2f)) {
                val a = inc.cells(now, w)
                val fresh = hearspace.core.situation.SurfaceGrid(config.situation)
                for (img in added) fresh.add(img, 0f)
                val b = fresh.cells(now, w)
                assertEquals(b.size, a.size, "now $j window $w")
                for (c in b.values) assertTrue(a[fresh.keyAt(c.centerW)]!!.frames.contentEquals(c.frames), "now $j window $w cell ${c.ix},${c.iz}")
            }
        }
    }

    @Test
    fun `SP-19 map-based speech stops when the last surface image is older than surfaceMaxAgeMs`() {
        val t0 = 1_000_000_000L
        val all = run(100, cls = blockLine())
        assertTrue(all.isNotEmpty(), "control run must speak")
        // 노면 영상을 첫 차례에만 주면, 그 영상 + 1 s 뒤로는 노면 지도에 기대는 말이 없다
        val stale = runSurfaceOnce(100, cls = blockLine())
        val limit = t0 + config.situation.surfaceMaxAgeMs * 1_000_000L + 30_000_000L
        assertTrue(stale.all { it.tNs <= limit }, stale.toString())
    }

    /** [run]과 같지만 노면 영상은 첫 차례에만 준다. */
    private fun runSurfaceOnce(n: Int, cls: (Float, Float) -> Int): List<Utterance> {
        val sit = Situations(config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, 500_000_000L)
        val out = ArrayList<Utterance>()
        val first = surface(1_000_000_000L, pose(0f, 0f), cls)
        for (i in 0 until n) {
            val t = 1_000_000_000L + i * stepNs
            val w = pose(0f, 0f)
            val (u, _) = sit.step(SituationInput(t + 30_000_000L, depth(t, w), ObstacleSnapshot(t, emptyList(), 0f, MapHealth.OK), emptyList(), heading, first))
            out += u
        }
        return out
    }

    @Test
    fun `SP-22 an approaching person is spoken, a crossing or standing person is not`() {
        val labels = LabelImage(0L, ByteArray(kS.width * kS.height) { SemanticLabel.PERSON.pixel.toByte() }, kS, pose(0f))
        fun said(o: Obstacle) = run(30, cls = { _, _ -> S }, depthOf = { t, w -> depth(t, w, labels = labels) }, obstacles = listOf(o))
        assertTrue(said(obstacle(7, Vec3(0f, 0.6f, -4f))).none { it.text == "사람 다가옴" })
        assertTrue(said(obstacle(8, Vec3(0f, 0.6f, -4f), vel = Vec3(1.2f, 0f, 0f), moving = true)).none { it.text == "사람 다가옴" })
        val approach = said(obstacle(9, Vec3(0f, 0.6f, -4f), vel = Vec3(0f, 0f, 1.2f), moving = true))
        assertEquals(1, approach.count { it.text == "사람 다가옴" }, approach.toString())
        assertTrue(approach.none { it.text == "차 다가옴" })
    }

    @Test
    fun `SP-20 a wide dot pad where the line starts is not a branch, a real crossing line is`() {
        // 선이 2.6 m 앞에서 시작하고, 시작점에 폭 1.4 m·깊이 0.6 m 점형블록 판(노면 모델은 같은 블록으로 칠함, T01 160 s)
        val pad: (Float, Float) -> Int = { x, z -> if ((x in -0.15f..0.15f && -z in 2.6f..6f) || (x in -0.7f..0.7f && -z in 2.0f..2.6f)) B else S }
        val padSaid = run(30, cls = pad)
        assertEquals(0, padSaid.count { it.text.contains("갈림") || it.text.contains("꺾임") }, padSaid.toString())
        // 걸어가다 만나는(선을 잡은 자리에서 7 m 넘게 떨어진) 양옆 2 m씩 가로지르는 선은 갈림
        val cross: (Float, Float) -> Int = { x, z -> if ((x in -0.15f..0.15f && -z in 0.5f..13f) || (x in -2f..2f && -z in 9.6f..9.9f)) B else S }
        val crossSaid = run(80, userZ = { i -> -0.1f * i }, cls = cross)
        assertEquals(1, crossSaid.count { it.text == "블록 갈림" }, crossSaid.toString())
    }

    @Test
    fun `SP-21 the line-start cross bar is not a branch, later branches 10 m apart are both spoken once`() {
        // 선을 잡을 때 3 m 앞 가로줄(ㅗ 시작 구조, M51 사용자) → 말하지 않음. 13 m·23 m 앞 갈림은 1 m/s로 걸으며 둘 다(옛 규칙은 키 하나라 둘째를 막았다)
        val three: (Float, Float) -> Int = { x, z ->
            if ((x in -0.15f..0.15f && -z in 0.5f..26f) || (x in -2f..2f && (-z in 3f..3.3f || -z in 13f..13.3f || -z in 23f..23.3f))) B else S
        }
        val said = run(230, userZ = { i -> -0.1f * i }, cls = three)
        assertEquals(2, said.count { it.text == "블록 갈림" }, said.toString())
    }

    // ---- M69 팔 세기(ARMS): 1 m/s로 걷고, 선을 잡은 자리(M51 선 시작 규칙 7 m)보다 먼 10 m에 자리를 둔다 ----
    private val arms = config.situation.copy(blockMethod = hearspace.core.types.BlockMethod.ARMS, gridRecentK = 3)

    private fun blockSaid(said: List<Utterance>) = said.filter { it.text.contains("갈림") || it.text.contains("꺾임") || it.text.startsWith("블록 끝") }

    @Test
    fun `SP-29 arms says a straight line nothing and a crossing line a branch once`() {
        val straight: (Float, Float) -> Int = { x, z -> if (x in -0.15f..0.15f && -z in 0.5f..16f) B else S }
        val s = run(120, userZ = { i -> -0.1f * i }, cls = straight, sc = arms)
        assertTrue(blockSaid(s).isEmpty(), s.toString())
        val cross: (Float, Float) -> Int = { x, z -> if ((x in -0.15f..0.15f && -z in 0.5f..14f) || (x in -2f..2f && -z in 9.6f..9.9f)) B else S }
        val c = run(95, userZ = { i -> -0.1f * i }, cls = cross, sc = arms)
        assertEquals(1, c.count { it.text == "블록 갈림" }, c.toString())
        assertTrue(c.none { it.text.contains("꺾임") }, c.toString())
    }

    @Test
    fun `SP-30 arms says a right turn with a corner pad as a turn, not a branch`() {
        // 선 0.5~10 m, 모서리 점형 판 0.6 m(x −0.15~0.45), 오른쪽 선(x 0.45~4)
        val turn: (Float, Float) -> Int = { x, z ->
            if ((x in -0.15f..0.15f && -z in 0.5f..10f) || (x in -0.15f..0.45f && -z in 10f..10.6f) || (x in 0.45f..4f && -z in 10.15f..10.45f)) B else S
        }
        val s = run(95, userZ = { i -> -0.1f * i }, cls = turn, sc = arms)
        assertEquals(1, s.count { it.text == "블록 오른쪽으로 꺾임" }, s.toString())
        assertTrue(s.none { it.text.contains("갈림") || it.text.contains("왼쪽") }, s.toString())
    }

    private val rowBeforeEdge: (Float, Float) -> Int = { x, z -> if ((x in -0.15f..0.15f && -z in 0.5f..10f) || (x in -2f..2f && -z in 10f..10.6f)) B else S }

    @Test
    fun `SP-31 a stop row before a 1 m drop is down stairs, not a branch`() {
        val s = run(95, userZ = { i -> -0.1f * i }, cls = rowBeforeEdge, depthOf = { t, w -> depth(t, w, edgeM = 10.9f, dropM = 1.0f) }, sc = arms)
        assertTrue(s.any { it.text.startsWith("내려가는 계단") }, s.toString())
        assertTrue(s.none { it.text.contains("갈림") || it.text.contains("꺾임") || it.text.startsWith("올라가는") }, s.toString())
    }

    @Test
    fun `SP-32 a stop row before a 0_5 m rise is up stairs, never down`() {
        val s = run(95, userZ = { i -> -0.1f * i }, cls = rowBeforeEdge, depthOf = { t, w -> depth(t, w, edgeM = 10.9f, dropM = -0.5f) }, sc = arms)
        assertTrue(s.any { it.text == "올라가는 계단" }, s.toString())
        assertTrue(s.none { it.text.contains("갈림") || it.text.contains("꺾임") || it.text.startsWith("내려가는") }, s.toString())
    }

    @Test
    fun `SP-33 a stop row on flat ground is no stairs and a row reached across the sidewalk is no branch`() {
        // 블록 없이 인도를 걷다가 9 m 앞 가로줄에서 선이 시작(ㅗ 시작 구조, 154154처럼 뒤 팔 없음), 평평함
        val start: (Float, Float) -> Int = { x, z -> if ((x in -2f..2f && -z in 9f..9.6f) || (x in -0.15f..0.15f && -z in 9.6f..16f)) B else S }
        val s = run(110, userZ = { i -> -0.1f * i }, cls = start, sc = arms)
        assertTrue(s.none { it.text.contains("계단") || it.text.contains("갈림") || it.text.contains("꺾임") }, s.toString())
    }

    @Test
    fun `SP-34 arms is off by default and the line method stays the default`() {
        assertEquals(hearspace.core.types.BlockMethod.LINES, config.situation.blockMethod)
        assertEquals(0, config.situation.gridRecentK)
    }

    @Test
    fun `SP-5 no block end when the ground beyond is unobserved`() {
        val said = run(100, cls = { x, z -> if (-z > 3.5f) 255 else blockLine()(x, z) })
        assertEquals(0, said.count { it.text.startsWith("블록 끝") }, said.toString())
    }

    @Test
    fun `SP-6 only an obstacle overlapping the block strip is on the block, one beside it is not`() {
        // 블록 띠 x ∈ [−0.15, 0.15]. 상자 반폭 0.2: on은 [−0.1, 0.3]으로 띠와 겹침, beside는 [0.25, 0.65]로 띠 옆(M51 정의), far는 멀리
        val on = obstacle(1, Vec3(0.1f, 0.4f, -3f))
        val beside = obstacle(2, Vec3(0.45f, 0.4f, -3.5f))
        val far = obstacle(3, Vec3(-0.8f, 0.4f, -3f))
        val said = run(100, cls = blockLine(to = 6f), obstacles = listOf(on, beside, far))
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

    @Test
    fun `블록 선 맞추기는 주축이 없는 점 묶음에서 방향 없는 선을 내지 않는다`() {
        // M69 반복 일치에서 처리 시간을 흔들자 상황 판단이 예외로 멈췄다: 안쪽 점이 어느 방향으로도 길지 않게 고르게 퍼지면
        // (작은 점형 블록 조각 등) 공분산에 주축이 없어 방향이 0/0 = NaN이 되고, NaN 길이는 최소 길이 검사를 지나 각도 계산에서 예외
        // 정확히 표현되는 값의 십자: 공분산이 정확히 등방(sxx = szz, sxz = 0)이라 주축이 없다. 허용 폭 0.65 m라 다섯 점이 모두 안쪽
        val cross = listOf(Vec3(0f, 0f, 0f), Vec3(0.5f, 0f, 0f), Vec3(-0.5f, 0f, 0f), Vec3(0f, 0f, 0.5f), Vec3(0f, 0f, -0.5f))
        val lines = hearspace.core.situation.BlockLines.fit(cross, 0.6f, 1.0f, 0.1f)
        assertTrue(lines.all { it.dir.x.isFinite() && it.dir.z.isFinite() && it.lengthM.isFinite() }, lines.toString())
        assertTrue(lines.isEmpty(), lines.toString())
        // 정상 줄은 그대로 맞춘다
        val row = List(20) { Vec3(3f, 0f, -0.1f * it) }
        assertEquals(1, hearspace.core.situation.BlockLines.fit(row, 0.15f, 1.0f, 0.1f).size)
    }
}
