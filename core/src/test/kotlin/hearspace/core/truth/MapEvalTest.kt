package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.replay.MapEvalWriter
import hearspace.core.replay.OfflineReplay
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticSessionWriter
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 지도 평가: 정답 구조물의 두께·앞 치우침(M13, `map_eval.json`). */
class MapEvalTest {
    private fun wall(name: String, min: Vec3, max: Vec3) = TruthObstacle(name, HeightClass.FLOOR, TruthKind.STRUCTURE, min, max, null)

    @Test
    fun `thickness counts voxels along the wall normal and front offset is measured toward the walker`() {
        val truth = GroundTruth(
            2, "E02", false, "",
            listOf(wall("right", Vec3(0.52f, 0f, 0f), Vec3(0.62f, 2.4f, 4f)), wall("end", Vec3(-0.6f, 0f, 4f), Vec3(0.6f, 2.4f, 4.1f))),
        )
        val v = 0.05f
        val voxels = ArrayList<Vec3>()
        for (z in 10 until 70) for (y in 8 until 34) {
            // 오른쪽 벽: 두 칸(앞면 0.52보다 0.045·0.005 앞)
            voxels += Vec3(0.475f, y * v + 0.025f, z * v + 0.025f)
            voxels += Vec3(0.525f, y * v + 0.025f, z * v + 0.025f)
        }
        for (x in -6 until 6) for (y in 8 until 34) for (k in 0 until 4) voxels += Vec3(x * v + 0.025f, y * v + 0.025f, 3.825f + k * v) // 끝 벽: 네 칸, 0.175 앞부터
        val w = MapEval.walls(voxels, truth, v).associateBy { it.name }
        assertEquals(0.1f, w.getValue("right").thicknessP50, 1e-6f)
        assertEquals(0.045f, w.getValue("right").frontOffsetP50, 1e-4f)
        assertEquals(0.2f, w.getValue("end").thicknessP50, 1e-6f)
        assertEquals(0.175f, w.getValue("end").frontOffsetP50, 1e-4f)
    }

    @Test
    fun `replay of a synthetic corridor writes thin walls in place`(@TempDir dir: File) {
        val base = File(System.getProperty("hearspace.defaultConfig")).readText()
        val spec = SceneSpec("E02", Scenes.corridorE(), Walk(durationS = 2f + 3f), depthEveryNFrames = 3)
        val session = SyntheticSessionWriter.write(spec.generate(), File(dir, "session"), spec.id)
        val g = spec.walk.gripOffsetM
        val overrides = """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] } }"""
        val out = File(dir, "run")
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(10f)).run(session, out, overrides)
        val final = (MiniJson.parse(File(out, MapEvalWriter.FILE).readText()) as JsonObject).fields.getValue("final") as JsonArray
        val walls = final.items.map { it as JsonObject }.associate { (it.fields.getValue("name") as hearspace.core.types.JsonString).value to it.fields }
        assertTrue(walls.keys.containsAll(listOf("wall_left", "wall_right")), "walls ${walls.keys}")
        for ((name, f) in walls) {
            val t = (f.getValue("thicknessP50M") as JsonNumber).value
            val o = (f.getValue("frontOffsetP50M") as JsonNumber).value
            assertTrue(t <= 0.1 && o in -0.06..0.06, "$name thickness $t offset $o")
        }
    }
}
