package hearspace.core.synth

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.audio.VirtualScenario
import hearspace.core.audio.VirtualScenarioConfig
import hearspace.core.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VirtualScenarioTest {
    private val defaultFile = File(System.getProperty("hearspace.defaultConfig", "../app/src/main/assets/config/default.json"))
    private val scenarioFile = File(System.getProperty("hearspace.virtualScenarioConfig",
        defaultFile.parentFile.resolve("virtual-scenario.json").path))
    private val cfg = VirtualScenarioConfig.parse(scenarioFile.readText())

    @Test fun comparisonToneRemainsAudibleAndDuckingRecovers() {
        val base = ConfigLoader.load(defaultFile.readText())
        val config = base.copy(sonify = base.sonify.copy(mode = SonifyMode.RISK_CONTINUOUS,
            mapping = SonifyMapping.DISTANCE_HEIGHT, onceS = cfg.comparisonHoldS))
        // 합성 1-tap HRIR: 방향 효과를 없애 이득 변화만 비교한다.
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x52484157).putInt(1).putInt(config.audio.sampleRate).putInt(1).putInt(2)
            .putFloat(-180f).putFloat(1f).putFloat(1f)
            .putFloat(0f).putFloat(1f).putFloat(1f).array()
        val reference = BinauralRenderer(config, Hrtf.parse(bytes))
        val actual = BinauralRenderer(config, Hrtf.parse(bytes))
        val a = FloatArray(config.audio.blockSize * 2)
        val b = FloatArray(a.size)
        val cmd = AudioCmd(1, 0f, cfg.warnM, Band.WARN, SoundKind.FLOOR_PULSE, 0f,
            heightDeltaM = cfg.bodyHeightDeltaM)
        fun block(index: Int, speaking: Boolean) {
            val output = GuidanceOutput(index.toLong() * config.audio.blockSize * 1_000_000_000L / config.audio.sampleRate,
                GuidanceState.NORMAL, listOf(cmd), 0f, null)
            reference.renderWithSpeech(output, a, false)
            actual.renderWithSpeech(output, b, speaking)
        }
        for (i in 0..199) block(i, false)
        for (i in 200..399) block(i, true)
        assertTrue(a.any { abs(it) > 1e-5f }, "static comparison tone stopped")
        assertTrue(a.indices.all { abs(b[it] - a[it] * config.audio.ttsObstacleGain) < 1e-6f })
        for (i in 400..599) block(i, false)
        assertTrue(a.indices.all { abs(b[it] - a[it]) < 1e-6f })
    }
}
