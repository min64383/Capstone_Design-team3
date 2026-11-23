package hearspace.app.relay

import hearspace.app.ui.user.StatusSpeechPolicy
import hearspace.app.ui.user.StatusSpeechPolicy.Status
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 말 소리 모음 문장 목록(M67 설계 3.5절). 규칙 코드는 고치지 않으므로(팀원 브랜치와 충돌을 피함) 여기서 목록과 코드를 맞춘다.
 * 상태 말은 정책을 돌려 나오는 문장을, 상황 말·앱 시작 문장은 원본 파일에 같은 글자가 있는지 본다.
 */
class RelayTextsTest {
    private fun asset(name: String) = File("src/main/assets/config/$name").readText()
    private val config = ConfigLoader.load(asset("default.json"),
        listOf("risk-continuous.override.json", "harness.override.json", "situation.override.json", "semantic-combo.override.json").map(::asset))

    @Test
    fun `목록은 64개이고 겹치지 않는다`() {
        val all = RelayTexts.all(config.situation)
        assertEquals(64, all.size)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `상태 말 정책이 내는 문장 10가지가 모두 목록에 있다`() {
        val p = StatusSpeechPolicy(StatusSpeechPolicy.Config(recoveryStableMs = 0, unavailableStableMs = 0))
        // 준비 → 시작 → 일시정지 → 재개 → (안내 뒤 준비 = 확인 불가) → 주변 확인 재개 → 바닥 확인 → 시작 실패 → 오류 멈춤 → 종료 → 시작 전
        val said = listOf(Status.PREPARING, Status.GUIDING, Status.PAUSED, Status.GUIDING, Status.PREPARING, Status.GUIDING,
            Status.FLOOR_CHECK, Status.START_FAILED, Status.STOPPED, Status.ENDED, Status.IDLE)
            .mapIndexedNotNull { i, st -> p.update(st, i.toLong())?.text }
        assertEquals(10, said.toSet().size, "every branch of the policy")
        assertTrue(RelayTexts.STATUS.containsAll(said), "missing: ${said - RelayTexts.STATUS.toSet()}")
    }

    @Test
    fun `앱 시작 문장과 상황 말은 원본 코드에 같은 글자로 있다`() {
        val activity = File("src/main/java/hearspace/app/ui/user/UserModeActivity.kt").readText()
        assertTrue("\"${RelayTexts.STATUS[0]}\"" in activity)
        val situations = File("../core/src/main/kotlin/hearspace/core/situation/Situations.kt").readText()
        for (t in RelayTexts.SITUATION) assertTrue("\"$t\"" in situations, t)
        val withDistance = RelayTexts.all(config.situation).filter { it.endsWith("미터") }.map { it.substringBefore(", ") + ", " }.toSet()
        assertEquals(5, withDistance.size)
        for (prefix in withDistance) assertTrue("\"$prefix\${meters(" in situations, prefix)
    }
}
