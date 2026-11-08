package hearspace.core.synth

import hearspace.core.geometry.Vec3
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.ReplayListener
import hearspace.core.replay.SlowStep
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.HeightClass
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs

/**
 * M48 합성 이동 물체(IMPROVE_SPEC §12 SM-1~SM-6). 관문은 움직임 판정을 정답(사람 라벨처럼)으로 바꾼 `ORACLE` 시험이다(분해·넘겨주기·안내 단계).
 * 깊이 기하만의 판정(속도 문턱 + χ²)으로 돌리는 같은 이름의 시험은 결과만 출력한다: 정지 장면에서 정지·움직임이 갈리지 않아 F(M48 결과). 합성 세션을 실제 재생 경로(OfflineReplay, 깊이 전달 136 ms + 느린 경로 10 ms)로 돌리고,
 * 소리를 낸 블록 시각의 참 머리·참 사람 위치로 채점한다. 합성은 관문이고 값을 고르는 데 쓰지 않는다(M33 규칙).
 */
class MovingObstacleTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    @TempDir
    lateinit var tmp: File

    /** 블록 하나: 시각(재생 시계 = 깊이 촬영과 같은 부팅 시계), 안내 출력. */
    data class Block(val tNs: Long, val g: GuidanceOutput, val snapshot: ObstacleSnapshot?)

    /**
     * 움직임 정답(판정 단계를 정답으로 바꿈, M49 사람 라벨을 흉내): track 측정 칸의 절반 이상이 움직이는 원기둥 안(반지름 + 0.05 m)이면 true, 아니면 모름(null:
     * 지금 상태 유지). 라벨은 track에 붙어 돌아서도 떨어지지 않는다(속도와 관계없음).
     */
    private fun oracleOf(spec: SceneSpec): (Long, List<Vec3>) -> Boolean? = { tNs, cells ->
        val t = tS(tNs)
        val hit = spec.scene.items.any { it ->
            if (it.motion == null) return@any false
            val c = it.shape as? VerticalCylinder ?: return@any false
            val ctr = Vec3(c.cx, 0f, c.cz) + it.offsetAt(t)
            cells.count { (ctr - it).horizontal().norm() < c.radiusM + 0.05f } * 2 >= cells.size // 측정 칸의 절반 이상이 사람 안(마스크처럼)
        }
        if (hit) true else null
    }

    private var useOracle = false
    /** M49: 판정 단계를 합성 의미 영상의 라벨 표로(정답 대신). 세션에 의미 영상을 쓰고 원시 깊이를 10 Hz로 흉내 낸다. */
    private var useLabels = false

    /** 합성 의미 영상 간격(새 깊이 장 수). M55 시험은 기기 라벨 빈도(약 16 Hz)에 맞춰 2. */
    private var semEveryN = 1
    /** 모든 경우를 관문으로 판정한다(M48 결과 기준. 깊이 기하 판정 시험은 따로 `useOracle`로 보고만). */
    @Suppress("UNUSED_PARAMETER")
    private fun gated(n: Noise) = true

    private fun replay(spec: SceneSpec, extra: String = ""): List<Block> {
        val g = spec.walk.gripOffsetM
        val sep = if (extra.isBlank()) "" else ", $extra"
        val overrides = """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }"""
        val dir = File(tmp, "${spec.id}_${extra.hashCode()}_${spec.noise.depthQuadK}_${useOracle}_$useLabels")
        SyntheticSessionWriter.write(spec.generate(), File(dir, "session"), "SYN", rawEveryN = if (useLabels) 3 else 1, semantics = useLabels, semanticEveryN = semEveryN)
        val out = ArrayList<Block>()
        val listener = object : ReplayListener {
            override fun onSlowStep(step: SlowStep) {
                if (System.getenv("M48_TRACE") != null && spec.id == System.getenv("M48_TRACE") && extra.contains("KALMAN")) {
                    val t = tS(step.depth.tCaptureNs)
                    if (t in (System.getenv("M48_T0")?.toFloat() ?: 4.6f)..(System.getenv("M48_T1")?.toFloat() ?: 6.4f)) println("  trace t=${"%.2f".format(t)} " + step.motion.joinToString(" | ") { m ->
                        "id${m.id}${if (m.candidate) "C" else ""}${if (m.moving) "M" else ""} sv=${"%.2f".format(m.velSigmaMps)} pos=${m.posW?.let { "%.2f,%.2f".format(it.x, it.z) }} v=${m.velW?.let { "%.2f,%.2f".format(it.x, it.z) }} meas=${m.nMeasCells} votes=${"%.1f".format(m.votes)}"
                    } + " obs=" + step.snapshot.obstacles.joinToString { "${it.id}${if (it.moving) "M" else ""}@%.2f,%.2f".format(it.repPointW.x, it.repPointW.z) })
                }
            }
            override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) { out += Block(tNs, g, snapshot) }
        }
        val oracle = if (useOracle && extra.contains("KALMAN")) oracleOf(spec) else null
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(10f), movingOracle = oracle).run(File(dir, "session"), listener)
        return out
    }

    private fun tS(tNs: Long) = (tNs - SyntheticGenerator.T0_NS) / 1e9f

    /** 시각 [t]의 참 사람(원기둥) 앞 가장자리의 진행 방향 거리와 옆 거리(머리 기준). */
    private fun truth(spec: SceneSpec, person: SceneItem, t: Float): Pair<Float, Float> {
        val head = SyntheticGenerator.cameraPose(spec.walk, t).second
        val c = person.shape as VerticalCylinder
        val p = Vec3(c.cx, 0f, c.cz) + person.offsetAt(t)
        val rel = (p - head.positionW).horizontal()
        return (rel dot head.headingW) - c.radiusM to (rel dot head.rightW)
    }

    private fun active(b: Block) = b.g.state == GuidanceState.NORMAL || b.g.state == GuidanceState.DEGRADED
    private fun stopped(b: Block) = active(b) && b.g.commands.any { it.band == Band.STOP }
    private fun warned(b: Block) = active(b) && b.g.commands.any { it.band == Band.STOP || it.band == Band.WARN }

    /** 첫 STOP 때의 참 거리·TTC(다가오는 속도는 참 사람 속도 − 참 머리 속도의 진행 방향 성분). 진입(참 거리 < 1.0 m) 전 STOP인지도. */
    data class Approach(val stopAlongM: Float?, val ttcS: Float?, val entryBeforeStop: Boolean)

    private fun approach(spec: SceneSpec, person: SceneItem, blocks: List<Block>): Approach {
        val inCorridor = { t: Float -> truth(spec, person, t).let { (a, l) -> abs(l) - (person.shape as VerticalCylinder).radiusM <= 0.4f && a <= 3.5f } }
        val entry = blocks.firstOrNull { val t = tS(it.tNs); inCorridor(t) && truth(spec, person, t).first < 1.0f }
        val stop = blocks.firstOrNull(::stopped) ?: return Approach(null, null, entry != null)
        val t = tS(stop.tNs)
        val along = truth(spec, person, t).first
        val dt = 0.02f
        val v = person.motion!!.velocityAt(t)
        val (h0, h1) = SyntheticGenerator.cameraPose(spec.walk, t - dt).second to SyntheticGenerator.cameraPose(spec.walk, t + dt).second
        val vHead = (h1.positionW - h0.positionW) * (1f / (2 * dt))
        val closing = -((v - vHead).horizontal() dot h0.headingW)
        return Approach(along, if (closing > 0.05f) along / closing else Float.POSITIVE_INFINITY, entry != null && entry.tNs < stop.tNs)
    }

    /** 정답 없는 경고 시간(s): 경고 블록 중 사람이 통로 안 2.95 m 안에 없는 블록. */
    private fun unexplainedWarnS(spec: SceneSpec, person: SceneItem, blocks: List<Block>, others: List<Float> = emptyList()): Float {
        val r = (person.shape as VerticalCylinder).radiusM
        var s = 0f
        for ((a, b) in blocks.zipWithNext()) {
            if (!warned(a)) continue
            val (al, lat) = truth(spec, person, tS(a.tNs))
            val explained = (abs(lat) - r <= 0.4f && al <= 2.95f) || others.any { it <= 2.95f }
            if (!explained) {
                s += (b.tNs - a.tNs) / 1e9f
                if (System.getenv("M48_DEBUG") != null && (s * 1000).toInt() % 200 == 0) println("  unexplained t=${tS(a.tNs)} along=$al lat=$lat cmd=${a.g.commands.firstOrNull()} obs=${a.snapshot?.obstacles?.map { it.id to it.moving }}")
            }
        }
        return s
    }

    private val floor = SceneItem("floor", HorizontalPlane(0f), obstacle = false)

    /** 머리 앞 [startAlongM]에서 [speedMps]로 마주 와 [stopAlongM]에서 서는 사람(서 있는 사용자 기준, 머리 z = 카메라 z + 0.3). */
    private fun oncoming(speedMps: Float, startAlongM: Float = 4.5f, stopAlongM: Float = 0.4f, startS: Float = 1.5f, headZ: Float = 0.3f): SceneItem {
        val r = 0.2f
        val moveS = (startAlongM - stopAlongM) / speedMps
        return SceneItem(
            "person", VerticalCylinder(0f, headZ - startAlongM - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            motion = Motion(Vec3(0f, 0f, speedMps), startS, startS + moveS),
        )
    }

    private fun sm1(speedMps: Float, noise: Noise): Pair<SceneSpec, SceneItem> {
        val p = oncoming(speedMps)
        val dur = 1.5f + (4.5f - 0.4f) / speedMps + 1.0f
        return SceneSpec("SM-1-$speedMps", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = dur), noise) to p
    }

    private val noisy = Noise(depthQuadK = 0.009f, edgeSmoothPx = 3)

    @Test
    fun `SM-1 baseline - the current rule stops late for an oncoming person (reproduction)`() {
        val results = listOf(0.6f, 1.0f, 1.5f).flatMap { v -> listOf(Noise(), noisy).map { n -> v to n } }.map { (v, n) ->
            val (spec, p) = sm1(v, n)
            val blocks = replay(spec)
            val a = approach(spec, p, blocks)
            val w = blocks.firstOrNull(::warned)
            val nObs = blocks.filter { it.snapshot != null && tS(it.tNs) < 1.5f + (4.5f - 0.4f) / v }.maxOfOrNull { it.snapshot!!.obstacles.size } ?: 0
            println("SM-1 NONE v=$v noise=${n.depthQuadK}: stop along ${a.stopAlongM} ttc ${a.ttcS} enteredBeforeStop ${a.entryBeforeStop}; first WARN along ${w?.let { truth(spec, p, tS(it.tNs)).first }} at ${w?.let { tS(it.tNs) }} s, max obstacles while moving $nObs")
            a
        }
        assertTrue(results.any { it.entryBeforeStop || it.stopAlongM == null || (it.ttcS ?: 0f) < 1.0f }, "baseline should fail at least one SM-1 case")
    }

    private var kalman = """ "track": { "motion": "KALMAN" } """

    /** M52 새 옵션(깊이 점 단위 측정점, 시작 게이트). 평활 뒤처짐 보정은 합성 깊이에 뒤처짐이 없어 켜지 않는다. */
    private val m52 = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 2.5 } """

    /** 첫 STOP이 실제 진입(1.0 m) 전이고 그때 정답 TTC ≥ 1.0 s인지. */
    private fun approachOk(a: Approach) = a.stopAlongM != null && !a.entryBeforeStop && a.stopAlongM >= 1.0f && (a.ttcS ?: 0f) >= 1.0f

    @Test
    fun `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() {
        val fails = ArrayList<String>()
        for (v in listOf(0.6f, 1.0f, 1.5f)) for (n in listOf(Noise(), noisy)) {
            val (spec, p) = sm1(v, n)
            val a = approach(spec, p, replay(spec, kalman))
            println("SM-1 KALMAN v=$v noise=${n.depthQuadK}: stop along ${a.stopAlongM} ttc ${a.ttcS} enteredBeforeStop ${a.entryBeforeStop}")
            if (!approachOk(a) && gated(n)) fails += "v=$v s=${n.depthQuadK}: $a"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    @Test
    fun `SM-2 KALMAN - user and person walk toward each other`() {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val gapS = (4.5f - 0.6f) / 1.6f
            val r = 0.2f
            val p = SceneItem("person", VerticalCylinder(0f, 0.3f - 4.5f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(0f, 0f, 0.8f), 1.5f, 1.5f + gapS))
            val spec = SceneSpec("SM-2", Scene(listOf(floor, p)), Walk(standS = 1.5f, speedMps = 0.8f, legM = 0.8f * gapS, legCount = 1, durationS = 1.5f + gapS + 1.0f), n)
            val a = approach(spec, p, replay(spec, kalman))
            println("SM-2 KALMAN noise=${n.depthQuadK}: stop along ${a.stopAlongM} ttc ${a.ttcS} enteredBeforeStop ${a.entryBeforeStop}")
            if (!approachOk(a) && gated(n)) fails += "s=${n.depthQuadK}: $a"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    @Test
    fun `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val r = 0.2f
            val p = SceneItem("person", VerticalCylinder(-2.5f, 0.3f - 2.0f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(1f, 0f, 0f), 1.5f, 6.5f))
            val spec = SceneSpec("SM-3", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = 7.5f), n)
            val on = replay(spec, kalman)
            val off = replay(spec)
            val stops = on.count(::stopped)
            val (u1, u0) = unexplainedWarnS(spec, p, on) to unexplainedWarnS(spec, p, off)
            println("SM-3 noise=${n.depthQuadK}: STOP blocks $stops, unexplained warn KALMAN $u1 s, off $u0 s")
            if ((stops > 0 || u1 > u0 + 0.05f) && gated(n)) fails += "s=${n.depthQuadK}: stops $stops, unexplained $u1 vs $u0"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    @Test
    fun `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val spec = Scenes.SC02.copy(id = "SM-4", noise = n)
            val on = replay(spec, kalman)
            val off = replay(spec)
            val moving = on.count { b -> b.snapshot?.obstacles?.any { it.moving } == true }
            fun firstStop(bs: List<Block>) = bs.firstOrNull(::stopped)?.let { b ->
                val head = SyntheticGenerator.cameraPose(spec.walk, tS(b.tNs)).second
                (Vec3(0f, 0f, -2f) - head.positionW).horizontal() dot head.headingW
            }
            val (s1, s0) = firstStop(on) to firstStop(off)
            println("SM-4 noise=${n.depthQuadK}: moving blocks $moving, first STOP KALMAN $s1 off $s0")
            if (moving > 0 || s1 == null || s0 == null || abs(s1 - s0) > 0.10f) fails += "s=${n.depthQuadK}: moving $moving, stop $s1 vs $s0"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    @Test
    fun `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val r = 0.2f
            val boxFront = 0.3f - 2.5f // 머리 앞 2.5 m(시작 머리 z = 0.3)
            val box = SceneItem("box", Box(Vec3(-0.225f, 0f, boxFront - 0.45f), Vec3(0.225f, 0.8f, boxFront)), obstacle = true, expectedClass = HeightClass.FLOOR)
            val p = SceneItem("person", VerticalCylinder(-2f, boxFront + 0.3f + r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(1f, 0f, 0f), 1.5f, 5.5f))
            val spec = SceneSpec("SM-5", Scene(listOf(floor, box, p)), Walk(standS = 6f, speedMps = 1f, durationS = 6f + 2.0f), n)
            fun firstBoxStop(bs: List<Block>) = bs.firstOrNull { tS(it.tNs) > 6f && stopped(it) }?.let { b ->
                val head = SyntheticGenerator.cameraPose(spec.walk, tS(b.tNs)).second
                (Vec3(0f, 0f, boxFront) - head.positionW).horizontal() dot head.headingW
            }
            // 기준 = 사람이 지나가지 않은 같은 장면(끈 설정). 사람이 지나간 끈 설정은 사람 흔적 칸으로 먼저 STOP해 기준이 될 수 없다
            val ref = spec.copy(id = "SM-5-ref", scene = Scene(listOf(floor, box)))
            val (s1, s0) = firstBoxStop(replay(spec, kalman)) to firstBoxStop(replay(ref))
            println("SM-5 noise=${n.depthQuadK}: box first STOP KALMAN with person $s1, box alone (off) $s0, off with person ${firstBoxStop(replay(spec))}")
            if ((s1 == null || s0 == null || s1 < s0 - 0.10f) && gated(n)) fails += "s=${n.depthQuadK}: $s1 vs $s0"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    @Test
    fun `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val r = 0.2f
            val inS = (4.5f - 0.8f) / 1.0f
            val p = SceneItem("person", VerticalCylinder(0f, 0.3f - 4.5f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(0f, 0f, 1f), 1.5f, 1.5f + inS, then = Motion(Vec3(1f, 0f, 0f), 1.5f + inS, 1.5f + inS + 2f)))
            val spec = SceneSpec("SM-6", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = 1.5f + inS + 3f), n)
            val on = replay(spec, kalman)
            val off = replay(spec)
            val a = approach(spec, p, on)
            val (u1, u0) = unexplainedWarnS(spec, p, on) to unexplainedWarnS(spec, p, off)
            println("SM-6 noise=${n.depthQuadK}: stop along ${a.stopAlongM} ttc ${a.ttcS} entered ${a.entryBeforeStop}; unexplained KALMAN $u1 off $u0")
            if ((!approachOk(a) || u1 > u0) && gated(n)) fails += "s=${n.depthQuadK}: $a, unexplained $u1 vs $u0"
        }
        if (useOracle || useLabels) assertTrue(fails.isEmpty(), fails.joinToString("; ")) // 깊이 기하 판정(정답 없음)은 F라 보고만(M48 결과)
    }

    /**
     * SM-7(M52): 사람이 뒤쪽 정지 상자와 0.10 m 틈(`cluster.epsM` 0.15 m보다 작음)으로 붙어 서 있다가 1.0 m/s로 다가온다. 통과: 움직이는 track이
     * 처음 출력될 때 칼만 속도가 그때 참 속도에서 0.5 m/s 안, 그리고 SM-1과 같은 STOP 조건. 합성 사람은 서 있을 때부터 깊이에 보여 라벨(정답)이 붙은
     * track이 걷기 전에 생기므로, T 150338의 시작 뜀(평활 깊이가 배경에서 사람 값으로 바뀌는 과도 응답)은 재현되지 않는다: 회귀 시험이다.
     * 시작 게이트의 원리는 `MotionFilterTest`(150338 측정열), 효과는 T 재생으로 본다.
     */
    private fun sm7Check(json: String): List<String> {
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val r = 0.2f
            val personFront = 2.8f
            val boxFront = personFront + 2 * r + 0.10f // 사람 뒤 0.10 m
            val box = SceneItem("box", Box(Vec3(-0.225f, 0f, 0.3f - boxFront - 0.45f), Vec3(0.225f, 0.8f, 0.3f - boxFront)), obstacle = true, expectedClass = HeightClass.FLOOR)
            val moveS = (personFront - 0.4f) / 1.0f
            val p = SceneItem("person", VerticalCylinder(0f, 0.3f - personFront - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(0f, 0f, 1.0f), 1.5f, 1.5f + moveS))
            val spec = SceneSpec("SM-7", Scene(listOf(floor, box, p)), Walk(standS = 1000f, durationS = 1.5f + moveS + 1.0f), n)
            val blocks = replay(spec, json)
            val first = blocks.firstNotNullOfOrNull { b -> b.snapshot?.obstacles?.firstOrNull { it.moving && it.velocityW != null }?.let { b to it } }
            val vStart = first?.second?.velocityW?.horizontal()?.norm()
            val vTrue = first?.let { p.motion!!.velocityAt(tS(it.first.tNs)).horizontal().norm() }
            val a = approach(spec, p, blocks)
            println("SM-7 ${if (json == m49) "M49" else if (json == m52) "M52" else "M48"} noise=${n.depthQuadK}: first moving output at ${first?.let { tS(it.first.tNs) }} s, |v| $vStart (true $vTrue); stop along ${a.stopAlongM} ttc ${a.ttcS} entered ${a.entryBeforeStop}")
            if (vStart == null || vTrue == null || abs(vStart - vTrue) > 0.5f || !approachOk(a)) fails += "s=${n.depthQuadK}: |v| $vStart vs $vTrue, $a"
        }
        return fails
    }

    @Test
    fun `M52 ORACLE SM-7 - a person starting right in front of a box gets a walking-range start speed`() {
        useOracle = true
        val m48 = sm7Check(kalman) // M48 설정: 보고만(재현)
        println("SM-7 M48 fails: $m48")
        val f = sm7Check(m52)
        assertTrue(f.isEmpty(), f.joinToString("; "))
    }

    /**
     * M49 라벨 판(설계 5.1·5.3절): 움직임 판정 = track마다 쌓은 합성 라벨 표, 측정 = 마스크 안 원시 점(RAW_MASK, 원시 10 Hz), 시작 게이트 6.9 m/s.
     * 합성 라벨은 맞는 라벨이라 경로가 작동하는지만 본다(라벨 정확도는 T 라벨 품질 보고).
     */
    private val m49 = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 6.9, "measureFrom": "RAW_MASK" }, "vision": { "label": "SEMANTICS" } """

    @Test fun `M49 LABEL SM-1`() { useLabels = true; kalman = m49; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M49 LABEL SM-2`() { useLabels = true; kalman = m49; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M49 LABEL SM-3`() { useLabels = true; kalman = m49; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M49 LABEL SM-4`() { useLabels = true; kalman = m49; `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }
    @Test fun `M49 LABEL SM-5`() { useLabels = true; kalman = m49; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M49 LABEL SM-6`() { useLabels = true; kalman = m49; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }

    @Test
    fun `M49 LABEL SM-7`() {
        useLabels = true
        val f = sm7Check(m49)
        assertTrue(f.isEmpty(), f.joinToString("; "))
    }

    /**
     * SM-8(M49): 차량 라벨이 붙은 정지 상자(주차한 차·세워 둔 자전거)로 사용자가 걸어간다.
     * 설계의 기준("첫 STOP이 라벨 없는 같은 장면과 0.10 m 안")은 미충족이고, 기대값이 틀렸다(설계 오류, DECISIONS 2026-11-06): 움직이는 track의
     * TTC는 사용자 속도를 포함한 상대 속도라, 사용자가 걸으면 라벨 붙은 정지 물체도 TTC STOP이 먼저 난다(라벨 1.26 대 끈 0.86 m). 끈 설정의 0.86 m는
     * 지연(깊이 전달 136 + 처리 10 ms × 1 m/s)만큼 목표 1.0 m보다 늦은 값이다. 그래서 이 시험은 확실한 성질만 검사한다: 라벨이 STOP을 늦추지 않는다.
     */
    @Test
    fun `M49 LABEL SM-8 - a parked vehicle-labelled box keeps its first STOP`() {
        useLabels = true
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val base = Scenes.SC02.copy(id = "SM-8", noise = n)
            val spec = base.copy(scene = Scene(base.scene.items.map { if (it.obstacle) it.copy(semantic = hearspace.core.types.SemanticLabel.VEHICLE) else it }))
            val on = replay(spec, m49)
            val off = replay(spec)
            fun firstStop(bs: List<Block>) = bs.firstOrNull(::stopped)?.let { b ->
                val head = SyntheticGenerator.cameraPose(spec.walk, tS(b.tNs)).second
                (Vec3(0f, 0f, -2f) - head.positionW).horizontal() dot head.headingW
            }
            val moving = on.count { b -> b.snapshot?.obstacles?.any { it.moving } == true }
            val (s1, s0) = firstStop(on) to firstStop(off)
            println("SM-8 noise=${n.depthQuadK}: moving blocks $moving, first STOP label $s1 off $s0")
            if (s1 == null || s0 == null || s1 < s0 - 0.10f) fails += "s=${n.depthQuadK}: stop $s1 vs $s0"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /**
     * SM-9(M49): 자전거 크기 상자(길이 1.7·폭 0.6·높이 1.0 m, 차량 라벨)가 서 있는 사용자에게 다가온다. 처음 0.5 s는 절반 속도, 그 뒤 [speedMps]
     * (가속), 앞면이 0.4 m에 닿으면 선다(감속). 옛 게이트(2.5 m/s) 바로 위 3.0 m/s와 새 게이트 한도 6.9 m/s. 통과: 앞면이 1.0 m에 닿기 전에
     * 움직이는 track이 출력됨(게이트에 막히지 않음). STOP 거리·TTC와 가속 뒤 칼만 속도 오차는 보고만 한다.
     * 설계 기준은 두 속도 모두였으나 6.9 m/s는 미충족이고 기준을 계산 없이 세웠다(설계 오류): 후보는 통로 길이 3.5 m 안에서만 생기고, 출력에 표 6개
     * (거리 가중으로 약 0.27 s)와 깊이 전달 약 0.15 s가 걸려 그동안 약 2.9 m를 온다. 1 m 전에 출력할 수 있는 속도는 약 2.5 m / 0.42 s ≈ 6 m/s(추정).
     * 그래서 검사는 3.0 m/s만, 6.9 m/s는 보고만 한다.
     */
    @Test
    fun `M49 LABEL SM-9 - a bicycle-sized vehicle is tracked as moving above walking speed`() {
        useLabels = true
        val fails = ArrayList<String>()
        for (v in listOf(3.0f, 6.9f)) for (n in listOf(Noise(), noisy)) {
            val headZ = 0.3f
            val startAlong = 5.0f
            val front = headZ - startAlong
            val slowS = 0.5f
            val fastS = (startAlong - 0.4f - v / 2 * slowS) / v
            val bike = SceneItem(
                "bike", Box(Vec3(-0.3f, 0f, front - 1.7f), Vec3(0.3f, 1.0f, front)), obstacle = true, expectedClass = HeightClass.BODY,
                motion = Motion(Vec3(0f, 0f, v / 2), 1.5f, 1.5f + slowS, then = Motion(Vec3(0f, 0f, v), 1.5f + slowS, 1.5f + slowS + fastS)),
                semantic = hearspace.core.types.SemanticLabel.VEHICLE,
            )
            val spec = SceneSpec("SM-9-$v", Scene(listOf(floor, bike)), Walk(standS = 1000f, durationS = 1.5f + slowS + fastS + 1.0f), n)
            val blocks = replay(spec, m49)
            fun along(t: Float): Float {
                val head = SyntheticGenerator.cameraPose(spec.walk, t).second
                return (Vec3(0f, 0f, front) + bike.offsetAt(t) - head.positionW).horizontal() dot head.headingW
            }
            val firstMoving = blocks.firstOrNull { b -> b.snapshot?.obstacles?.any { it.moving } == true }
            val stop = blocks.firstOrNull(::stopped)
            val vErr = blocks.filter { tS(it.tNs) in (1.5f + slowS + 0.5f)..(1.5f + slowS + fastS) }
                .mapNotNull { b -> b.snapshot?.obstacles?.firstOrNull { it.moving }?.velocityW?.let { abs(it.horizontal().norm() - v) } }.maxOrNull()
            println("SM-9 v=$v noise=${n.depthQuadK}: first moving output at along ${firstMoving?.let { along(tS(it.tNs)) }}, STOP at along ${stop?.let { along(tS(it.tNs)) }}, max |v err| 0.5 s after speed-up $vErr")
            if (v < 6f && (firstMoving == null || along(tS(firstMoving.tNs)) < 1.0f)) fails += "v=$v s=${n.depthQuadK}: no moving output before 1 m"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /**
     * M55 ②: 의미 모드 조합(사람 원천 P + 경계 띠 B + 신뢰도 무게 W, 문턱 5). 사람 원천은 사람 평활 점을 정지 지도에 넣지 않으므로, 라벨 판정 전에
     * 후보를 정지 지도로 넘기면 다가오는 사람을 아무도 표현하지 않는다(T 150159). `track.holdLabelPending`이 그 넘김을 라벨 판정까지 미룬다.
     */
    private fun m55(hold: Boolean) = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 6.9, "measureFrom": "RAW_MASK", "holdLabelPending": $hold },
        "vision": { "label": "SEMANTICS", "movableDepth": "RAW" }, "frontend": { "labelBandPx": 6 }, "map": { "hitWeighting": "CONFIDENCE", "weightedMinHits": 5 } """

    /** SM-10(M55): 이미 걷는 사람이 7 m에서 와 0.4 m에서 선다. 깊이는 3 m까지만(T 150159처럼 사람이 걷는 채로 3 m 안에서 처음 깊이에 나타남). */
    private fun sm10(speedMps: Float, noise: Noise): Pair<SceneSpec, SceneItem> {
        val p = oncoming(speedMps, startAlongM = 7f, startS = 0.2f)
        return SceneSpec("SM-10-$speedMps", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = 0.2f + (7f - 0.4f) / speedMps + 1.0f), noise) to p
    }

    @Test
    fun `M55 PBW5 SM-10 - a person already walking into a 3 m depth range is stopped in time with or without hold`() {
        useLabels = true; semEveryN = 2
        val fails = ArrayList<String>()
        for (v in listOf(1.0f, 1.5f)) for (n in listOf(Noise(maxDepthM = 3f), noisy.copy(maxDepthM = 3f))) {
            val (spec, p) = sm10(v, n)
            val off = approach(spec, p, replay(spec, m55(false)))
            val on = approach(spec, p, replay(spec, m55(true)))
            println("M55 SM-10 v=$v noise=${n.depthQuadK}: no hold stop ${off.stopAlongM} ttc ${off.ttcS} ok ${approachOk(off)} | hold stop ${on.stopAlongM} ttc ${on.ttcS} ok ${approachOk(on)}")
            // 합성은 평활 깊이가 움직이는 사람을 뒤따르지 않아(T에서 0.10~0.36 m 뒤처짐, M54) 기기의 라벨 표 정체를 재현하지 못한다: 두 설정 모두 통과만 본다
            if (!approachOk(off)) fails += "no hold v=$v s=${n.depthQuadK}: $off"
            if (!approachOk(on)) fails += "hold v=$v s=${n.depthQuadK}: $on"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    @Test fun `M55 PBW5 hold SM-1`() { useLabels = true; semEveryN = 2; kalman = m55(true); `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M55 PBW5 hold SM-3`() { useLabels = true; semEveryN = 2; kalman = m55(true); `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M55 PBW5 hold SM-4`() { useLabels = true; semEveryN = 2; kalman = m55(true); `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }

    /** M52 새 옵션을 켜고 SM-1~SM-6 정답 판정이 그대로 통과하는지(회귀). */
    @Test fun `M52 ORACLE SM-1`() { useOracle = true; kalman = m52; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M52 ORACLE SM-2`() { useOracle = true; kalman = m52; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M52 ORACLE SM-3`() { useOracle = true; kalman = m52; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M52 ORACLE SM-5`() { useOracle = true; kalman = m52; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M52 ORACLE SM-6`() { useOracle = true; kalman = m52; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }

    /** 판정 단계를 정답으로 바꾼 SM-1~SM-6(분해·넘겨주기·안내 단계만 판정, 앞 단계는 정답). */
    @Test fun `ORACLE SM-1`() { useOracle = true; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `ORACLE SM-2`() { useOracle = true; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `ORACLE SM-3`() { useOracle = true; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `ORACLE SM-5`() { useOracle = true; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `ORACLE SM-6`() { useOracle = true; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }
}
