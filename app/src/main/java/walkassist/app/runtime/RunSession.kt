package walkassist.app.runtime

import android.util.Log
import walkassist.app.TAG
import walkassist.core.audio.Hrtf
import walkassist.core.session.DeviceCsv
import walkassist.core.types.Config
import java.io.File

/** 실행 하나: 실행 로그 + 파이프라인 + 오디오 출력. 만들면 시작하고 [close]로 오디오 → 파이프라인 → 로그 순으로 멈춘다. */
class RunSession(config: Config, hrtf: Hrtf, val dir: File, clockNs: () -> Long) : AutoCloseable {
    val logger = RunLogger(dir)
    val pipeline = LivePipeline(config, hrtf, logger, clockNs)
    val output = BinauralOutput(config.audio, pipeline::renderBlock).also { it.start() }

    init {
        Log.i(TAG, "run started: $dir")
    }

    /** `device.csv` 한 행(발열·배터리·출력 지연). */
    fun logDevice(thermal: ThermalMonitor) {
        logger.device(DeviceCsv.format(thermal.sample().copy(audioOutputLatencyMs = output.latencyMs)))
    }

    override fun close() {
        output.close()
        pipeline.close()
        logger.close()
        Log.i(TAG, "run log: $dir")
    }
}
