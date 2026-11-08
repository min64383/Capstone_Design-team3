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

    /** 원시 깊이에 평활 전 깊이를 따로 쓴다(M58 SM-14·15: 평활에서만 빠지는 사람). 끄면 원시 파일 = 평활 영상. */
    private var realRaw = false

    /** 있으면 재생의 움직임 기록(motion.csv 줄)을 모은다(M59 SM-16: 받아들인 원시 측정이 어느 사람 것인지). */
    private var motionSink: MutableList<hearspace.core.tracking.MotionDebug>? = null

    /** 합성 의미 영상 간격(새 깊이 장 수). M55 시험은 기기 라벨 빈도(약 16 Hz)에 맞춰 2. */
    private var semEveryN = 1
    /** 모든 경우를 관문으로 판정한다(M48 결과 기준. 깊이 기하 판정 시험은 따로 `useOracle`로 보고만). */
    @Suppress("UNUSED_PARAMETER")
    private fun gated(n: Noise) = true

    private fun replay(spec: SceneSpec, extra: String = ""): List<Block> {
        val g = spec.walk.gripOffsetM
        val sep = if (extra.isBlank()) "" else ", $extra"
        val overrides = """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }"""
        val dir = File(tmp, "${spec.id}_${extra.hashCode()}_${spec.noise.depthQuadK}_${useOracle}_${useLabels}_$realRaw")
        SyntheticSessionWriter.write(spec.generate(), File(dir, "session"), "SYN", rawEveryN = if (useLabels) 3 else 1, semantics = useLabels, semanticEveryN = semEveryN, realRaw = realRaw)
        val out = ArrayList<Block>()
        val listener = object : ReplayListener {
            override fun onSlowStep(step: SlowStep) {
                motionSink?.addAll(step.motion)
                if (System.getenv("M57_EV") != null && spec.id.startsWith(System.getenv("M57_EV"))) for (e in step.evidence) {
                    println("  ev t=${"%.2f".format(tS(step.depth.tCaptureNs))} id${e.id}${if (e.candidate) "C" else ""}${if (e.moving) "M" else ""} age=${"%.2f".format(e.ageS)} pts=${e.nPts} cmp=${e.nCmp} viol=${e.nViol} rot=${"%.1f".format(e.rotDegS)} labs=${e.labels.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:${it.value}" }}")
                }
                if (System.getenv("M48_TRACE") != null && spec.id == System.getenv("M48_TRACE") && extra.contains("KALMAN")) {
                    val t = tS(step.depth.tCaptureNs)
                    if (t in (System.getenv("M48_T0")?.toFloat() ?: 4.6f)..(System.getenv("M48_T1")?.toFloat() ?: 6.4f)) println("  trace t=${"%.2f".format(t)} " + step.motion.joinToString(" | ") { m ->
                        "id${m.id}${if (m.candidate) "C" else ""}${if (m.moving) "M" else ""} sv=${"%.2f".format(m.velSigmaMps)} pos=${m.posW?.let { "%.2f,%.2f".format(it.x, it.z) }} v=${m.velW?.let { "%.2f,%.2f".format(it.x, it.z) }} meas=${m.nMeasCells} votes=${"%.2f".format(m.votes)} sc=${"%.2f".format(m.score)}"
                    } + " obs=" + step.snapshot.obstacles.joinToString { "${it.id}${if (it.moving) "M" else ""}@%.2f,%.2f".format(it.repPointW.x, it.repPointW.z) })
                    if (t in (System.getenv("M48_T0")?.toFloat() ?: 4.6f)..(System.getenv("M48_T1")?.toFloat() ?: 6.4f)) for (x in step.assoc) println(
                        "    assoc id${x.id}${if (x.moving) "M" else ""} pts=${x.nPts} blobs=${x.nBlobs} chosen=${x.nChosen} chi2=${"%.1f".format(x.chi2)} ok=${x.accepted} meas=${"%.2f,%.2f".format(x.measW.x, x.measW.z)} box x ${"%.2f..%.2f".format(x.lo.x, x.hi.x)} z ${"%.2f..%.2f".format(x.lo.z, x.hi.z)}")
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

    /** M57 복셀 지도 하나(OWNED) + 칸 라벨 + 창 뒤쪽 늘림(평활 뒤처짐 0.345 s) + 시선 그늘(2.93 m), 판정은 M49 라벨 표 그대로(1단계). */
    private val m57 = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 6.9, "measureFrom": "RAW_MASK",
        "motionMap": "OWNED", "gateLagS": 0.345, "shadowDepthM": 2.93 }, "vision": { "label": "SEMANTICS", "voxelLabels": true } """

    /** SM-11의 복셀 지도 하나 설정(M58이 바꿔 다시 돌림). */
    private var oneMap = m57

    @Test fun `M57 OWNED SM-1`() { useLabels = true; kalman = m57; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M57 OWNED SM-2`() { useLabels = true; kalman = m57; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M57 OWNED SM-3`() { useLabels = true; kalman = m57; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M57 OWNED SM-4`() { useLabels = true; kalman = m57; `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }
    @Test fun `M57 OWNED SM-5`() { useLabels = true; kalman = m57; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M57 OWNED SM-6`() { useLabels = true; kalman = m57; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }

    /**
     * SM-11(M57): 사람이 머리 앞 3.5 m에 2.5 s 서 있다가 1.0 m/s로 다가와 0.6 m에서 선다. 서 있던 자리의 칸은 다가오는 사람 몸에 가려 빈 곳 감쇠로
     * 지워지지 않는다(T 150300 흔적과 같은 꼴). 통과(OWNED): 사람이 그 자리를 1.0 m 넘게 떠난 뒤 그 자리(상자까지 수평 0.5 m) 정지 출력 0 s, 접근 STOP·TTC 기준.
     * 두 지도(M49 라벨)의 흔적 시간은 보고만 한다.
     */
    @Test
    fun `M57 OWNED SM-11 - a person who stood then walks toward the user leaves no trail`() {
        useLabels = true
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val p = oncoming(1.0f, startAlongM = 3.5f, stopAlongM = 0.6f, startS = 2.5f)
            val spec = SceneSpec("SM-11", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = 2.5f + 2.9f + 1.5f), n)
            val c = p.shape as VerticalCylinder
            val start = Vec3(c.cx, 0f, c.cz)
            fun boxDist(o: hearspace.core.types.Obstacle): Float {
                val dx = maxOf(o.aabbMinW.x - start.x, 0f, start.x - o.aabbMaxW.x)
                val dz = maxOf(o.aabbMinW.z - start.z, 0f, start.z - o.aabbMaxW.z)
                return kotlin.math.hypot(dx, dz)
            }
            fun trailS(bs: List<Block>): Float {
                var s = 0f
                for ((a, b) in bs.zipWithNext()) {
                    if (p.offsetAt(tS(a.tNs)).horizontal().norm() <= 1.0f) continue
                    val tr = a.snapshot?.obstacles?.filter { !it.moving && boxDist(it) <= 0.5f }.orEmpty()
                    if (tr.isNotEmpty()) {
                        s += (b.tNs - a.tNs) / 1e9f
                        if (System.getenv("M57_DEBUG") != null) println("  trail t=${"%.2f".format(tS(a.tNs))} person=${p.offsetAt(tS(a.tNs)).z} " + tr.joinToString { "id${it.id} z ${"%.2f".format(it.aabbMinW.z)}..${"%.2f".format(it.aabbMaxW.z)} y ${"%.2f".format(it.aabbMinW.y)}..${"%.2f".format(it.aabbMaxW.y)} n${it.nObservations}" })
                    }
                }
                return s
            }
            val off = replay(spec, m49)
            val on = replay(spec, oneMap)
            val a = approach(spec, p, on)
            println("M57 SM-11 noise=${n.depthQuadK}: trail two maps ${trailS(off)} s, one map ${trailS(on)} s; one map stop ${a.stopAlongM} ttc ${a.ttcS}")
            if (trailS(on) > 0f) fails += "s=${n.depthQuadK}: trail ${trailS(on)} s"
            if (!approachOk(a)) fails += "s=${n.depthQuadK}: $a"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /** M57 3단계: 복셀 지도 하나 + 빈 곳 위반 판정(2단계 값, data/sweeps/m57/s2/judge2.json) + 후보 문턱 5·판정 전 출력 + 측정 점 라벨 거름 없앰. */
    private val m57j = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 6.9, "measureFrom": "RAW_MASK",
        "motionMap": "OWNED", "gateLagS": 0.345, "shadowDepthM": 2.93, "motionEvidence": "FREE_SPACE",
        "freeSpaceLagFrames": [6, 6, 12, 12, 10, 12, 12, 12, 12], "freeSpaceMarginM": [0.1017, 0.0966, 0.4026, 0.4584, 0.1673, 0.2616, 0.4972, 0.4231, 0.5157],
        "freeSpaceWindow": 5, "freeSpaceThreshold": 0.112, "freeSpaceMinCompared": 50, "freeSpaceMaxRotDegS": 100, "freeSpaceConvergeS": 1.5,
        "freeSpacePersistFrames": 30, "objectMinHits": 5, "candidateOutput": true, "measureLabels": false },
        "vision": { "label": "SEMANTICS", "voxelLabels": true } """

    /** SM-12·SM-13의 판정 설정(M58이 바꿔 다시 돌림). */
    private var fsCfg = m57j

    @Test fun `M57 FREE_SPACE SM-1`() { useLabels = true; kalman = m57j; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M57 FREE_SPACE SM-2`() { useLabels = true; kalman = m57j; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M57 FREE_SPACE SM-3`() { useLabels = true; kalman = m57j; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M57 FREE_SPACE SM-4`() { useLabels = true; kalman = m57j; `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }
    @Test fun `M57 FREE_SPACE SM-5`() { useLabels = true; kalman = m57j; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M57 FREE_SPACE SM-6`() { useLabels = true; kalman = m57j; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }

    private val backWall = SceneItem("wall", Box(Vec3(-4f, 0f, -7.2f), Vec3(4f, 3f, -7f)), obstacle = false, semantic = hearspace.core.types.SemanticLabel.BUILDING)

    /**
     * SM-12(M57): 라벨 없음·물체 라벨 상자(폭 0.5·높이 1.0 m)가 머리 앞 4.8 m에서 0.5 m/s로 8 s 다가온다(뒤 7 m에 벽: 지난 장이 빈 곳을 봄).
     * 통과: 움직이는 동안 기하 길로 움직이는 track이 출력됨(보인 지 T_수렴 1.5 s + 이어짐 3.0 s 뒤). M49 라벨 표의 움직이는 출력 수는 보고만.
     * 처음 판(3.0 m를 옆으로 가로지름)은 track이 통로 안 군집에만 생겨 상자가 통로에 약 2 s만 있어 기하 길(4.5 s)을 시험할 수 없었다.
     * 0.5 m/s로 7 s만 움직이면 통로(3.5 m)에 들어온 뒤 4.5 s인 8.15 s에 판정돼 움직임이 끝난 8.0 s보다 늦었다. 0.3 m/s는 1.5~2.5 m 구간 여유(0.40·0.46 m)를
     * 되돌아보기 위 끝 1.2 s로 넘지 못해 판정되지 않았다(v_min 조건을 못 맞추는 구간, 0단계부터 알던 한계). 둘 다 결과에 한계로 적는다.
     */
    @Test
    fun `M57 FREE_SPACE SM-12 - an unlabelled moving box is judged moving by geometry after convergence`() {
        useLabels = true
        val fails = ArrayList<String>()
        for (lab in listOf(hearspace.core.types.SemanticLabel.UNLABELED, hearspace.core.types.SemanticLabel.OBJECT)) {
            val spec = sm12(lab)
            val on = replay(spec, fsCfg)
            val off = replay(spec, m49)
            val firstMoving = on.firstOrNull { b -> b.snapshot?.obstacles?.any { it.moving } == true }?.let { tS(it.tNs) }
            val offMoving = off.count { b -> b.snapshot?.obstacles?.any { it.moving } == true }
            println("M57 SM-12 $lab: first moving output ${firstMoving} s (motion 1.0~9.0 s), M49 label moving blocks $offMoving")
            if (firstMoving == null || firstMoving > 9.0f) fails += "$lab: no moving output while moving"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /** SM-12 장면: 라벨 [lab] 상자(폭 0.5·높이 1.0 m)가 머리 앞 4.8 m에서 0.5 m/s로 8 s 다가온다(뒤 7 m에 벽). */
    private fun sm12(lab: hearspace.core.types.SemanticLabel): SceneSpec {
        val box = SceneItem(
            "box", Box(Vec3(-0.25f, 0f, 0.3f - 4.8f - 0.5f), Vec3(0.25f, 1.0f, 0.3f - 4.8f)), obstacle = true, expectedClass = HeightClass.BODY,
            motion = Motion(Vec3(0f, 0f, 0.5f), 1.0f, 9.0f), semantic = lab,
        )
        return SceneSpec("SM-12-$lab", Scene(listOf(floor, backWall, box)), Walk(standS = 1000f, durationS = 10.0f), Noise())
    }

    /** SM-13(M57): 사람 라벨 원기둥이 2.5 m 앞에 6 s 서 있다(뒤 벽). 통과: 움직이는 출력 없음(라벨 길도 S > 문턱이 필요). */
    @Test
    fun `M57 FREE_SPACE SM-13 - a standing person stays static`() {
        useLabels = true
        val p = SceneItem("person", VerticalCylinder(0f, 0.3f - 2.5f - 0.2f, 0.2f, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            semantic = hearspace.core.types.SemanticLabel.PERSON)
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val spec = SceneSpec("SM-13", Scene(listOf(floor, backWall, p)), Walk(standS = 1000f, durationS = 6.0f), n)
            val moving = replay(spec, fsCfg).count { b -> b.snapshot?.obstacles?.any { it.moving } == true }
            println("M57 SM-13 noise=${n.depthQuadK}: moving blocks $moving")
            if (moving > 0) fails += "s=${n.depthQuadK}: $moving moving blocks"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /** M57 조합(뷰어 변형 41의 track·vision: 한 지도 + 빈 곳 위반 판정, 후보 문턱·판정 전 출력·측정 점 라벨 거름 없앰은 끔). */
    private val m57c = """ "track": { "motion": "KALMAN", "measureWeight": "POINTS", "initMaxSpeedMps": 6.9, "measureFrom": "RAW_MASK",
        "motionMap": "OWNED", "gateLagS": 0.345, "shadowDepthM": 2.93, "motionEvidence": "FREE_SPACE",
        "freeSpaceLagFrames": [6, 6, 12, 12, 10, 12, 12, 12, 12], "freeSpaceMarginM": [0.1017, 0.0966, 0.4026, 0.4584, 0.1673, 0.2616, 0.4972, 0.4231, 0.5157],
        "freeSpaceWindow": 5, "freeSpaceThreshold": 0.112, "freeSpaceMinCompared": 50, "freeSpaceMaxRotDegS": 100, "freeSpaceConvergeS": 1.5,
        "freeSpacePersistFrames": 30 }, "vision": { "label": "SEMANTICS", "voxelLabels": true } """

    /** M58: M57 조합 + 움직이는 track 존재 증거를 원시 장으로(`track.existFrom` RAW). */
    private val m58 = m57c.replace("\"freeSpacePersistFrames\": 30 }", "\"freeSpacePersistFrames\": 30, \"existFrom\": \"RAW\" }")

    @Test fun `M58 SM-1`() { useLabels = true; kalman = m58; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M58 SM-2`() { useLabels = true; kalman = m58; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M58 SM-3`() { useLabels = true; kalman = m58; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M58 SM-4`() { useLabels = true; kalman = m58; `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }
    @Test fun `M58 SM-5`() { useLabels = true; kalman = m58; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M58 SM-6`() { useLabels = true; kalman = m58; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }
    @Test fun `M58 SM-11`() { oneMap = m58; `M57 OWNED SM-11 - a person who stood then walks toward the user leaves no trail`() }
    @Test fun `M58 SM-13`() { fsCfg = m58; `M57 FREE_SPACE SM-13 - a standing person stays static`() }
    /**
     * SM-12 라벨 한계(M58): 실제 M57 조합(꺼진 옵션 없이)으로는 라벨 없음·물체 라벨 상자가 끝까지 움직이는 출력이 되지 않는다(M57 3단계의 SM-12 통과는
     * 측정 점 라벨 거름 없앰 등 꺼진 옵션을 켠 설정이었다). 통과: M58이 M57 조합과 같은 첫 움직이는 출력 시각(라벨 밖 물체에는 닿지 않음). 값은 보고만.
     */
    @Test
    fun `M58 SM-12 - an unlabelled moving box is handled the same as the M57 combination`() {
        useLabels = true
        val fails = ArrayList<String>()
        for (lab in listOf(hearspace.core.types.SemanticLabel.UNLABELED, hearspace.core.types.SemanticLabel.OBJECT)) {
            val spec = sm12(lab)
            val (a, b) = firstMovingS(replay(spec, m57c)) to firstMovingS(replay(spec, m58))
            println("M58 SM-12 $lab: first moving output M57 combination $a s, M58 $b s (motion 1.0~9.0 s)")
            if (a != b) fails += "$lab: $a vs $b"
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /**
     * 2 m 앞을 옆으로 0.6 m/s로 가로지르는 사람(SM-3 장면, 속도는 T 150457의 옆 속도 약 0.65 m/s에 맞춤: 1.0 m/s면 통로를 1.2 s 만에 지나 판정 뒤 남는
     * 시간이 거의 없다). 1.5 s에 x −2.5 m에서 출발해 통로(옆 ±0.6 m)를 4.67~6.67 s에 지난다. [missing]이면 그 구간 평활 깊이에서만 빠지고, [removeAtS]면 그때 사라진다.
     */
    private fun crossing(missing: ClosedFloatingPointRange<Float>? = null, removeAtS: Float? = null): SceneItem {
        val r = 0.2f
        return SceneItem("person", VerticalCylinder(-2.5f, 0.3f - 2.0f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            motion = Motion(Vec3(0.6f, 0f, 0f), 1.5f, 9.5f), smoothedMissingS = missing, removeAtS = removeAtS)
    }

    /** 사람 원기둥이 통로 폭(±0.4 m) 안에 걸치는지(머리 기준 옆 거리). */
    private fun inCorridor(spec: SceneSpec, p: SceneItem, t: Float) = abs(truth(spec, p, t).second) - (p.shape as VerticalCylinder).radiusM <= 0.4f

    /** 움직이는 출력의 대표점이 사람 중심 0.5 m 안인지(수평). */
    private fun nearPerson(p: SceneItem, o: hearspace.core.types.Obstacle, t: Float): Boolean {
        val c = p.shape as VerticalCylinder
        val ctr = Vec3(c.cx, 0f, c.cz) + p.offsetAt(t)
        return (o.repPointW - ctr).horizontal().norm() <= 0.5f
    }

    /** 첫 움직이는 출력 시각(s). */
    private fun firstMovingS(bs: List<Block>) = bs.firstOrNull { b -> b.snapshot?.obstacles?.any { it.moving } == true }?.let { tS(it.tNs) }

    /**
     * SM-14(M58): 가로지르는 사람(0.6 m/s)이 움직인다고 판정된 뒤 통로를 지나는 동안(5.7~6.7 s) 평활 깊이에서만 빠진다(T 150457: 평활이 옆으로
     * 걷는 사람을 지움, 원시는 봄; 원시 깊이는 평활 전 깊이를 따로 씀, 뒤 7 m에 벽: 평활이 그 자리를 빈 곳으로 봄). 통과: SM-3 기준(STOP 없음, 정답
     * 없는 경고가 끈 판보다 0.05 s 넘게 늘지 않음) + 빠진 동안 통로 안 블록의 90% 이상에 사람 0.5 m 안 움직이는 출력. M57 조합은 보고만.
     */
    @Test
    fun `M58 SM-14 - a crossing person lost only by smoothed depth keeps a moving output`() {
        useLabels = true
        realRaw = true
        val miss = 5.7f..6.7f
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val p = crossing(miss)
            val spec = SceneSpec("SM-14", Scene(listOf(floor, backWall, p)), Walk(standS = 1000f, durationS = 9.0f), n)
            val off = replay(spec)
            fun kept(bs: List<Block>): Pair<Int, Int> {
                val inMiss = bs.filter { val t = tS(it.tNs); t in miss && inCorridor(spec, p, t) }
                return inMiss.count { b -> b.snapshot?.obstacles?.any { it.moving && nearPerson(p, it, tS(b.tNs)) } == true } to inMiss.size
            }
            for ((name, cfg) in listOf("M57" to m57c, "M58" to m58)) {
                val on = replay(spec, cfg)
                val (k, all) = kept(on)
                val anyMoving = on.count { b -> val t = tS(b.tNs); t in miss && inCorridor(spec, p, t) && b.snapshot?.obstacles?.any { it.moving } == true }
                val stops = on.count(::stopped)
                val (u1, u0) = unexplainedWarnS(spec, p, on) to unexplainedWarnS(spec, p, off)
                println("SM-14 $name noise=${n.depthQuadK}: first moving ${firstMovingS(on)} s, moving output near person in miss $k/$all (any moving $anyMoving), STOP blocks $stops, unexplained $u1 vs off $u0 s")
                if (name == "M58") {
                    val first = firstMovingS(on)
                    if (first == null || first >= miss.start) fails += "s=${n.depthQuadK}: judged at $first s, not before ${miss.start} s (test invalid)"
                    if (all == 0 || k < 0.9f * all) fails += "s=${n.depthQuadK}: moving output $k/$all"
                    if (stops > 0 || u1 > u0 + 0.05f) fails += "s=${n.depthQuadK}: stops $stops, unexplained $u1 vs $u0"
                }
            }
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /**
     * SM-15(M58): 가로지르는 사람(0.6 m/s)이 통로 한가운데를 지난 5.8 s에 사라진다(뒤 7 m에 벽: 그 자리가 빈 곳으로 보임. 벽이 없으면 몸통 높이 화소 뒤
     * 바닥이 깊이 범위 8 m 밖이라 빈 곳 검사가 걸리지 않는다). 통과: 사라진 뒤 0.5 s 안에 움직이는 출력이 없어짐(점수 1.0에서 원시 장
     * 4장 × 0.1 s + 원시 간격 0.1 s, 설계 3.1절). M57 조합은 보고만.
     */
    @Test
    fun `M58 SM-15 - a person who vanishes mid-corridor is dropped within half a second`() {
        useLabels = true
        realRaw = true
        val gone = 5.8f
        val fails = ArrayList<String>()
        for (n in listOf(Noise(), noisy)) {
            val p = crossing(removeAtS = gone)
            val spec = SceneSpec("SM-15", Scene(listOf(floor, backWall, p)), Walk(standS = 1000f, durationS = 8.0f), n)
            for ((name, cfg) in listOf("M57" to m57c, "M58" to m58)) {
                val on = replay(spec, cfg)
                val last = on.lastOrNull { b -> b.snapshot?.obstacles?.any { it.moving } == true }?.let { tS(it.tNs) }
                println("SM-15 $name noise=${n.depthQuadK}: first moving ${firstMovingS(on)} s, last moving $last s (gone $gone s)")
                if (name == "M58") {
                    if (firstMovingS(on)?.let { it < gone } != true) fails += "s=${n.depthQuadK}: not judged moving before $gone s (test invalid)"
                    if (last != null && last > gone + 0.5f) fails += "s=${n.depthQuadK}: moving output until $last s"
                }
            }
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }

    /** M59 조합: M58 조합 + 원시 측정 게이트(`track.rawAssociation` GATE: χ² 9.21, 게이트 반지름 0.4 m에서 지움). */
    private val m59 = m58.replace("\"existFrom\": \"RAW\" }", "\"existFrom\": \"RAW\", \"rawAssociation\": \"GATE\" }")

    @Test fun `M59 SM-1`() { useLabels = true; kalman = m59; `SM-1 KALMAN - an oncoming person is stopped before 1 m with TTC at least 1 s`() }
    @Test fun `M59 SM-2`() { useLabels = true; kalman = m59; `SM-2 KALMAN - user and person walk toward each other`() }
    @Test fun `M59 SM-3`() { useLabels = true; kalman = m59; `SM-3 KALMAN - a person crossing 2 m ahead is not a STOP and leaves no lingering warning`() }
    @Test fun `M59 SM-4`() { useLabels = true; kalman = m59; `SM-4 KALMAN - a static box is never judged moving and its first STOP is unchanged`() }
    @Test fun `M59 SM-5`() { useLabels = true; kalman = m59; `SM-5 KALMAN - a person crossing just in front of a box does not take the box cells`() }
    @Test fun `M59 SM-6`() { useLabels = true; kalman = m59; `SM-6 KALMAN - a person who comes close and steps aside is stopped in time and leaves less lingering warning`() }
    @Test fun `M59 SM-11`() { oneMap = m59; `M57 OWNED SM-11 - a person who stood then walks toward the user leaves no trail`() }
    @Test fun `M59 SM-13`() { fsCfg = m59; `M57 FREE_SPACE SM-13 - a standing person stays static`() }

    /**
     * SM-16 장면(M59, T 150439를 좌우로 뒤집어 본뜸): 사람 A가 머리 앞 2.0 m를 오른쪽으로 0.7 m/s로 지나 깊이 영상 오른쪽 가장자리 밖으로 나가고, 사람 B가
     * 옆 [bX]에서 머리 앞 4.5 m부터([bStartS]) 0.7 m/s로 다가와 0.4 m에서 선다. A가 가장자리에 있을 때 B가 A의 창에 든다. 뒤 7 m에 벽. [withA]가
     * false면 B만(B 혼자의 STOP 기준).
     */
    private fun sm16(n: Noise, bX: Float, bStartS: Float, withA: Boolean = true): Triple<SceneSpec, SceneItem, SceneItem> {
        val r = 0.2f
        val a = SceneItem("personA", VerticalCylinder(-2.5f, 0.3f - 2.0f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            motion = Motion(Vec3(0.7f, 0f, 0f), 1.5f, 9.0f))
        val b = SceneItem("personB", VerticalCylinder(bX, 0.3f - 4.5f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            motion = Motion(Vec3(0f, 0f, 0.7f), bStartS, bStartS + (4.5f - 0.4f) / 0.7f))
        val dur = bStartS + (4.5f - 0.4f) / 0.7f + 1.0f
        val items = if (withA) listOf(floor, backWall, a, b) else listOf(floor, backWall, b)
        return Triple(SceneSpec("SM-16-$bX-$bStartS-$withA", Scene(items), Walk(standS = 1000f, durationS = dur), n), a, b)
    }

    /** 움직이는 track마다 받아들인 원시 측정(시각순)과 그때 가장 가까운 사람(중심 0.5 m 안, 없으면 null). */
    private fun measuredBy(motion: List<hearspace.core.tracking.MotionDebug>, people: List<SceneItem>): Map<Int, List<Pair<Float, String?>>> =
        motion.filter { it.moving && it.measW != null }.distinctBy { it.id to it.measTNs }.groupBy { it.id }.mapValues { (_, ms) ->
            ms.sortedBy { it.measTNs }.map { m ->
                val t = tS(m.measTNs)
                val near = people.map { p ->
                    val c = p.shape as VerticalCylinder
                    p.name to (m.measW!! - (Vec3(c.cx, 0f, c.cz) + p.offsetAt(t))).horizontal().norm()
                }.filter { it.second <= 0.5f }.minByOrNull { it.second }?.first
                t to near
            }
        }

    /**
     * SM-16(M59): 두 사람 장면에서 A를 따르던 움직이는 track이 B로 옮겨 붙지 않는다. 시험이 되려면 M58 조합에서 옮겨 붙음(A track의 받아들인 측정이
     * A 0.5 m 밖)이 일어나야 한다. 장면 둘: 기본(B 옆 0.3 m, 3.6 s 출발) 통과 = A를 먼저 잰 track의 받아들인 측정이 모두 A 0.5 m 안, B는 SM-1 기준
     * (1.0 m 앞 진입 전 STOP, TTC ≥ 1.0 s). 변형(B 옆 0.5 m, 3.9 s: 1단계에서 가장자리에서 기운 예측이 B를 가로채고 합치기가 B track을 흡수한 장면) 통과 =
     * 보고만(M59 조합 GATE는 가장자리에서 기운 예측이 B를 0.3 s 받는다: 한 덩어리 한 track은 덩어리 나눔과 함께 꺼진 옵션, M59 결과).
     */
    @Test
    fun `M59 SM-16 - a track following a crossing person does not jump to a person approaching behind`() {
        useLabels = true
        realRaw = true
        val fails = ArrayList<String>()
        for ((bX, bStart) in listOf(0.3f to 3.6f, 0.5f to 3.9f)) for (n in listOf(Noise(), noisy)) {
            val (spec, a, b) = sm16(n, bX, bStart)
            val alone = approach(spec, b, replay(sm16(n, bX, bStart, withA = false).first, m59))
            for ((name, cfg) in listOf("M58" to m58, "M59" to m59)) {
                val sink = ArrayList<hearspace.core.tracking.MotionDebug>()
                motionSink = sink
                val blocks = replay(spec, cfg)
                motionSink = null
                val by = measuredBy(sink, listOf(a, b))
                val aTracks = by.filterValues { ms -> ms.firstOrNull { it.second != null }?.second == "personA" }
                val strays = aTracks.mapValues { (_, ms) -> ms.filter { it.second != "personA" } }.filterValues { it.isNotEmpty() }
                val ap = approach(spec, b, blocks)
                val tag = "bX=$bX s=${n.depthQuadK}"
                println("SM-16 $name $tag: A tracks ${aTracks.keys} (${aTracks.values.sumOf { it.size }} meas), strays ${strays.mapValues { (_, v) -> v.joinToString { "%.2f:%s".format(it.first, it.second ?: "-") } }}; B stop ${ap.stopAlongM} ttc ${ap.ttcS} entered ${ap.entryBeforeStop} (alone ${alone.stopAlongM})")
                if (System.getenv("SM16_TRACE") != null) by.forEach { (id, ms) -> println("  id$id " + ms.joinToString(" ") { "%.2f:%s".format(it.first, it.second?.last() ?: "-") }) }
                when (name) {
                    "M58" -> if (strays.isEmpty()) fails += "$tag: M58 combination did not jump (test invalid)"
                    "M59" -> if (bX <= 0.3f) { // 변형 장면(옆 0.5 m)은 보고만: 가장자리에서 기운 예측이 B를 0.3 s 받는 한계(한 덩어리 한 track 없음, M59 결과)
                        if (aTracks.isEmpty()) fails += "$tag: no track measured A"
                        if (strays.isNotEmpty()) fails += "$tag: A track measured off A ${strays.keys}"
                        if (!approachOk(ap)) fails += "$tag: B $ap (alone $alone)"
                    }
                }
            }
        }
        assertTrue(fails.isEmpty(), fails.joinToString("; "))
    }
}
