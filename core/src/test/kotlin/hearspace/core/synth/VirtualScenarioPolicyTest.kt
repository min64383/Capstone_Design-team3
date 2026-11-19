package hearspace.core.synth

import hearspace.core.audio.VirtualScenario
import hearspace.core.audio.VirtualScenarioConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class VirtualScenarioPolicyTest {
    private val defaultFile = File(System.getProperty("hearspace.defaultConfig", "../app/src/main/assets/config/default.json"))
    private val scenarioFile = File(System.getProperty("hearspace.virtualScenarioConfig",
        defaultFile.parentFile.resolve("virtual-scenario.json").path))
    private val cfg = VirtualScenarioConfig.parse(scenarioFile.readText())
    private val originNs = 10_000_000_000L

    private fun simulate(repeat: Boolean, target: VirtualScenario.Target): List<Pair<Float, VirtualScenario.Cue>> {
        val scenario = VirtualScenario(cfg, repeat, target)
        val result = ArrayList<Pair<Float, VirtualScenario.Cue>>()
        var elapsedMs = 0L
        while (elapsedMs < cfg.durationS * 1000) {
            val frame = scenario.sample(elapsedMs / 1000f, originNs + elapsedMs * 1_000_000L)
            scenario.nextCue(frame, frame.observedNs)?.let {
                result.add(frame.elapsedS to it)
                scenario.onAccepted(it)
            }
            elapsedMs += cfg.tickMs
        }
        return result
    }

    @Test fun approachSpeaksOnceAtEachThresholdAfterConfirmation() {
        val said = simulate(false, VirtualScenario.Target.BODY)
        assertEquals(2, said.size)
        assertEquals(6.4f, said[0].first, 0.01f)
        assertEquals(13.2f, said[1].first, 0.01f)
        assertEquals("정면, 장애물, 2미터.", said[0].second.text)
        assertEquals("정면, 장애물, 1미터. 잠시 멈춰 주세요.", said[1].second.text)
        assertFalse(said[0].second.urgent)
        assertTrue(said[1].second.urgent)
    }

    @Test fun thresholdOscillationDoesNotRepeatAcceptedSpeech() {
        assertEquals(1, simulate(true, VirtualScenario.Target.BODY).size)
    }

    @Test fun outsideHasNoCueAndHeadUsesHeightWording() {
        assertTrue(simulate(false, VirtualScenario.Target.OUTSIDE).isEmpty())
        assertTrue(simulate(true, VirtualScenario.Target.OUTSIDE).isEmpty())
        assertTrue(simulate(false, VirtualScenario.Target.HEAD).all { "머리 높이" in it.second.text })
    }

    @Test fun unacceptedAndFailedRequestsCanRetryWithCurrentEvidence() {
        val scenario = VirtualScenario(cfg, false, VirtualScenario.Target.BODY)
        val start = scenario.sample(cfg.warnReachS, originNs)
        assertNull(scenario.nextCue(start, start.observedNs))
        val confirmed = start.copy(observedNs = originNs + cfg.normalConfirmMs * 1_000_000L)
        val cue = requireNotNull(scenario.nextCue(confirmed, confirmed.observedNs))
        assertEquals(cue, scenario.nextCue(confirmed, confirmed.observedNs))
        scenario.onAccepted(cue)
        assertNull(scenario.nextCue(confirmed, confirmed.observedNs))
        scenario.onFailed(cue.text)
        assertEquals(cue, scenario.nextCue(confirmed, confirmed.observedNs))
    }

    @Test fun futureStaleAndRepeatedSnapshotsDoNotConfirmSpeech() {
        val scenario = VirtualScenario(cfg, false, VirtualScenario.Target.BODY)
        val frame = scenario.sample(cfg.warnReachS, originNs)
        assertNull(scenario.nextCue(frame, originNs - 1))
        assertNull(scenario.nextCue(frame, originNs + (cfg.maxFrameAgeMs * 1e6).toLong() + 1))
        assertNull(scenario.nextCue(frame, originNs))
        assertNull(scenario.nextCue(frame, originNs + cfg.normalConfirmMs * 500_000L))
    }

    @Test fun configurationChangesThresholdsAndRejectsInvalidValues() {
        val changed = cfg.copy(warnM = 2.2f, stopM = 1.2f)
        val scenario = VirtualScenario(changed, false, VirtualScenario.Target.BODY)
        val frame = scenario.sample(changed.warnReachS, originNs)
        assertNull(scenario.nextCue(frame, frame.observedNs))
        val confirmed = frame.copy(observedNs = originNs + changed.normalConfirmMs * 1_000_000L)
        assertTrue(requireNotNull(scenario.nextCue(confirmed, confirmed.observedNs)).text.contains("2.2미터"))
        assertThrows(IllegalArgumentException::class.java) { cfg.copy(stopM = cfg.warnM) }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualScenarioConfig.parse(scenarioFile.readText().replace("\"normalConfirmMs\": 400", "\"normalConfirmMs\": 0"))
        }
    }
}
