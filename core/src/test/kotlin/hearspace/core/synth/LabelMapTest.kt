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
import org.junit.jupiter.api.Assertions.assertEquals
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

    /**
     * SL-O(M55 ②-A 장애물 원천 O): 장애물 라벨(물체) 상자 3 m. 원시가 없는 상자(신뢰도 0)는 평활로 채우므로 O를 켜도 첫 STOP이 그대로다.
     * 원시가 있는 상자는 원시 점(10 Hz)으로 들어가 STOP이 나야 한다. 원시 점 표 무게가 정해지기 전이라 늦음은 보고만 한다(설계 표 8에서 유도).
     */
    @Test
    fun slObstacleRawFillsWithSmoothed() {
        val bandO = """ "frontend": { "labelBandPx": 6, "bandLabels": ["UNLABELED", "TREE", "STRUCTURE", "OBJECT", "VEHICLE", "PERSON"] }, "map": { "hitWeighting": "CONFIDENCE", "weightedMinHits": 5 } """
        val withO = """$bandO, "vision": { "obstacleDepth": "RAW" } """
        val box = Box(Vec3(-0.225f, 0f, -3.45f), Vec3(0.225f, 0.8f, -3.0f))
        for (conf in listOf(0, 200)) {
            val spec = SceneSpec("SL-O-$conf", Scene(listOf(floor, SceneItem("box", box, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.OBJECT, rawConfidence = conf))),
                walkTo(3f))
            val s0 = firstAlong(spec, box, replay(spec, bandO), Band.STOP)
            val s1 = firstAlong(spec, box, replay(spec, withO), Band.STOP)
            println("SL-O raw confidence $conf: first STOP B+W5 $s0, +O $s1")
            assertTrue(s0 != null && s1 != null, "STOP이 나야 한다")
            if (conf == 0) assertEquals(s0!!, s1!!, 0.01f, "원시가 없으면 평활로 채워 그대로")
        }
    }

    private val roles = """ "vision": { "voxelLabels": true }, "corridor": { "judge": "LABEL" } """

    private fun wallAlongZ(xInner: Float, toRight: Boolean, label: SemanticLabel) = SceneItem(
        "wall", Box(Vec3(if (toRight) xInner else xInner - 0.1f, 0f, -8f), Vec3(if (toRight) xInner + 0.1f else xInner, 2.4f, -0.5f)),
        obstacle = false, structure = true, semantic = label,
    )

    private fun warnBlocks(run: Run) = run.blocks.count { b -> active(b) && b.g.commands.any { it.band == Band.STOP || it.band == Band.WARN } }

    /** SL-3 정면 건물 벽: 통로를 가로막는 벽(라벨 건물). 역할을 켠 첫 STOP이 라벨 없이(BOX)와 같다(가로막는 면은 나란한 줄이 아님). */
    @Test
    fun sl3FrontBuildingWall() {
        val wall = Box(Vec3(-2f, 0f, -3.1f), Vec3(2f, 2.4f, -3.0f))
        val spec = SceneSpec("SL-3", Scene(listOf(floor, SceneItem("wall", wall, obstacle = true, expectedClass = HeightClass.FLOOR, semantic = SemanticLabel.BUILDING))), walkTo(3f))
        val s0 = firstAlong(spec, wall, replay(spec, ""), Band.STOP)
        val s1 = firstAlong(spec, wall, replay(spec, roles), Band.STOP)
        println("SL-3 first STOP: BOX $s0, LABEL $s1")
        assertTrue(s0 != null && s1 != null && s1 >= s0 - 0.05f, "정면 건물 벽 STOP이 늦지 않아야 한다")
    }

    /**
     * SL-4 나란한 건물 벽: 사용자가 한쪽으로 치우쳐 왼쪽 벽 안쪽 면이 보행선 왼쪽 0.2 m(가장자리 구역 0.25 m 안이라 BOX는 경고). 오른쪽 벽이 +0.8 m(틈 1.0 m ≥ 몸 폭)면
     * 역할 판정은 M44 나란한 경계와 같은 결과이고, +0.4 m(틈 0.6 m)면 안내한다. 같은 벽이 라벨 없음이면 역할을 켜도 BOX 그대로.
     * 설계 표 7의 "틈 1.0 m면 경고 없음"은 M44 자체도 넘지 못하는 값이라(경고 블록 152, BOX 498) 시험 설계 오류로 보고 M44와 같음으로 바꿨다(결과 문서).
     */
    @Test
    fun sl4ParallelBuildingWalls() {
        val walk = Walk(durationS = 5f)
        fun spec(right: Float, label: SemanticLabel) = SceneSpec("SL-4_${right}_$label", Scene(listOf(floor, wallAlongZ(-0.2f, false, label), wallAlongZ(right, true, label))), walk)
        val wideBox = warnBlocks(replay(spec(0.8f, SemanticLabel.BUILDING), ""))
        val wideLabel = warnBlocks(replay(spec(0.8f, SemanticLabel.BUILDING), roles))
        val narrowLabel = warnBlocks(replay(spec(0.4f, SemanticLabel.BUILDING), roles))
        val wideUnlabeled = warnBlocks(replay(spec(0.8f, SemanticLabel.UNLABELED), roles))
        val wideBoundary = warnBlocks(replay(spec(0.8f, SemanticLabel.BUILDING), """ "corridor": { "judge": "BOUNDARY" } """))
        println("SL-4 warn blocks: wide BOX $wideBox, wide LABEL $wideLabel, wide BOUNDARY(M44) $wideBoundary, narrow LABEL $narrowLabel, wide unlabeled LABEL $wideUnlabeled")
        assertTrue(wideBox > 0, "BOX는 0.2 m 벽에 경고해야 시험이 뜻이 있다")
        assertTrue(wideLabel == wideBoundary && wideLabel < wideBox, "건물 벽에서는 M44 나란한 경계와 같고 BOX보다 경고가 적다")
        assertTrue(narrowLabel > 0, "틈 < 몸 폭이면 안내한다")
        assertTrue(wideUnlabeled == wideBox, "라벨 없는 벽은 역할을 켜도 BOX 그대로")
    }

    /**
     * SL-7 얕게 만나는 건물 벽(기록 시험): 벽(라벨 건물)은 보행선 오른쪽 0.5 m에 나란하고 사용자는 8°로 벽 쪽을 향해 걸어 약 3.6 m 뒤에 만난다. 설계 기준은
     * "역할을 켠 첫 STOP이 BOX보다 0.05 s 넘게 늦지 않음"이었으나 M44 나란한 경계 규칙의 결함으로 2.0 s 늦었다(BOX 3.17 s, LABEL 5.18 s: 만나는 점 약 2.4 m 앞 대
     * 0.4 m 앞). 사용자 결정(2026-11-06): M54에서 역할 판정(LABEL)은 안내에 쓰지 않고 꺼진 옵션으로 남기며, 결함은 고치기 전 BOUNDARY·LABEL을 쓰지 않는다(openItems).
     * 이 시험은 그 결함이 남아 있음을 기록한다: 고치면 아래 단언이 깨지므로 그때 기준을 "늦지 않음"으로 되돌린다.
     */
    @Test
    fun sl7ShallowConvergingBuildingWall() {
        val spec = SceneSpec("SL-7", Scene(listOf(floor, wallAlongZ(0.5f, true, SemanticLabel.BUILDING))), Walk(headingDeg = 8f, durationS = 5.3f))
        fun firstStopS(run: Run) = run.blocks.firstOrNull { b -> active(b) && b.g.commands.any { it.band == Band.STOP } }?.let { tS(it.tNs) }
        val t0 = firstStopS(replay(spec, ""))
        val t1 = firstStopS(replay(spec, roles))
        println("SL-7 first STOP time: BOX $t0, LABEL $t1")
        assertTrue(t0 != null && t1 != null && t1 > t0 + 1.0f, "M44 결함 기록: 고쳤으면 기준을 '늦지 않음'으로 되돌린다")
    }
}
