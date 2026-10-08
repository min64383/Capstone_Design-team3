package hearspace.core.replay

import hearspace.core.geometry.Vec3
import hearspace.core.guidance.MapAction
import hearspace.core.mapping.MapUpdate
import hearspace.core.mapping.VoxelView
import hearspace.core.pipeline.ClusterDebug
import hearspace.core.pipeline.FastPath
import hearspace.core.pipeline.SlowPath
import hearspace.core.pipeline.StageTimes
import hearspace.core.session.DepthEvent
import hearspace.core.session.PoseEvent
import hearspace.core.session.SessionReader
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import hearspace.core.types.RgbGuideMode
import java.io.File

/** 느린 경로 한 번의 결과(재생 가상 시계 기준). */
class SlowStep(
    val depth: DepthFrame,
    /** 워커가 이 깊이를 잡은 시각과 결과를 낸 시각(가상 시계, ns). */
    val startNs: Long,
    val doneNs: Long,
    val snapshot: ObstacleSnapshot,
    val mapUpdate: MapUpdate,
    val clusterDebug: List<ClusterDebug>,
    /** 실제 시계로 잰 단계별 처리 시간(재생 결과에는 영향 없음). */
    val stageNs: StageTimes,
    /** 결과가 빠른 경로에 반영됐는가. 처리 중 RESET이 오면 이전 월드 좌표의 결과라 버린다. */
    val applied: Boolean,
    /** 처리 직후 점유 복셀. [ReplayListener.captureVoxels]가 true일 때만. */
    val occupiedVoxels: List<VoxelView>?,
)

/** [OfflineReplay] 결과를 받는 쪽: 실행 로그 파일([RunLogWriter]) 또는 평가 GUI의 메모리 수집. */
interface ReplayListener {
    /** true면 느린 경로마다 점유 복셀을 복사해 [SlowStep.occupiedVoxels]로 준다(GUI용, 비용이 든다). */
    val captureVoxels: Boolean get() = false

    /** 느린 경로 결과가 나온 순서대로. */
    fun onSlowStep(step: SlowStep)

    /** 오디오 블록마다. [pose]는 그 블록이 쓴 최신 자세, [snapshot]은 그 블록이 쓴 스냅샷(없으면 null). */
    fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput)

    /** 재생이 끝났을 때 한 번. */
    fun onEnd() {}
}

/**
 * PC 오프라인 재생 (MVP §7.9, IMPROVE_SPEC §10.1). 녹화 세션을 가상 시계로 앱(`LivePipeline`)과 같은 인과 순서로 돌린다.
 * 같은 입력이면 항상 같은 출력이다(스레드·실제 시계 없음. 실제 시계는 [SlowStep.stageNs] 측정에만 쓴다).
 *
 * 가상 시계 = 앱 수신 시각(`frames.csv`의 `sysElapsedNs`, ARCore 프레임 시각과 같은 부팅 시계, F5).
 * - 자세·깊이는 그 프레임의 수신 시각에 도착한다.
 * - 느린 경로 워커: 쉬고 있으면 도착 즉시 시작하고 [slowPathNs]만큼 뒤에 스냅샷을 낸다. 처리 중 도착한 깊이는 최신 1장만 남는다.
 * - 오디오: `audio.blockSize` 간격 블록마다 그 시각까지 도착한 최신 자세·스냅샷으로 `FastPath.compute`.
 *   렌더링은 받는 쪽이 한다(GUI는 블록마다 바이노럴 렌더러로 소리를 만든다).
 * - 맵 명령·RESET 처리는 앱과 같다(RESET 자세 시각 이전 깊이·처리 중 결과는 버림).
 */
