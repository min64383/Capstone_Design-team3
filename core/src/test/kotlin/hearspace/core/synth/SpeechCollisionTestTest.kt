package hearspace.core.synth

import hearspace.core.audio.SpeechCollisionTest
import hearspace.core.audio.SpeechCollisionTest.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SpeechCollisionTestTest {
    private val cfg = Config.parse(File(System.getProperty("hearspace.collisionConfig",
        "../app/src/main/assets/config/speech-collision-test.json")).readText())

    private class Trial(val test: SpeechCollisionTest, val base: Attempt, val urgent: Attempt)
    private fun startUrgent(mode: Mode = Mode.INTERRUPT, protect: Boolean = true): Trial {
        val test = SpeechCollisionTest(cfg, mode, 0L, protect)
        val first = requireNotNull(test.next(0))
        assertTrue(first.allowed)
        test.onAdmission(first, true)
        test.onEvent("base", first.line.text, Event.STARTED, 1000)
        assertNull(test.next(1000 + cfg.urgentAfterStartMs - 1))
        val urgent = requireNotNull(test.next(1000 + cfg.urgentAfterStartMs))
        assertTrue(urgent.allowed)
        assertEquals(Role.URGENT, urgent.line.role)
        // AndroidTts는 speak() 반환 전에 이전 발화의 중단 이벤트를 전달한다.
        test.onEvent("base", first.line.text, Event.INTERRUPTED, 1000 + cfg.urgentAfterStartMs)
        test.onAdmission(urgent, true)
        test.onEvent("urgent", urgent.line.text, Event.STARTED, 2000)
        return Trial(test, first, urgent)
    }

    @Test fun triggerWaitsForActualStartRatherThanRequestTime() {
        val test = SpeechCollisionTest(cfg, Mode.INTERRUPT, 0, true)
        val first = requireNotNull(test.next(0))
        test.onAdmission(first, true)
        assertNull(test.next(5000))
        test.onEvent("base", first.line.text, Event.STARTED, 5000)
        assertNull(test.next(5000 + cfg.urgentAfterStartMs - 1))
        assertEquals(Role.URGENT, test.next(5000 + cfg.urgentAfterStartMs)?.line?.role)
    }

    @Test fun interruptPassRequiresUrgentCompletionAndRecoveryListening() {
        val t = startUrgent()
        assertNull(t.test.next(3000))
        assertNull(t.test.result)
        t.test.onEvent("urgent", t.urgent.line.text, Event.COMPLETED, 4000)
        assertNull(t.test.next(4000 + cfg.recoveryListenMs - 1))
        assertNull(t.test.result)
        t.test.next(4000 + cfg.recoveryListenMs)
        assertEquals(true, t.test.result?.passed)
    }

    @Test fun ordinaryStatusCannotReplaceUrgentEvenWithReplaceCurrentFlag() {
        val t = startUrgent(Mode.STATUS_COLLISION)
        val status = requireNotNull(t.test.next(2000 + cfg.statusAfterUrgentStartMs))
        assertTrue(status.line.replaceCurrent)
        assertFalse(status.allowed)
        t.test.onAdmission(status, false)
        t.test.onEvent("urgent", t.urgent.line.text, Event.COMPLETED, 4000)
        t.test.next(4000 + cfg.recoveryListenMs)
        assertEquals(true, t.test.result?.passed)
    }

    @Test fun disablingProtectionReproducesUrgentBeingInterruptedByStatus() {
        val t = startUrgent(Mode.STATUS_COLLISION, protect = false)
        val status = requireNotNull(t.test.next(2000 + cfg.statusAfterUrgentStartMs))
        assertTrue(status.allowed)
        t.test.onEvent("urgent", t.urgent.line.text, Event.INTERRUPTED, 2500)
        t.test.onAdmission(status, true)
        assertEquals(false, t.test.result?.passed)
        t.test.onEvent("urgent", t.urgent.line.text, Event.COMPLETED, 4000)
        assertEquals(false, t.test.result?.passed)
    }

    @Test fun lateCompletionOfReplacedBaseDoesNotClearUrgent() {
        val t = startUrgent(Mode.STATUS_COLLISION)
        t.test.onEvent("base", t.base.line.text, Event.COMPLETED, 2100)
        val status = requireNotNull(t.test.next(2000 + cfg.statusAfterUrgentStartMs))
        assertFalse(status.allowed)
        assertNull(t.test.result)
    }

    @Test fun mismatchedUtteranceIdIsIgnored() {
        val t = startUrgent()
        t.test.onEvent("old-urgent", t.urgent.line.text, Event.ERROR, 2500)
        assertNull(t.test.result)
    }

    @Test fun initialCompletionBeforeInterruptIsFailure() {
        val test = SpeechCollisionTest(cfg, Mode.INTERRUPT, 0, true)
        val first = requireNotNull(test.next(0))
        test.onAdmission(first, true)
        test.onEvent("base", first.line.text, Event.STARTED, 1)
        test.onEvent("base", first.line.text, Event.COMPLETED, 2)
        assertEquals(false, test.result?.passed)
    }

    @Test fun engineErrorOrRejectedRequestCannotPass() {
        val test = SpeechCollisionTest(cfg, Mode.INTERRUPT, 0, true)
        test.onAdmission(requireNotNull(test.next(0)), false)
        assertEquals(false, test.result?.passed)
        val other = SpeechCollisionTest(cfg, Mode.INTERRUPT, 0, true)
        other.onEvent("engine", "", Event.ERROR, 0)
        assertEquals(false, other.result?.passed)
    }

    @Test fun cancelAndTimeoutCannotPass() {
        val t = startUrgent()
        t.test.cancel()
        t.test.onEvent("urgent", t.urgent.line.text, Event.COMPLETED, 4000)
        assertEquals(false, t.test.result?.passed)
        val timeout = SpeechCollisionTest(cfg, Mode.INTERRUPT, 0, true)
        timeout.next(cfg.timeoutMs)
        assertEquals(false, timeout.result?.passed)
    }

    @Test fun invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(urgentAfterStartMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(recoveryStatusText = cfg.urgentText) }
    }
}
