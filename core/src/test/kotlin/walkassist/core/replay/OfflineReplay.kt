package walkassist.core.replay

import walkassist.core.geometry.Vec3
import walkassist.core.guidance.MapAction
import walkassist.core.pipeline.ClusterDebugCsv
import walkassist.core.pipeline.FastPath
import walkassist.core.pipeline.SlowPath
import walkassist.core.session.DepthEvent
import walkassist.core.session.PoseEvent
import walkassist.core.session.RunLog
import walkassist.core.session.SessionReader
import walkassist.core.types.Config
import walkassist.core.types.DepthFrame
import walkassist.core.types.ObstacleSnapshot
import walkassist.core.types.PoseFrame
import java.io.File

/**
 * PC 오프라인 재생 (§7.9). 녹화 세션을 가상 시계로 앱(`LivePipeline`)과 같은 인과 순서로 돌려 §10.1 실행 로그를 쓴다.
 * 같은 입력이면 항상 같은 출력이다(스레드·실제 시계 없음).
 *
 * 가상 시계 = 앱 수신 시각(`frames.csv`의 `sysElapsedNs`, ARCore 프레임 시각과 같은 부팅 시계, F5).
 * - 자세·깊이는 그 프레임의 수신 시각에 도착한다.
 * - 느린 경로 워커: 쉬고 있으면 도착 즉시 시작하고 [slowPathNs]만큼 뒤에 스냅샷을 낸다. 처리 중 도착한 깊이는 최신 1장만 남는다.
 * - 오디오: `audio.blockSize` 간격 블록마다 그 시각까지 도착한 최신 자세·스냅샷으로 `FastPath.compute`(렌더링은 생략).
 * - 맵 명령·RESET 처리는 앱과 같다(RESET 자세 시각 이전 깊이·처리 중 결과는 버림).
 */
