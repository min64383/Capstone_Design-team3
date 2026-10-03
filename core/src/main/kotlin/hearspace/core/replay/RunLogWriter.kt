package hearspace.core.replay

import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.session.RunLog
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File
import java.io.Writer

/**
 * 재생 결과를 실행 로그(MVP §10.1, 앱과 같은 형식)로 [outDir]에 쓴다. 단계별 처리 시간은 `stage_timing.csv`에 따로.
 * `replay_info.json`에는 세션 경로·느린 경로 시간 방식·설정 덮어쓰기를 남긴다(분석 도구가 같은 설정을 쓰도록).
 */
class RunLogWriter(outDir: File, session: File, overridesJson: String, slowPathNote: String) : ReplayListener {
    private val slowOut: Writer
    private val guidOut: Writer
    private val obsOut: Writer
    private val clusterOut: Writer
    private val stageOut: Writer

    init {
        outDir.mkdirs()
        File(outDir, OfflineReplay.INFO_FILE).writeText(
            "{\n  \"session\": \"${session.absolutePath.replace("\\", "/")}\",\n  \"slowPath\": \"$slowPathNote\",\n" +
                "  \"configOverrides\": $overridesJson\n}\n",
        )
        slowOut = File(outDir, RunLog.SLOW_PATH_FILE).bufferedWriter()
        guidOut = File(outDir, RunLog.GUIDANCE_FILE).bufferedWriter()
        obsOut = File(outDir, RunLog.OBSTACLES_FILE).bufferedWriter()
        clusterOut = File(outDir, RunLog.CLUSTER_DEBUG_FILE).bufferedWriter()
        stageOut = File(outDir, RunLog.STAGE_TIMING_FILE).bufferedWriter()
        clusterOut.line(ClusterDebugCsv.HEADER)
        slowOut.line(RunLog.header(RunLog.SLOW_PATH_HEADER))
        guidOut.line(RunLog.header(RunLog.GUIDANCE_HEADER))
        obsOut.line(RunLog.header(RunLog.OBSTACLES_HEADER))
        stageOut.line(RunLog.header(RunLog.STAGE_TIMING_HEADER))
    }

    override fun onSlowStep(step: SlowStep) {
        slowOut.line(RunLog.slowPathLine(step.mapUpdate, step.startNs, step.doneNs, step.snapshot))
        RunLog.obstacleLines(step.snapshot).forEach { obsOut.line(it) }
        RunLog.clusterDebugLines(step.clusterDebug).forEach { clusterOut.line(it) }
        stageOut.line(RunLog.stageTimingLine(step.depth.tCaptureNs, step.stageNs))
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
        RunLog.guidanceLines(g, pose.tCaptureNs, snapshot?.tCaptureNs).forEach { guidOut.line(it) }
    }

    override fun onEnd() {
        listOf(slowOut, guidOut, obsOut, clusterOut, stageOut).forEach { it.close() }
    }

    private fun Writer.line(s: String) {
        write(s)
        write("\n")
    }
}
