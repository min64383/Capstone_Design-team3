package hearspace.app.runtime

import android.os.Process
import android.util.Log
import hearspace.app.TAG
import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.audio.ObjectLabels
import hearspace.core.audio.ObjectSpeechConfig
import hearspace.core.audio.ObjectSpeechFrame
import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.MapAction
import hearspace.core.pipeline.FastPath
import hearspace.core.pipeline.SlowPath
import hearspace.core.session.RunLog
import hearspace.core.situation.SituationInput
import hearspace.core.situation.SituationSpeechFrame
import hearspace.core.situation.Situations
import hearspace.core.situation.Utterance
import hearspace.core.types.AlertKind
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.LabelImage
import hearspace.core.types.MapHealth
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 스레드 배치(§9.1). 스레드 사이 전달은 모두 최신 값 1개 교체(`AtomicReference`)다.
 * - GL 스레드: [publishPose], [publishDepth]
 * - 느린 경로 워커(이 클래스가 소유): 최신 깊이 → `SlowPath.process` → [snapshot]
 * - 오디오 스레드: [renderBlock]이 블록마다 최신 자세·스냅샷으로 `FastPath.compute` → `BinauralRenderer`
 * - 상황 판단 스레드(M65, 설정이 켰을 때): 느린 경로가 처리마다 그 결과를 최신 값으로 넘기면 [surface]의 가장 최근 노면 영상으로 한 차례 돌리고,
 *   사용자 모드는 후보를 [lastSituationSpeech]로 넘기고, 기존 호출은 [onSpeech]와 [speechLevel]을 쓴다.
 *   느린 경로 스레드에서 돌리면 기기에서 한 차례 수십~수백 ms가 걸려 깊이 처리를 막았다(M65 E2: T01 처리 29.6 → 7.9 Hz).
 * 예외 하나: 맵 명령(RESET/SCALE)은 덮어쓰면 RESET이 사라질 수 있어 강한 쪽을 남기도록 합친다.
 * [clockNs]는 자세 시각과 같은 시계의 현재 시각이다(실시간·재생 모드가 다름, [LiveScreen] 참고).
 */
