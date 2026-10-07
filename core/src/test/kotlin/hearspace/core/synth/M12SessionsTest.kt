package hearspace.core.synth

import hearspace.core.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M12.0 오차 분해 분석 도구(`tools/analysis/error_decomp.py`)의 자체 점검용 합성 세션을 `build/test-output/m12/<변형>/`에 쓴다.
 * 알고 넣은 오차(깊이 편향·자세 점프·누적 드리프트)를 도구가 기대 방향·크기로 읽는지 `test_analysis.py`가 확인한다(IMPROVE_SPEC §11.1).
 */
class M12SessionsTest {
    private val root = File(System.getProperty("hearspace.testOutput"), "m12")

    /** E01: 거치 정지 12 s(수렴 판정은 정지 10 s 이상). */
    private val stand = Walk(standS = 100f, durationS = 12f)

    /** E02: 2 s 정지 후 3 m. */
    private val oneWay = Walk(durationS = 2f + 3f)

    /** E03: 2 s 정지, 3 m, 제자리 180°(2 s), 3 m, 1 s 정지(돌아선 방향 그대로). */
    private val roundTrip = Walk(legM = 3f, turnS = 2f, legCount = 2, durationS = 2f + 3f + 2f + 3f + 1f)

    private val variants = mapOf(
        "e01_clean" to SceneSpec("E01", Scenes.corridorE(2f), stand, depthEveryNFrames = 3),
        "e01_scale10" to SceneSpec("E01", Scenes.corridorE(2f), stand, Noise(depthScaleBias = 0.1f), depthEveryNFrames = 3),
        "e02_clean" to SceneSpec("E02", Scenes.corridorE(), oneWay, depthEveryNFrames = 3),
        "e02_jump" to SceneSpec("E02", Scenes.corridorE(), oneWay, Noise(poseJumps = listOf(PoseJump(4.5f, Vec3(0.3f, 0f, 0.2f), 5f))), depthEveryNFrames = 3),
        "e03_clean" to SceneSpec("E03", Scenes.corridorE(), roundTrip, depthEveryNFrames = 3),
        "e03_drift1" to SceneSpec("E03", Scenes.corridorE(), roundTrip, Noise(yawDriftDegPerM = 1f), depthEveryNFrames = 3),
        "e03_drift2" to SceneSpec("E03", Scenes.corridorE(), roundTrip, Noise(yawDriftDegPerM = 2f), depthEveryNFrames = 3),
    )

    @Test
    fun `write error decomposition sessions for analysis tools`() {
        for ((name, spec) in variants) {
            val dir = File(root, name)
            dir.deleteRecursively()
            SyntheticSessionWriter.write(spec.generate(), dir, spec.id)
            val truth = File(dir, "annotations/obstacles.json").readText()
            assertTrue("\"structure\"" in truth && "\"version\": 2" in truth, name)
        }
    }
}
