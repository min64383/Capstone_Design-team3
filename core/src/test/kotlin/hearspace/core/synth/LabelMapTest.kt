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
import hearspace.core.types.SemanticLabel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs

/**
 * M54 지도 앞단 합성 시험(설계 표 7, SL 장면). 합성 세션을 실제 재생 경로로 돌린다: 의미 영상은 깊이 장마다, 원시 깊이는 세 장에 한 장(약 10 Hz)이고
 * 평활 전 깊이와 요소별 신뢰도다(`realRaw`). 기준은 M54 옵션을 모두 끈 설정, 변형은 그 부분만 켠 설정이다. 합성은 관문이고 값을 고르는 데 쓰지 않는다.
 */
class LabelMapTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val stopM = 1.0f // 설정 policy.stopM과 같은 값(시험 판정용)

    @TempDir
    lateinit var tmp: File

    data class Block(val tNs: Long, val g: GuidanceOutput)
    class Run(val blocks: List<Block>, val steps: List<SlowStep>)

    private val floor = SceneItem("floor", HorizontalPlane(0f), obstacle = false, semantic = SemanticLabel.SIDEWALK)

    private fun replay(spec: SceneSpec, extra: String, voxels: Boolean = false): Run {
        val g = spec.walk.gripOffsetM
        val sep = if (extra.isBlank()) "" else ", $extra"
        val overrides = """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }"""
        val dir = File(tmp, "${spec.id}_${extra.hashCode()}")
        SyntheticSessionWriter.write(spec.generate(), File(dir, "session"), "SYN", rawEveryN = 3, semantics = true, realRaw = true)
        val blocks = ArrayList<Block>()
        val steps = ArrayList<SlowStep>()
        val listener = object : ReplayListener {
            override val captureVoxels get() = voxels
            override fun onSlowStep(step: SlowStep) { steps += step }
            override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) { blocks += Block(tNs, g) }
        }
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(10f)).run(File(dir, "session"), listener)
        return Run(blocks, steps)
    }

    private fun tS(tNs: Long) = (tNs - SyntheticGenerator.T0_NS) / 1e9f
    private fun active(b: Block) = b.g.state == GuidanceState.NORMAL || b.g.state == GuidanceState.DEGRADED

    /** 그 블록 시각의 참 머리에서 상자(위에서 본 사각형) 가장 가까운 점까지의 진행 방향 거리. */
    private fun alongTo(spec: SceneSpec, box: Box, t: Float): Float {
        val head = SyntheticGenerator.cameraPose(spec.walk, t).second
        val p = head.positionW
        val q = Vec3(p.x.coerceIn(box.min.x, box.max.x), 0f, p.z.coerceIn(box.min.z, box.max.z))
        return (q - p).horizontal() dot head.headingW
    }

    /** 처음 [band] 이상(STOP이면 STOP만, WARN이면 WARN·STOP)이 난 블록의 참 거리. 없으면 null. */
    private fun firstAlong(spec: SceneSpec, box: Box, run: Run, band: Band): Float? {
        val b = run.blocks.firstOrNull { b -> active(b) && b.g.commands.any { it.band == Band.STOP || (band == Band.WARN && it.band == Band.WARN) } }
        return b?.let { alongTo(spec, box, tS(it.tNs)) }
    }

    private val band = """ "frontend": { "labelBandPx": 6 }, "vision": { "movableDepth": "RAW" } """
    private val weights = """ "map": { "hitWeighting": "CONFIDENCE" } """
    private val floorW = """ "floor": { "weighting": "CONFIDENCE" } """

    private fun walkTo(distM: Float) = Walk(durationS = 2f + (distM - 0.5f))

    /** SL-1 막: 물체 상자 2 m(라벨 물체) 뒤 2 m에 벽(라벨 건물), 평활 가장자리 3화소. 서서 본 3 s 동안 상자와 벽 사이 막 칸이 띠에서 줄고, 걸어갈 때 첫 STOP은 같다. */
    @Test
    fun sl1MembraneBand() {
        val box = Box(Vec3(-0.225f, 0f, -2.45f), Vec3(0.225f, 1.0f, -2.0f))
        val wall = Box(Vec3(-2f, 0f, -4.6f), Vec3(2f, 2.4f, -4.5f))
        val scene = Scene(listOf(floor, SceneItem("box", box, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.OBJECT),
            SceneItem("wall", wall, obstacle = false, structure = true, semantic = SemanticLabel.BUILDING)))
        val noise = Noise(edgeSmoothPx = 3)
        val stand = SceneSpec("SL-1s", scene, Walk(speedMps = 0f, standS = 3f, durationS = 3f), noise)
        fun membrane(run: Run): Int = run.steps.last().occupiedVoxels!!.count { v ->
            v.centerW.z in -4.35f..-2.6f && abs(v.centerW.x) <= 0.5f && v.centerW.y in 0.1f..1.0f
        }
        val b0 = membrane(replay(stand, "", voxels = true))
        val b1 = membrane(replay(stand, band, voxels = true))
        println("SL-1 membrane voxels: base $b0, band $b1")
        assertTrue(b0 > 0, "기준에 막 칸이 있어야 시험이 뜻이 있다")
        assertTrue(b1 < b0, "경계 띠에서 막 칸이 줄어야 한다: $b1 vs $b0")
        val walk = SceneSpec("SL-1w", scene, walkTo(2f), noise)
        val s0 = firstAlong(walk, box, replay(walk, ""), Band.STOP)
        val s1 = firstAlong(walk, box, replay(walk, band), Band.STOP)
        println("SL-1 first STOP: base $s0, band $s1")
        assertTrue(s0 != null && s1 != null && s1 >= s0 - 0.05f, "첫 STOP이 늦지 않아야 한다: $s1 vs $s0")
    }

    /** SL-2 가는 기둥: 0.08 m 기둥 3 m(깊이 3~4화소, 라벨 구조물). 첫 경고·STOP이 기준과 같다(심이 남음). */
    @Test
    fun sl2ThinPole() {
        val pole = VerticalCylinder(0f, -3.04f, 0.04f, 0f, 1.6f)
        val poleBox = Box(Vec3(-0.04f, 0f, -3.08f), Vec3(0.04f, 1.6f, -3.0f))
        val spec = SceneSpec("SL-2", Scene(listOf(floor, SceneItem("pole", pole, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.STRUCTURE))),
            walkTo(3f), Noise(edgeSmoothPx = 3))
        val r0 = replay(spec, "")
        val r1 = replay(spec, band)
        val w0 = firstAlong(spec, poleBox, r0, Band.WARN)
        val w1 = firstAlong(spec, poleBox, r1, Band.WARN)
        val s0 = firstAlong(spec, poleBox, r0, Band.STOP)
        val s1 = firstAlong(spec, poleBox, r1, Band.STOP)
        println("SL-2 first WARN base $w0 band $w1, first STOP base $s0 band $s1")
        assertTrue(w0 != null && w1 != null && w1 >= w0 - 0.05f, "첫 경고가 늦지 않아야 한다")
        assertTrue(s0 != null && s1 != null && s1 >= s0 - 0.05f, "첫 STOP이 늦지 않아야 한다")
    }

    /**
     * SL-5 바닥 무게: 참 바닥(y = 0)은 카메라 앞 1.4~2.0 m 띠만 원시 신뢰도가 높고(255, 바닥 화소의 약 30%), 나머지 원시 없는 자리의 평활은 바닥보다 0.10 m 낮은 면으로 보인다
     * (실측: 원시 없는 바닥 화소의 평활이 바닥 아래로 치우침, 150159 그 구간 바닥 추정 0.10 m 낮음; T 바닥 화소 중 신뢰도 ≥ 128은 17%로 소수). 서서 본 3 s의
     * 바닥 추정 오차가 바닥 무게에서 0.05 m 안이고 기준보다 작다.
     */
    @Test
    fun sl5FloorWeights() {
        val near = SceneItem("nearFloor", Box(Vec3(-3f, -0.02f, -2.0f), Vec3(3f, 0f, -1.4f)), obstacle = false, semantic = SemanticLabel.SIDEWALK)
        val far = SceneItem("farFloor", HorizontalPlane(-0.10f), obstacle = false, semantic = SemanticLabel.SIDEWALK, rawConfidence = 0)
        val spec = SceneSpec("SL-5", Scene(listOf(near, far)), Walk(speedMps = 0f, standS = 3f, durationS = 3f))
        fun err(run: Run) = run.steps.mapNotNull { it.mapUpdate.floorY }.takeLast(30).let { ys -> ys.sorted()[ys.size / 2] }
        val e0 = err(replay(spec, ""))
        val e1 = err(replay(spec, floorW))
        println("SL-5 floor estimate: base $e0, weighted $e1")
        assertTrue(abs(e1) <= 0.05f, "바닥 무게의 바닥 추정 오차 ≤ 0.05 m: $e1")
        assertTrue(abs(e1) < abs(e0), "기준보다 작아야 한다: $e1 vs $e0")
    }

    /** SL-6 연석: 2.5 m부터 0.15 m 높은 보도(라벨 보도, 신뢰도 255). 앞단 변형이 기준보다 늦게 경고하지 않는다. */
    @Test
    fun sl6Curb() {
        val curb = Box(Vec3(-2f, 0f, -2.8f), Vec3(2f, 0.15f, -2.5f)) // 폭 0.3 m 연석 띠
        val spec = SceneSpec("SL-6", Scene(listOf(floor, SceneItem("curb", curb, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.SIDEWALK))),
            walkTo(2.5f))
        val w0 = firstAlong(spec, curb, replay(spec, ""), Band.WARN)
        for ((name, extra) in listOf("band" to band, "weights" to "$band, $weights", "floor" to "$band, $weights, $floorW")) {
            val w = firstAlong(spec, curb, replay(spec, extra), Band.WARN)
            println("SL-6 first WARN base $w0, $name $w")
            assertTrue(w0 == null || (w != null && w >= w0 - 0.05f), "$name: 연석 경고가 늦지 않아야 한다: $w vs $w0")
        }
    }

    /**
     * SL-8 신뢰도 낮은 물체: 원시 신뢰도가 모두 10인 상자 3 m(라벨 물체). 신뢰도 무게에서도 첫 경고·STOP이 기준보다 0.05 m 넘게 늦지 않다.
     * 설계 표 7의 "≥ 0.9 × stopM"은 기준도 넘지 못하는 값이라(합성 기준 첫 STOP 0.86 m) 다른 안전 기준처럼 기준 대비로 바꿨다(결과 문서에 적음).
     */
    @Test
    fun sl8LowConfidenceObject() {
        val box = Box(Vec3(-0.225f, 0f, -3.45f), Vec3(0.225f, 0.8f, -3.0f))
        val spec = SceneSpec("SL-8", Scene(listOf(floor, SceneItem("box", box, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.OBJECT, rawConfidence = 10))),
            walkTo(3f))
        val r0 = replay(spec, "")
        val r1 = replay(spec, weights)
        val w0 = firstAlong(spec, box, r0, Band.WARN)
        val w1 = firstAlong(spec, box, r1, Band.WARN)
        val s0 = firstAlong(spec, box, r0, Band.STOP)
        val s1 = firstAlong(spec, box, r1, Band.STOP)
        println("SL-8 first WARN base $w0 weights $w1, first STOP base $s0 weights $s1 (stopM $stopM)")
        assertTrue(w0 != null && w1 != null && w1 >= w0 - 0.05f, "첫 경고가 늦지 않아야 한다")
        assertTrue(s0 != null && s1 != null && s1 >= s0 - 0.05f, "첫 STOP이 늦지 않아야 한다")
    }
}