class LivePipeline(
    private val config: Config,
    hrtf: Hrtf,
    private val logger: RunLogger?,
    private val clockNs: () -> Long,
    objectSpeechConfig: ObjectSpeechConfig?,
) : AutoCloseable {
    constructor(config: Config, hrtf: Hrtf, logger: RunLogger?, clockNs: () -> Long) :
        this(config, hrtf, logger, clockNs, null)

    private val fast = FastPath(config)
    private val slow = SlowPath(config).apply {
        objectLabels = objectSpeechConfig?.takeIf { it.enabled && it.useSemanticLabels }?.let {
            ObjectLabels(it, config.vision.maxLabelAgeMs * 1_000_000L)
        }
    }
    private val maxInfoAgeNs = (config.policy.maxInfoAgeMs * 1e6).toLong()
    // 사용자 모드는 상태·물체·보행상황을 같은 TTS 선택기로 보낸다.
    private val useLiveSpeechArbiter = objectSpeechConfig != null
    private val latestSurface = AtomicReference<LabelImage?>(null)
    private var appliedSurface: LabelImage? = null // Situation 스레드 전용
    val lastSituationSpeech = AtomicReference<SituationSpeechFrame?>(null)
    private val renderer = BinauralRenderer(config, hrtf)
    private val situations = if (config.situation.enabled) Situations(
        config.situation, config.corridor.widthM, config.corridor.heightM, config.vision.footHeightWindowS, (config.policy.maxInfoAgeMs * 1e6).toLong(),
    ) else null

    /** 노면 모델(M65, [RunSession]이 만든다). 없으면 상황 판단은 노면 영상 없이 돈다. */
    @Volatile
    var surface: SurfaceModel? = null

    /** 앱에서 지금 말하는 중인 줄의 등급(0 = 쉼, 1~3). 말 내보내기가 줄을 받은 때부터 끝날 때까지. */
    val speechLevel = AtomicInteger(0)

    /** 기존 호출용 발화 결과(상황 판단 스레드에서 부른다). 사용자 모드는 lastSituationSpeech를 읽는다. */
    @Volatile
    var onSpeech: ((List<Utterance>) -> Unit)? = null

    /** 소리가 나지 않은 상황 말의 열쇠(UI 스레드가 넣고, 느린 경로가 다음 처리 차례에 말하기 규칙에 되돌린다). 잃으면 안 되는 사건이라 집합에 모은다. */
    private val unsaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** UI 스레드: 앱이 소리를 내지 못한 상황 말을 되돌린다(상황이 이어지면 새 근거로 다시 낸다). */
    fun unsay(key: String) {
        unsaid.add(key)
    }

    private val pose = AtomicReference<PoseFrame?>(null)
    private val depth = AtomicReference<DepthFrame?>(null)
    private val headingW = AtomicReference<Vec3?>(null)
    private val mapAction = AtomicReference(MapAction.NONE)

    /** 마지막 RESET을 일으킨 자세 시각. 이보다 앞선 깊이는 이전 월드 좌표라 버린다. */
    private val resetAfterNs = AtomicLong(Long.MIN_VALUE)
    private val wake = Semaphore(0)

    /** 최신 스냅샷(오디오 스레드·오버레이가 읽음). */
    val snapshot = AtomicReference<ObstacleSnapshot?>(null)

    /** 오버레이용: 최신 안내 출력과 머리 자세, 복셀 중심(느린 경로마다 복사). */
    val lastOutput = AtomicReference<GuidanceOutput?>(null)
    /** 문장 생성용 출력과 그 출력에 쓰인 스냅샷을 같은 값으로 교체한다. */
    val lastObjectSpeech = AtomicReference<ObjectSpeechFrame?>(null)
    val lastHead = AtomicReference<HeadPose?>(null)
    val voxelCentersW = AtomicReference<List<Vec3>>(emptyList())

    /** 사용자 일시정지(§7.5 PAUSED, UI 스레드가 쓴다). */
    @Volatile
    var paused = false
        set(value) {
            field = value
            if (value) lastSituationSpeech.set(null)
        }

    /** UI 스레드가 TTS 발화 상태를 쓰고 오디오 스레드가 블록마다 읽는다. */
    @Volatile
    var speechActive = false

    /** 알림(상태 전이·요청한 알림)이 날 때 오디오 스레드에서 부른다(진동·화면 갱신용, 가볍게). */
    @Volatile
    var onAlert: ((AlertKind) -> Unit)? = null

    private val requestedAlert = AtomicReference<AlertKind?>(null)

    /** 상태 기계 밖의 알림음(예: 시작 안내)을 다음 블록에 낸다. */
    fun requestAlert(kind: AlertKind) = requestedAlert.set(kind)

    /** 느린 경로 처리 횟수(오버레이 Hz 계산용). */
    val nSlow = AtomicLong()

    @Volatile
    private var running = true
    private val worker = Thread(::slowLoop, "SlowPath").apply { start() }

    /** 상황 판단 한 차례의 입력(느린 경로 → 상황 판단 스레드, 최신 값 하나). */
    private class SitJob(val depth: DepthFrame, val snapshot: ObstacleSnapshot, val resetMark: Long)

    private val sitJob = AtomicReference<SitJob?>(null)
    private val sitReset = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sitWorker = situations?.let { Thread(::situationLoop, "Situation").apply { start() } }

    // ---- GL 스레드 ----

    fun publishPose(p: PoseFrame) = pose.set(p)

    fun publishDepth(d: DepthFrame) {
        depth.set(d)
        wake.release()
    }

    /** 완료된 노면 마스크를 넘긴다. 화소는 SurfaceClass 번호이고 촬영 시각·자세를 보존한다. */
    fun publishSurface(surface: LabelImage) {
        if (!running || situations == null || surface.tCaptureNs <= resetAfterNs.get() ||
            surface.tCaptureNs > clockNs() || surface.K.width <= 0 || surface.K.height <= 0 ||
            surface.labels.size.toLong() != surface.K.width.toLong() * surface.K.height) return
        latestSurface.getAndUpdate { previous ->
            if (previous == null || surface.tCaptureNs > previous.tCaptureNs) surface else previous
        }
    }

    /** 입력 기록(M61, `input_timing.csv`). 로그가 없으면 아무것도 하지 않는다. */
    fun logInput(frameTNs: Long, kind: String, imageTNs: Long?, copyNs: Long, result: String, tracking: String?) =
        logger?.input(frameTNs, clockNs(), kind, imageTNs, copyNs, result, tracking)

    // ---- 오디오 스레드 ----

    /** 블록 하나를 [out](2 × blockSize, L R 교차)에 렌더한다. 자세가 아직 없으면 알림음만. */
    fun renderBlock(out: FloatArray) {
        requestedAlert.getAndSet(null)?.let {
            renderer.alert(it)
            onAlert?.invoke(it)
        }
        val p = pose.get()
        if (p == null) { // 카메라 시작 전: 알림음만(음원 없음)
            lastObjectSpeech.set(null)
            lastSituationSpeech.set(null)
            renderer.renderWithSpeech(GuidanceOutput(clockNs(), GuidanceState.UNKNOWN, emptyList(), Float.NaN, null), out, speechActive)
            return
        }
        val snap = snapshot.get()
        fast.paused = paused
        val g = fast.compute(p, snap, clockNs())
        g.alert?.let { onAlert?.invoke(it) }
        when (val a = fast.takeMapAction()) {
            MapAction.NONE -> Unit
            MapAction.RESET -> {
                resetAfterNs.set(p.tCaptureNs)
                snapshot.set(null) // 이전 월드 좌표의 물체로 안내하지 않는다
                lastSituationSpeech.set(null)
                mapAction.set(MapAction.RESET)
                wake.release()
            }
            MapAction.SCALE -> {
                mapAction.compareAndSet(MapAction.NONE, a)
                wake.release()
            }
        }
        fast.headingW?.let { headingW.set(it) }
        renderer.renderWithSpeech(g, out, speechActive)
        lastOutput.set(g)
        lastObjectSpeech.set(ObjectSpeechFrame(g, snap?.takeIf {
            it.tCaptureNs > resetAfterNs.get()
        }, resetAfterNs.get()))
        lastHead.set(fast.head)
        // 블록마다 로그 한 건(약 190건/초). 문자열은 로그 스레드가 만든다(A1 CO5)
        logger?.guidance(g, p.tCaptureNs, snap?.tCaptureNs)
    }

    // ---- 느린 경로 워커 ----

    private var lastSlowStartNs = Long.MIN_VALUE

    private fun slowLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            wake.acquire()
            wake.drainPermits()
            val action = mapAction.getAndSet(MapAction.NONE)
            if (action != MapAction.NONE) {
                slow.apply(action)
                if (action == MapAction.RESET) {
                    sitReset.set(true) // Situations는 상황 판단 스레드에서만 만진다.
                    lastSituationSpeech.set(null)
                }
                Log.i(TAG, "map action $action")
            }
            // M13.4 주기 상한: 지난 시작에서 1/maxRateHz가 지나야 꺼낸다(기다리는 동안 온 깊이는 최신 값으로 교체된다)
            val minIntervalNs = if (config.slowPath.maxRateHz > 0f) (1e9 / config.slowPath.maxRateHz).toLong() else 0L
            if (minIntervalNs > 0 && lastSlowStartNs != Long.MIN_VALUE) {
                val waitNs = lastSlowStartNs + minIntervalNs - clockNs()
                if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
            }
            val d = depth.getAndSet(null) ?: continue
            val h = headingW.get() ?: continue
            val resetMark = resetAfterNs.get()
            if (d.tCaptureNs < resetMark) continue
            val t0 = clockNs()
            lastSlowStartNs = t0
            val s = slow.process(d, h)
            val t1 = clockNs()
            // 처리 중 RESET이 왔으면 이 결과는 이전 월드 좌표의 것이다
            if (resetAfterNs.get() == resetMark) snapshot.set(s)
            voxelCentersW.set(slow.map.voxels.occupied().map { it.centerW })
            nSlow.incrementAndGet()
            logger?.slowPath(RunLog.slowPathLine(slow.lastMapUpdate!!, t0, t1, s))
            logger?.stageTiming(RunLog.stageTimingLine(s.tCaptureNs, slow.lastStageNs))
            logger?.obstacles(RunLog.obstacleLines(s))
            logger?.clusters(slow.lastClusterDebug)
            slow.lastMapUpdate?.geometry?.let { logger?.geometry(s.tCaptureNs, it) }
            if (sitWorker != null && !paused && resetAfterNs.get() == resetMark) {
                sitJob.set(SitJob(d, s, resetMark))
                java.util.concurrent.locks.LockSupport.unpark(sitWorker)
            }
        }
    }

    // ---- 상황 판단 스레드 ----

    private fun situationLoop() {
        Process.setThreadPriority(BACKGROUND_WORK_PRIORITY)
        val sit = situations ?: return
        while (running) {
            val job = sitJob.getAndSet(null)
            if (job == null) {
                java.util.concurrent.locks.LockSupport.park(this)
                continue
            }
            if (sitReset.getAndSet(false)) {
                sit.reset()
                appliedSurface = null
            }
            if (paused || resetAfterNs.get() != job.resetMark) continue

            val nowNs = clockNs()
            val g = lastOutput.get()?.takeIf { nowNs - it.tBlockNs in 0L..maxInfoAgeNs }
            // 최신 모델 결과가 이 깊이보다 나중이면 쓰지 않는다. 이전에 적용한 마스크도 후보로 둔다.
            val surf = listOfNotNull(surface?.latest?.get(), latestSurface.get(), appliedSurface)
                .filter { it.tCaptureNs > job.resetMark && it.tCaptureNs <= job.depth.tCaptureNs }
                .maxByOrNull { it.tCaptureNs }
            if (!sit.due(job.depth.tCaptureNs, surf?.tCaptureNs)) continue
            appliedSurface = surf
            for (key in unsaid.toList()) {
                unsaid.remove(key)
                sit.speech.unsay(key)
            }

            val consumer = onSpeech
            val level = if (consumer != null) speechLevel.get() else null
            val t0 = System.nanoTime()
            val (requests, rec) = sit.detect(SituationInput(nowNs, job.depth, job.snapshot,
                g?.commands.orEmpty(), Situations.headingOf(g), surf, level))
            val said = if (useLiveSpeechArbiter) emptyList() else sit.speech.tick(nowNs, requests, level)
            logger?.situationTiming(rec.tNs, System.nanoTime() - t0)
            logger?.situation(said, rec)

            // 판단 도중 일시정지·RESET이 왔으면 결과를 내보내지 않는다.
            val finishedNs = clockNs()
            if (!running || paused || resetAfterNs.get() != job.resetMark ||
                finishedNs - job.depth.tCaptureNs !in 0L..maxInfoAgeNs) {
                lastSituationSpeech.set(null)
                said.forEach { sit.speech.unsay(it.key) }
                continue
            }
            if (useLiveSpeechArbiter) {
                val usable = job.snapshot.mapHealth == MapHealth.OK && job.snapshot.floorY != null &&
                    g?.state == GuidanceState.NORMAL
                lastSituationSpeech.set(if (usable) SituationSpeechFrame(job.resetMark, requests) else null)
            } else if (said.isNotEmpty() && consumer != null) {
                speechLevel.set(said.minOf { it.priority })
                consumer(said)
            }
        }
    }

    override fun close() {
        running = false
        lastSituationSpeech.set(null)
        lastObjectSpeech.set(null)
        wake.release()
        worker.join(2000)
        sitWorker?.let { java.util.concurrent.locks.LockSupport.unpark(it); it.join(2000) }
    }

    companion object {
        /**
         * 상황 판단·노면 모델 스레드의 nice 값(M65 E2: 같은 기기에서 느린 경로와 다퉈 처리 p95가 늘었다). 느린 경로(DISPLAY, −4)보다 몫이 작게.
         * VERIFY: Android는 10(BACKGROUND) 이상이면 스레드를 background 그룹(작은 코어 위주)으로 옮긴다고 알려져 5로 둔다(문서 확인 못 함).
         */
        const val BACKGROUND_WORK_PRIORITY = 5
    }
}