class OfflineReplay(
    private val config: Config,
    /** 깊이 한 장의 느린 경로 처리 시간(ns). 고정값 또는 기록된 실측값(`slow_path.csv`)으로 준다. */
    private val slowPathNs: (DepthFrame) -> Long,
) {

    /**
     * [session]을 재생해 [outDir]에 실행 로그(MVP §10.1)와 `replay_info.json`을 쓴다. 정답 파일이 있으면 지도 평가
     * `map_eval.json`(M13, [MapEvalWriter])도 쓴다.
     */
    fun run(session: File, outDir: File, overridesJson: String = "{}", slowPathNote: String = "") {
        val log = RunLogWriter(outDir, session, overridesJson, slowPathNote)
        run(session, if (File(session, "annotations/obstacles.json").isFile) MapEvalWriter(log, outDir, session, config) else log)
    }

    /** [session]을 재생해 결과를 [listener]에 넘긴다. */
    fun run(session: File, listener: ReplayListener) {
        val reader = SessionReader(session)
        val sysOf = reader.rows.associate { it.frameIndex to it.sysElapsedNs }
        // M13.7: RGB 안내 보정을 켜면 깊이 장마다 과거 RGB를 붙인다
        val rgb = if (config.frontend.rgbGuide != RgbGuideMode.NONE) {
            RgbFrames(session, reader.rows, reader.meta.camera.imageIntrinsics, (config.frontend.rgbMaxAgeMs * 1e6).toLong())
        } else {
            null
        }

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
        // M13.4: 느린 경로 주기 상한(앱 LivePipeline과 같은 규칙: 시작 간격이 1/maxRateHz 이상)
        val minIntervalNs = if (config.slowPath.maxRateHz > 0f) (1e9 / config.slowPath.maxRateHz).toLong() else 0L
        var lastStartNs = Long.MIN_VALUE

        fun startIfIdle(atNs: Long) {
            if (job != null) return
            if (minIntervalNs > 0 && lastStartNs != Long.MIN_VALUE && atNs - lastStartNs < minIntervalNs) return // 깊이는 최신 값으로 기다림
            if (mapAction != MapAction.NONE) {
                slow.apply(mapAction)
                mapAction = MapAction.NONE
            }
            val d = pending ?: return
            pending = null
            val h = heading ?: return // 앱과 같이: 진행 방향을 모르면 그 깊이는 버린다
            if (d.tCaptureNs < resetAfterNs) return
            lastStartNs = atNs
            val s = slow.process(d, h)
            val voxels = if (listener.captureVoxels) slow.map.voxels.occupied() else null
            job = Job(d, atNs, atNs + slowPathNs(d), s, resetAfterNs, slow.lastMapUpdate!!, slow.lastClusterDebug, slow.lastStageNs, voxels)
        }

        fun finishUpTo(tNs: Long) {
            while (true) {
                val j = job ?: return
                if (j.doneNs > tNs) return
                job = null
                val applied = resetAfterNs == j.resetMark
                if (applied) snapshot = j.snapshot
                listener.onSlowStep(SlowStep(j.depth, j.startNs, j.doneNs, j.snapshot, j.mapUpdate, j.clusterDebug, j.stageNs, applied, j.voxels))
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
        var t = next?.first ?: return listener.onEnd()
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
                            pending = rgb?.attach(e.depth) ?: e.depth
                            startIfIdle(arrival)
                        }
                    }
                    next = if (events.hasNext()) events.next() else null
                }
            }
            if (minIntervalNs > 0) startIfIdle(t) // 주기 상한으로 미뤄 둔 깊이를 간격이 지나면 시작
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
                listener.onBlock(t, p, snap, g)
            }
            t += blockNs
        }
        listener.onEnd()
    }

    private class Job(
        val depth: DepthFrame,
        val startNs: Long,
        val doneNs: Long,
        val snapshot: ObstacleSnapshot,
        val resetMark: Long,
        val mapUpdate: MapUpdate,
        val clusterDebug: List<ClusterDebug>,
        val stageNs: StageTimes,
        val voxels: List<VoxelView>?,
    )

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
