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
 * M48 합성 이동 물체(IMPROVE_SPEC §12 SM-1~SM-6). 합성 세션을 실제 재생 경로(OfflineReplay, 깊이 전달 136 ms + 느린 경로 10 ms)로 돌리고,
 * 소리를 낸 블록 시각의 참 머리·참 사람 위치로 채점한다. 합성은 관문이고 값을 고르는 데 쓰지 않는다(M33 규칙).
 */
class MovingObstacleTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    @TempDir
    lateinit var tmp: File

    /** 블록 하나: 시각(재생 시계 = 깊이 촬영과 같은 부팅 시계), 안내 출력. */
    data class Block(val tNs: Long, val g: GuidanceOutput, val snapshot: ObstacleSnapshot?)

    private fun replay(spec: SceneSpec, extra: String = ""): List<Block> {
        val g = spec.walk.gripOffsetM
        val sep = if (extra.isBlank()) "" else ", $extra"
        val overrides = """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }"""
        val dir = File(tmp, "${spec.id}_${extra.hashCode()}")
        SyntheticSessionWriter.write(spec.generate(), File(dir, "session"), "SYN")
        val out = ArrayList<Block>()
        val listener = object : ReplayListener {
            override fun onSlowStep(step: SlowStep) {}
            override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) { out += Block(tNs, g, snapshot) }
        }
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(10f)).run(File(dir, "session"), listener)
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
            if (!explained) s += (b.tNs - a.tNs) / 1e9f
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
}