class OfflineReplay(
    private val config: Config,
    /** 깊이 한 장의 느린 경로 처리 시간(ns). 고정값 또는 기록된 실측값(`slow_path.csv`)으로 준다. */
    private val slowPathNs: (DepthFrame) -> Long,
) {

    /**
     * [session]을 재생해 [outDir]에 `slow_path.csv`·`guidance.csv`·`obstacles.csv`와 `replay_info.json`을 쓴다.
     * [overridesJson]은 [config]를 만들 때 기본 설정에 덮어쓴 JSON(분석 도구가 같은 설정을 쓰도록 기록만 한다).
     */
    fun run(session: File, outDir: File, overridesJson: String = "{}", slowPathNote: String = "") {
        val reader = SessionReader(session)
        val sysOf = reader.rows.associate { it.frameIndex to it.sysElapsedNs }
        outDir.mkdirs()
        File(outDir, INFO_FILE).writeText(
            "{\n  \"session\": \"${session.absolutePath.replace("\\", "/")}\",\n  \"slowPath\": \"$slowPathNote\",\n" +
                "  \"configOverrides\": $overridesJson\n}\n",
        )
        val slowOut = File(outDir, RunLog.SLOW_PATH_FILE).bufferedWriter()
        val guidOut = File(outDir, RunLog.GUIDANCE_FILE).bufferedWriter()
        val obsOut = File(outDir, RunLog.OBSTACLES_FILE).bufferedWriter()
        val clusterOut = File(outDir, RunLog.CLUSTER_DEBUG_FILE).bufferedWriter()
        clusterOut.line(ClusterDebugCsv.HEADER)
        slowOut.line(RunLog.header(RunLog.SLOW_PATH_HEADER))
        guidOut.line(RunLog.header(RunLog.GUIDANCE_HEADER))
        obsOut.line(RunLog.header(RunLog.OBSTACLES_HEADER))

        val fast = FastPath(config)
        val slow = SlowPath(config)
        var pose: PoseFrame? = null
        var snapshot: ObstacleSnapshot? = null
        var heading: Vec3? = null
        var mapAction = MapAction.NONE
        var resetAfterNs = Long.MIN_VALUE
        var pending: DepthFrame? = null
        // 처리 중인 작업(끝 시각, 결과, 시작 시 RESET 표시)
        var job: Job? = null

        fun startIfIdle(atNs: Long) {
            if (job != null) return
            if (mapAction != MapAction.NONE) {
                slow.apply(mapAction)
                mapAction = MapAction.NONE
            }
            val d = pending ?: return
            pending = null
            val h = heading ?: return // 앱과 같이: 진행 방향을 모르면 그 깊이는 버린다
            if (d.tCaptureNs < resetAfterNs) return
            val s = slow.process(d, h)
            val doneNs = atNs + slowPathNs(d)
            job = Job(doneNs, s, resetAfterNs, RunLog.slowPathLine(slow.lastMapUpdate!!, atNs, doneNs, s), RunLog.clusterDebugLines(slow.lastClusterDebug))
        }

        fun finishUpTo(tNs: Long) {
            while (true) {
                val j = job ?: return
                if (j.doneNs > tNs) return
                job = null
                if (resetAfterNs == j.resetMark) snapshot = j.snapshot
                slowOut.line(j.slowLine)
                RunLog.obstacleLines(j.snapshot).forEach { obsOut.line(it) }
                j.clusterLines.forEach { clusterOut.line(it) }
                startIfIdle(j.doneNs)
            }
        }

        val events = reader.events(config.depth.source).map { e ->
            when (e) {
                is PoseEvent -> sysOf.getValue(e.frameIndex) to e
                is DepthEvent -> sysOf.getValue(e.frameIndex) to e
            }
        }.iterator()
        val blockNs = config.audio.blockSize * 1_000_000_000L / config.audio.sampleRate
        var next = if (events.hasNext()) events.next() else null
        var t = next?.first ?: return
        while (next != null || job != null) {
            // 이 블록 시각까지의 도착과 완료를 시간 순서대로
            while (true) {
                val arrival = next?.first ?: Long.MAX_VALUE
                val done = job?.doneNs ?: Long.MAX_VALUE
                if (minOf(arrival, done) > t) break
                if (done <= arrival) {
                    finishUpTo(done)
                } else {
                    when (val e = next!!.second) {
                        is PoseEvent -> pose = e.pose
                        is DepthEvent -> {
                            pending = e.depth
                            startIfIdle(arrival)
                        }
                    }
                    next = if (events.hasNext()) events.next() else null
                }
            }
            val p = pose
            if (p != null) {
                val snap = snapshot
                val g = fast.compute(p, snap, t)
                when (val a = fast.takeMapAction()) {
                    MapAction.NONE -> Unit
                    MapAction.RESET -> {
                        resetAfterNs = p.tCaptureNs
                        snapshot = null
                        mapAction = MapAction.RESET
                        startIfIdle(t)
                    }
                    MapAction.SCALE -> {
                        if (mapAction == MapAction.NONE) mapAction = a
                        startIfIdle(t)
                    }
                }
                fast.headingW?.let { heading = it }
                RunLog.guidanceLines(g, p.tCaptureNs, snap?.tCaptureNs).forEach { guidOut.line(it) }
            }
            t += blockNs
        }
        listOf(slowOut, guidOut, obsOut, clusterOut).forEach { it.close() }
    }

    private class Job(val doneNs: Long, val snapshot: ObstacleSnapshot, val resetMark: Long, val slowLine: String, val clusterLines: List<String>)

    private fun java.io.Writer.line(s: String) {
        write(s)
        write("\n")
    }

    companion object {
        const val INFO_FILE = "replay_info.json"

        /** 고정 처리 시간(ms). S10 실측 중앙값 약 9~10 ms(M7). */
        fun fixed(ms: Float): (DepthFrame) -> Long = { (ms * 1e6).toLong() }

        /** 기록된 실행 로그(`slow_path.csv`)의 tDone − tStart를 깊이 시각으로 찾아 쓰고, 없으면 [fallbackMs]. */
        fun recorded(slowPathCsv: File, fallbackMs: Float): (DepthFrame) -> Long {
            val byT = slowPathCsv.readLines().drop(1).filter { it.isNotBlank() }.associate { line ->
                val c = line.split(',')
                c[0].toLong() to (c[2].toLong() - c[1].toLong())
            }
            return { d -> byT[d.tCaptureNs] ?: (fallbackMs * 1e6).toLong() }
        }
    }
}
