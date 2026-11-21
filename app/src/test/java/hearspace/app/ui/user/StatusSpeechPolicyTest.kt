package hearspace.app.ui.user

import hearspace.app.ui.user.StatusSpeechPolicy.Status.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 합성 상태/시계로 시험. 실제 감지 또는 음성 엔진에 의존하지 않는다. */
class StatusSpeechPolicyTest {
    private fun policy() = StatusSpeechPolicy(StatusSpeechPolicy.Config(recoveryStableMs = 400, unavailableStableMs = 800))

    @Test fun `시작 전은 무음이고 준비는 한 번만`() {
        val p = policy()
        assertNull(p.update(IDLE, 0))
        assertEquals("안내를 준비합니다.", p.update(PREPARING, 10)?.text)
        assertNull(p.update(PREPARING, 1000))
    }

    @Test fun `실제 안내 상태가 안정된 뒤 시작을 읽는다`() {
        val p = policy()
        p.update(PREPARING, 0)
        assertNull(p.update(GUIDING, 100))
        assertNull(p.update(GUIDING, 499))
        assertEquals("안내를 시작합니다.", p.update(GUIDING, 500)?.text)
        assertNull(p.update(GUIDING, 900))
    }

    @Test fun `일시정지는 즉시이고 재개는 실제 출력 확인 뒤`() {
        val p = policy()
        p.update(GUIDING, 0); p.update(GUIDING, 400)
        assertEquals("안내를 일시정지합니다.", p.update(PAUSED, 500)?.text)
        assertNull(p.update(GUIDING, 600))
        assertEquals("안내를 재개합니다.", p.update(GUIDING, 1000)?.text)
    }

    @Test fun `짧은 추적 상실은 발화하지 않는다`() {
        val p = policy()
        p.update(GUIDING, 0); p.update(GUIDING, 400)
        assertNull(p.update(PREPARING, 500))
        assertNull(p.update(GUIDING, 900))
        assertNull(p.update(GUIDING, 2000))
    }

    @Test fun `안내 도중 추적 상실은 준비가 아닌 확인 불가`() {
        val p = policy()
        p.update(GUIDING, 0); p.update(GUIDING, 400)
        p.update(PREPARING, 500)
        val a = p.update(PREPARING, 1300)
        assertEquals("주변을 확인할 수 없습니다. 잠시 멈춰 주세요.", a?.text)
        assertTrue(a!!.urgent)
        assertNull(p.update(PREPARING, 10_000))
    }

    @Test fun `바닥 확인 불가도 안정화 후 한 번`() {
        val p = policy()
        assertNull(p.update(FLOOR_CHECK, 0))
        assertEquals("바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.", p.update(FLOOR_CHECK, 800)?.text)
        assertNull(p.update(FLOOR_CHECK, 900))
    }

    @Test fun `복구는 안전하다는 표현 없이 읽는다`() {
        val p = policy()
        p.update(GUIDING, 0); p.update(GUIDING, 400)
        p.update(UNAVAILABLE, 500); p.update(UNAVAILABLE, 1300)
        p.update(GUIDING, 1400)
        assertEquals("주변 확인을 재개합니다.", p.update(GUIDING, 1800)?.text)
    }

    @Test fun `안정화 중 다른 상태가 오면 이전 상태를 버린다`() {
        val p = policy()
        p.update(GUIDING, 0)
        p.update(FLOOR_CHECK, 200)
        assertEquals("안내를 일시정지합니다.", p.update(PAUSED, 300)?.text)
        assertNull(p.update(PAUSED, 5000))
    }

    @Test fun `종료와 실패는 즉시 전달된다`() {
        val p = policy()
        assertEquals("안내를 종료합니다.", p.update(ENDED, 0)?.text)
        assertNull(p.update(ENDED, 10))
        assertTrue(p.update(START_FAILED, 20)!!.urgent)
        assertTrue(p.update(STOPPED, 30)!!.urgent)
    }

    @Test fun `새 세션은 초기화되어 준비 안내를 다시 한다`() {
        val p = policy()
        p.update(GUIDING, 0); p.update(GUIDING, 400)
        p.reset()
        assertEquals("안내를 준비합니다.", p.update(PREPARING, 500)?.text)
    }

    @Test fun `시계가 역행하면 안정화 시간을 다시 잰다`() {
        val p = policy()
        p.update(GUIDING, 1000)
        assertNull(p.update(GUIDING, 100))
        assertNull(p.update(GUIDING, 499))
        assertNotNull(p.update(GUIDING, 500))
    }

    @Test fun `음성은 장애물 없음이나 안전을 보장하지 않는다`() {
        for (status in StatusSpeechPolicy.Status.entries) {
            val p = policy()
            val announcements = listOfNotNull(p.update(status, 0), p.update(status, 10_000))
            assertTrue(announcements.none { "안전합니다" in it.text || "장애물 없음" in it.text })
        }
    }
}
