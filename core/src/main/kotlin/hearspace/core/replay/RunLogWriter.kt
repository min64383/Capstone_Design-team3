package hearspace.core.replay

import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.session.RunLog
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.Closeable
import java.io.File
import java.io.Writer

/**
 * 재생 결과를 실행 로그(MVP §10.1, 앱과 같은 형식)로 [outDir]에 쓴다. 단계별 처리 시간은 `stage_timing.csv`에 따로.
 * `replay_info.json`에는 세션 경로·느린 경로 시간 방식·설정 덮어쓰기를 남긴다(분석 도구가 같은 설정을 쓰도록).
 * 재생이 예외로 끝나도 쌓인 줄을 잃지 않게 호출한 쪽이 [close]한다(`use`, A1 FA9). 끝까지 돈 표시(`replay_done`)는 호출한 쪽이
 * 모든 출력을 쓴 뒤 [OfflineReplay.markDone]으로 남긴다. 같은 폴더에 남은 옛 표시는 여기서 지운다.
 */
class RunLogWriter(
    outDir: File,
    session: File,
    overridesJson: String,
    slowPathNote: String,
    /** 정답 대입 내용(JSON 객체, M12.3). 없으면 null. */
    substitutionJson: String? = null,
    /** RGB 안내 보정의 RGB 원천(M19, [RgbSource] 이름). */
    rgbSource: String = RgbSource.RECORDED.name,
) : ReplayListener, Closeable {
    private val slowOut: Writer
    private val guidOut: Writer
    private val obsOut: Writer
    private val clusterOut: Writer
    private val stageOut: Writer
    private var planesOut: Writer? = null
    private var geometryOut: Writer? = null
    private val outDir = outDir

    init {
        outDir.mkdirs()
        File(outDir, OfflineReplay.DONE_FILE).delete()
        File(outDir, RunLog.GEOMETRY_FILE).delete() // No stale diagnostics when rerunning SINGLE in the same directory.
        File(outDir, OfflineReplay.INFO_FILE).writeText(
            "{\n  \"session\": \"${session.absolutePath.replace("\\", "/")}\",\n  \"slowPath\": \"$slowPathNote\",\n" +
                "  \"rgbSource\": \"$rgbSource\",\n" +
                // 재생한 코드 판(M41, Gradle이 넘김: git 커밋 짧은 해시, 바뀐 작업 트리면 -dirty). 없으면 unknown
                "  \"gitHash\": \"${System.getProperty("hearspace.gitHash") ?: "unknown"}\",\n" +
                "  \"configOverrides\": $overridesJson" + (substitutionJson?.let { ",\n  \"substitution\": $it" } ?: "") + "\n}\n",
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
        step.mapUpdate.geometry?.let { result ->
            val w = geometryOut ?: File(outDir, RunLog.GEOMETRY_FILE).bufferedWriter().also {
                it.line(RunLog.header(RunLog.GEOMETRY_HEADER)); geometryOut = it
            }
            RunLog.geometryLines(step.depth.tCaptureNs, result).forEach { w.line(it) }
        }
        step.mapUpdate.planes?.let { p ->
            val w = planesOut ?: File(outDir, RunLog.PLANES_FILE).bufferedWriter().also { it.line(RunLog.header(RunLog.PLANES_HEADER)); planesOut = it }
            w.line(RunLog.planesLine(step.depth.tCaptureNs, p))
        }
    }

    override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) {
        RunLog.guidanceLines(g, pose.tCaptureNs, snapshot?.tCaptureNs).forEach { guidOut.line(it) }
    }

    override fun onEnd() = close()

    /** 기록 파일을 모두 닫는다(여러 번 불러도 된다). */
    override fun close() {
        listOfNotNull(slowOut, guidOut, obsOut, clusterOut, stageOut, planesOut, geometryOut).forEach { it.close() }
    }

    private fun Writer.line(s: String) {
        write(s)
        write("\n")
    }
}
