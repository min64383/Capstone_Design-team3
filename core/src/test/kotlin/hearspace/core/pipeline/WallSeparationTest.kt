package hearspace.core.pipeline

import hearspace.core.geometry.Vec3
import hearspace.core.synth.Box
import hearspace.core.synth.HorizontalPlane
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import hearspace.core.types.Obstacle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 벽 칸과 나머지를 따로 군집(IMPROVE_SPEC §6 C2, `cluster.separateWalls`). 기준선은 같은 장면을 끈 채로 돌린다.
 * 앞단(`frontend.enabled`)·영역 분할·인스턴스 지도는 끄고 지도는 HITS로 고정해 C2만 본다(`-PtestOverrides`로 다른 설정을 켠 실행에서도).
 */
class WallSeparationTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val off = """ "frontend": { "enabled": false, "segment": "NONE" }, "map": { "mode": "HITS", "instances": false }, "cluster": { "separateWalls": false } """
    private val on = """ "frontend": { "enabled": false, "planes": "RANSAC", "segment": "NONE" }, "map": { "mode": "HITS", "instances": false }, "cluster": { "separateWalls": true } """
    private val config = ConfigLoader.load(base)

    /** 깊이 한 장마다 장애물 목록(진행 방향은 참 머리 방향). */
    private fun run(spec: SceneSpec, overrides: String): List<List<Obstacle>> {
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, $overrides }"""))
        return spec.generate().frames.mapNotNull { f -> f.depth?.let { slow.process(it, f.truthHead.headingW).obstacles } }
    }

    @Test
    fun `a box just in front of an end wall is not merged with the wall when walls are clustered separately`() {
        // 폭 1.04 m 복도, 끝 벽 앞면 z −4.0, 그 0.12 m 앞에 상자(앞면 z −3.6, 높이 0.6). 기준선은 거리만 보므로 상자 윗면 칸과 벽 칸이
        // eps(0.15 m) 안이라 한 물체가 된다. 상자 1 m 앞까지 걷는다
        val box = SceneItem("box", Box(Vec3(-0.2f, 0f, -3.88f), Vec3(0.2f, 0.6f, -3.6f)), obstacle = true, expectedClass = HeightClass.FLOOR)
        val spec = SceneSpec("box-before-end-wall", Scene(Scenes.corridorE().items + box), Walk(durationS = 2f + 2.6f))
        fun merged(steps: List<List<Obstacle>>) = steps.count { s -> s.any { it.aabbMaxW.z > -3.7f && it.aabbMinW.z < -3.97f } }
        val baseline = merged(run(spec, off))
        val separated = merged(run(spec, on))
        println("box-before-end-wall merged frames: baseline $baseline, separate walls $separated")
        assertTrue(baseline > 0, "baseline should merge box and end wall: $baseline")
        assertTrue(separated == 0, "separate walls: $separated")
    }

    @Test
    fun `an end wall ahead is still an obstacle - walls are separated, not removed`() {
        // 끝 벽 앞면 z −4.0까지 걸어가 1 m 앞에서 선다(시작 카메라 z 0, 2 s 정지 후 1 m/s)
        val spec = SceneSpec("end-wall", Scenes.corridorE(), Walk(durationS = 2f + 3f))
        fun wallAhead(steps: List<List<Obstacle>>) = steps.takeLast(10).count { s -> s.any { it.aabbMinW.z < -3.9f && it.aabbMaxW.z > -4.2f } }
        val baseline = wallAhead(run(spec, off))
        val separated = wallAhead(run(spec, on))
        println("end wall seen as obstacle in the last 10 depth frames: baseline $baseline, separate walls $separated")
        assertTrue(baseline >= 8 && separated >= baseline, "baseline $baseline separate $separated")
    }

    @Test
    fun `a box touching a side wall stays one whole obstacle apart from the wall - regression`() {
        // 폭 1.04 m 복도, 오른쪽 벽 안쪽 면 x 0.52에 붙은 상자(x 0.12~0.52, 높이 0.6, 앞면 z −3.0). 상자 1 m 앞까지 걷는다
        val box = SceneItem("box", Box(Vec3(0.12f, 0f, -3.3f), Vec3(0.52f, 0.6f, -3f)), obstacle = true, expectedClass = HeightClass.FLOOR)
        val scene = Scene(
            listOf(
                SceneItem("floor", HorizontalPlane(0f), obstacle = false),
                Scenes.wall(0.52f, "wall_right"),
                Scenes.wall(-0.52f, "wall_left"),
                box,
            ),
        )
        val spec = SceneSpec("box-at-wall", scene, Walk(durationS = 2f + 2f))
        // 상자 자리와 겹치는 장애물의 x 범위(통로 폭 ±0.4 m 안). 기준선도 상자를 통째로 남긴다(가장자리 구조물 규칙은 진행 방향으로 0.8 m
        // 넘게 이어진 군집만 뺀다). 벽을 따로 묶어도 상자가 벽에 끌려가거나 잘리지 않는지 보는 회귀 확인
        fun boxX(steps: List<List<Obstacle>>) = steps.takeLast(10).map { s ->
            s.filter { it.aabbMaxW.z > -3.3f && it.aabbMinW.z < -3f && it.aabbMaxW.x > 0.12f }
        }
        val baseline = boxX(run(spec, off))
        val separated = boxX(run(spec, on))
        println("box-at-wall x ranges: baseline ${baseline.flatten().map { it.aabbMinW.x to it.aabbMaxW.x }.distinct()}, " +
            "separate walls ${separated.flatten().map { it.aabbMinW.x to it.aabbMaxW.x }.distinct()}")
        assertTrue(separated.all { it.isNotEmpty() }, "box obstacle in the last frames: ${separated.map { it.size }}")
        for (s in separated.flatten()) assertTrue(s.aabbMaxW.z - s.aabbMinW.z < 0.8f, "box merged with the wall: ${s.aabbMinW}..${s.aabbMaxW}")
        val inner = config.corridor.widthM / 2 - config.corridor.edgeInnerM // 가장자리 구역 폭
        assertTrue(separated.flatten().all { it.aabbMaxW.x >= config.corridor.widthM / 2 - inner / 2 }, "outer part of the box kept")
    }
}
