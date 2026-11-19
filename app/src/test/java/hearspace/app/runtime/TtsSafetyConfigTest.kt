package hearspace.app.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TtsSafetyConfigTest {
    @Test fun `기본 안전 한계는 유효하다`() {
        val c = TtsSafetyConfig()
        assertTrue(c.initTimeoutMs > 0)
        assertTrue(c.startTimeoutMs > 0)
        assertTrue(c.maxCompletionMs >= c.completionBaseMs)
    }
    @Test fun `무한 대기나 음수 대기 설정은 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { TtsSafetyConfig(initTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { TtsSafetyConfig(startTimeoutMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { TtsSafetyConfig(maxQueueWaitMs = Long.MAX_VALUE) }
    }
    @Test fun `완료 제한은 최소 대기보다 짧을 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) { TtsSafetyConfig(maxCompletionMs = 1) }
    }
}
