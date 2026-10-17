package hearspace.core.synth

import hearspace.core.replay.MapEvalWriter
import hearspace.core.replay.OfflineReplay
import hearspace.core.replay.RunLogWriter
import hearspace.core.types.ConfigLoader
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 점유 격자 모형의 장면 단위 예측(M33 설계 5.2절 ①): 정답 깊이(열화 s = 0)에서는 3상태 복셀 지도가 관측한 표면을 빈 곳으로 지우지 않는다.
 * M32 광선 판정은 같은 장면에서 표면을 지웠다(합성 200장면 평균 0.436). 칸이 투영된 화소로 판정하는 M33 규칙(성질 ①·④)으로 확인한다.
 */
class TriFrameSceneTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    private fun erased(overrides: String, dir: File): Double {
        val config = ConfigLoader.load(base, overrides)
        val seed = 7L
        val rs = RandomScenes.generate(seed)
        val session = File(dir, "session")
        SyntheticSessionWriter.write(SyntheticGenerator.generate(rs.scene, rs.walk, RandomScenes.noise(0f, seed)), session, "R%04d".format(seed), rawEveryN = 3)
        OfflineReplay(config, OfflineReplay.fixed(10f)).run(
            session, MapEvalWriter(RunLogWriter(dir, session, overrides, "fixed 10 ms"), dir, session, config, everyN = 5, align = rs.alignment(), sceneHeightM = 2.5f),
        )
        val j = MiniJson.parse(File(dir, MapEvalWriter.SCENE_FILE).readText()) as JsonObject
        return (j.fields["erasedRatio"] as JsonNumber).value
    }

    @Test
    fun `exact depth does not erase observed surfaces`(@TempDir dir: File) {
        val raw = """ "depth": { "source": "RAW", "minConfidence": 1 }, "frontend": { "enabled": false, "rgbGuide": "NONE" } """
        val m33 = erased("""{ "map": { "mode": "TRISTATE", "triSameFrame": true, "triFree": "PROJECTION", "triFreeRule": "EVIDENCE" }, $raw }""", File(dir, "m33"))
        assertTrue(m33 <= 0.05, "M33 erased surface $m33")
    }
}
