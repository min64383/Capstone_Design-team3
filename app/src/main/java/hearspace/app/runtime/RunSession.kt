package hearspace.app.runtime

import android.util.Log
import hearspace.app.TAG
import hearspace.core.audio.Hrtf
import hearspace.core.session.DeviceCsv
import hearspace.core.types.Config
import java.io.File
import kotlin.concurrent.thread

/**
 * 실행 하나: 실행 로그 + 파이프라인 + 오디오 출력. 만들면 시작하고 [close]로 오디오 → 파이프라인 → 로그 순으로 멈춘다.
 * 오디오 출력을 열지 못하면 이미 시작한 파이프라인·로그 스레드를 닫고 예외를 그대로 낸다(A1 FA3).
 */
class RunSession(config: Config, hrtf: Hrtf, val dir: File, clockNs: () -> Long) : AutoCloseable {
    val logger = RunLogger(dir)
    val pipeline = LivePipeline(config, hrtf, logger, clockNs)
    val output = BinauralOutput(config.audio, pipeline::renderBlock)

    init {
        try {
            output.start()
        } catch (e: Exception) {
            pipeline.close()
            logger.close()
            throw e
        }
        Log.i(TAG, "run started: $dir")
    }

    /** `device.csv` 한 행(발열·배터리·출력 지연). */
    fun logDevice(thermal: ThermalMonitor) {
        logger.device(DeviceCsv.format(thermal.sample().copy(audioOutputLatencyMs = output.latencyMs)))
    }

    /** 소리는 부른 스레드에서 바로 멈추고, 파이프라인·로그 정리(최대 2 s + 5 s 대기)는 다른 스레드에서 한다(A1 CO4: UI 스레드를 막지 않는다). */
    override fun close() {
        output.close()
        thread(name = "RunClose") {
            pipeline.close()
            logger.close()
            Log.i(TAG, "run log: $dir")
        }
    }

    companion object {
        /** 실시간 실행 로그 폴더 이름(앱 전용 외부 폴더 아래). */
        const val RUNS_DIR = "runs"

        /** [runsDir]에서 이름(시각) 순으로 가장 최근 [keep]개만 남기고 지운다(A1 FA4: 실행 로그가 저장 공간을 채우지 않게). 백그라운드에서 부른다. */
        fun trimRuns(runsDir: File, keep: Int) {
            val old = runsDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.dropLast(keep)
            for (d in old) if (!d.deleteRecursively()) Log.w(TAG, "run log delete incomplete: $d")
            if (old.isNotEmpty()) Log.i(TAG, "run logs trimmed: ${old.size} removed, keep $keep")
        }
    }
}
