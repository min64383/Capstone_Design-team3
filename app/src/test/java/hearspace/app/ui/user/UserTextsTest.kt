package hearspace.app.ui.user

import hearspace.core.types.SonifyMapping
import hearspace.core.types.SonifyMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 사용 방법 글(M45). 작동 화면 상태 변환 시험은 core `UserScreenTest`(M66에서 옮김). */
class UserTextsTest {

    @Test
    fun `사용 방법 글은 소리 방식을 따르고 무음이 안전이 아님을 알린다`() {
        val continuous = UserTexts.howTo(SonifyMode.RISK_CONTINUOUS, SonifyMapping.DISTANCE_HEIGHT)
        val pulse = UserTexts.howTo(SonifyMode.PULSE, SonifyMapping.LEGACY_RISK)
        assertTrue("높은 곳에 있는 장애물일수록 음이 높습니다" in continuous)
        assertTrue("빨라지고" in pulse)
        assertFalse("빨라지고" in continuous)
        for (t in listOf(continuous, pulse, UserTexts.howTo(SonifyMode.RISK_CONTINUOUS, SonifyMapping.LEGACY_RISK))) {
            assertTrue("소리가 나지 않는다고 앞이 안전하다는 뜻은 아닙니다" in t)
            assertTrue("가슴 하네스" in t)
        }
    }
}
