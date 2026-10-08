package hearspace.app.runtime

import android.os.Process
import android.util.Log
import hearspace.app.TAG
import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.MapAction
import hearspace.core.pipeline.FastPath
import hearspace.core.pipeline.SlowPath
import hearspace.core.session.RunLog
import hearspace.core.types.AlertKind
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 스레드 배치(§9.1). 스레드 사이 전달은 모두 최신 값 1개 교체(`AtomicReference`)다.
 * - GL 스레드: [publishPose], [publishDepth]
 * - 느린 경로 워커(이 클래스가 소유): 최신 깊이 → `SlowPath.process` → [snapshot]
 * - 오디오 스레드: [renderBlock]이 블록마다 최신 자세·스냅샷으로 `FastPath.compute` → `BinauralRenderer`
 * 예외 하나: 맵 명령(RESET/SCALE)은 덮어쓰면 RESET이 사라질 수 있어 강한 쪽을 남기도록 합친다.
 * [clockNs]는 자세 시각과 같은 시계의 현재 시각이다(실시간·재생 모드가 다름, [LiveScreen] 참고).
 */
class LivePipeline(
    private val config: Config,
    hrtf: Hrtf,
    private val logger: RunLogger?,
    private val clockNs: () -> Long,
) : AutoCloseable {

    private val fast = FastPath(config)
    private val slow = SlowPath(config)
    private val renderer = BinauralRenderer(config, hrtf)

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
    val lastHead = AtomicReference<HeadPose?>(null)
    val voxelCentersW = AtomicReference<List<Vec3>>(emptyList())

    /** 사용자 일시정지(§7.5 PAUSED, UI 스레드가 쓴다). */
    @Volatile
    var paused = false

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

    // ---- GL 스레드 ----

    fun publishPose(p: PoseFrame) = pose.set(p)

    fun publishDepth(d: DepthFrame) {
        depth.set(d)
        wake.release()
    }

    // ---- 오디오 스레드 ----

    /** 블록 하나를 [out](2 × blockSize, L R 교차)에 렌더한다. 자세가 아직 없으면 알림음만. */
    fun renderBlock(out: FloatArray) {
        requestedAlert.getAndSet(null)?.let {
            renderer.alert(it)
            onAlert?.invoke(it)
        }
        val p = pose.get()
        if (p == null) { // 카메라 시작 전: 알림음만(음원 없음)
            renderer.render(GuidanceOutput(clockNs(), GuidanceState.UNKNOWN, emptyList(), Float.NaN, null), out)
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
                mapAction.set(MapAction.RESET)
                wake.release()
            }
            MapAction.SCALE -> {
                mapAction.compareAndSet(MapAction.NONE, a)
                wake.release()
            }
        }
        fast.headingW?.let { headingW.set(it) }
        renderer.render(g, out)
        lastOutput.set(g)
        lastHead.set(fast.head)
        // ponytail: 블록마다 문자열 로그(약 190줄/초). 할당이 끊김을 만들면 N블록마다로 줄인다(§10.1 허용).
        logger?.guidance(RunLog.guidanceLines(g, p.tCaptureNs, snap?.tCaptureNs))
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
            logger?.obstacles(RunLog.obstacleLines(s))
            logger?.clusters(slow.lastClusterDebug)
        }
    }

    override fun close() {
        running = false
        wake.release()
        worker.join(2000)
    }
}
