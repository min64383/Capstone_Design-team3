package hearspace.core.pipeline

import hearspace.core.geometry.Vec3
import hearspace.core.synth.Box
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** M20 대표점 칸 진단(`cluster_debug.csv`의 repVoxel*). */
class RepVoxelDiagTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    /** 상자(앞면 z −2.0, 높이 0.6) 0.6 m 앞까지 걷는 동안 느린 경로마다 군집 기록. */
    private fun run(): List<ClusterDebug> {
        val box = SceneItem("box", Box(Vec3(-0.2f, 0f, -2.3f), Vec3(0.2f, 0.6f, -2.0f)), obstacle = true, expectedClass = HeightClass.FLOOR)
        val spec = SceneSpec("box-approach", Scene(Scenes.corridorE().items + box), Walk(durationS = 2f + 1.4f))
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] } }"""))
        return spec.generate().frames.mapNotNull { f -> f.depth?.let { slow.process(it, f.truthHead.headingW); slow.lastClusterDebug } }.flatten()
    }

    @Test
    fun `every cluster records the state, age and hits of its representative voxel`() {
        val rows = run()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.repVoxelState.isNotEmpty() && it.repVoxelHits >= 1 && it.repVoxelAgeMs >= 0f }, "missing rep voxel fields")
        // 이번 장에 관측됨 ⇔ 나이 0
        assertTrue(rows.all { (it.repVoxelState == "HIT") == (it.repVoxelAgeMs == 0f) }, "HIT must mean age 0")
        val states = rows.groupingBy { it.repVoxelState }.eachCount()
        println("rep voxel states while approaching a box: $states")
        assertTrue(states.getOrDefault("HIT", 0) > 0 && states.keys.any { it != "HIT" }, "expected both fresh and older rep voxels: $states")
    }
}
