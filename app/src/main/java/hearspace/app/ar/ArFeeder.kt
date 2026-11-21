package hearspace.app.ar

import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import hearspace.app.TAG
import hearspace.app.render.BackgroundRenderer
import hearspace.app.runtime.LivePipeline
import hearspace.app.runtime.SurfaceModel
import hearspace.core.replay.VisionFrames
import hearspace.core.session.SessionReader
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.DepthSource
import hearspace.core.types.LabelImage
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState
import kotlin.math.abs

/**
 * GL 스레드 쪽 ARCore 입력(§9.1): `update()` → 자세 슬롯, 새 깊이면 복사해 깊이 슬롯. 처리는 하지 않는다.
 * 실시간·재생 화면(`LiveScreen`)과 사용자 모드가 같이 쓴다. [drawCamera]가 거짓이면 카메라 영상을 그리지 않는다(검은 화면).
 *
 * 시계: 실시간은 `elapsedRealtimeNanos()`(ARCore 프레임 시각과 같은 기준, F5). 재생은 프레임 시각이 녹화 당시 값이므로
 * 첫 프레임에서 (수신 시각 − 프레임 시각)을 빼 맞춘다(F8).
 *
 * 의미 모드(M49): 세션에서 켜져 있으면 새 의미 영상마다 복사한다. [semanticSaver]가 있으면(재생 모드 "의미 영상 저장") 세션 폴더에 쓴다.
 * 설정이 라벨·원시 깊이를 쓰면([VisionFrames.needed]) 깊이 장마다 그 프레임까지 받은 가장 최근 의미 영상과 원시 깊이 장을 붙인다(PC 재생과 같은 규칙).
 */
class ArFeeder(private val config: Config, private val replay: Boolean, private val drawCamera: Boolean) {

    private val background = BackgroundRenderer()

    @Volatile
    var session: Session? = null

    @Volatile
    var pipeline: LivePipeline? = null

    /** 노면 모델(M65). 있으면 추적 중일 때 그 차례의 CPU 영상을 복사해 넘긴다(복사만, 처리는 모델 스레드). */
    @Volatile
    var surface: SurfaceModel? = null

    @Volatile
    private var clockOffsetNs: Long? = null

    @Volatile
    var tracking = "-"
        private set

    @Volatile
    var playbackFinished = false
        private set

    /** 재생 모드에서 의미 영상을 저장할 곳(UI 스레드가 실행마다 바꾼다). */
    @Volatile
    var semanticSaver: SemanticSaver? = null

    /** 받은 새 의미 영상 수와 마지막 크기(화면 표시). */
    @Volatile
    var nSemantic = 0L
        private set

    @Volatile
    var semanticSize = "-"
        private set

    // GL 스레드 전용
    private var textureBound: Session? = null
    private var viewportChanged = false
    var viewportW = 0
        private set
    var viewportH = 0
        private set
    private var lastFrameTNs = Long.MIN_VALUE
    private var lastDepthTNs = Long.MIN_VALUE
    private var lastSemanticTNs = Long.MIN_VALUE
    private var semanticOn = false
    /** 의미 영상·원시 깊이를 깊이 장에 붙이는 설정인지(재생과 같은 조건). */
    private val vision = VisionFrames.needed(config)
    private var lastLabels: LabelImage? = null
    private var lastRaw: DepthFrame? = null
    private var lastRawTNs = Long.MIN_VALUE
    private val recentPoses = ArrayDeque<PoseFrame>()

    /** 새 실행을 시작할 때(UI 스레드). */
    fun resetClock() {
        clockOffsetNs = if (replay) null else 0L
        playbackFinished = false
    }

    /** 자세와 같은 시계의 현재 시각. */
    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos() - (clockOffsetNs ?: 0L)

    fun onSurfaceCreated() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureBound = null
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportW = width
        viewportH = height
        viewportChanged = true
    }

    /** 한 프레임. 새 카메라 프레임이면 그 카메라를 돌려준다(오버레이용), 아니면 null. */
    fun onDrawFrame(displayRotation: Int): Camera? {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return null
        if (textureBound !== s) {
            s.setCameraTextureName(background.textureId)
            textureBound = s
            semanticOn = s.config.semanticMode == com.google.ar.core.Config.SemanticMode.ENABLED
            viewportChanged = true
        }
        if (viewportChanged) {
            s.setDisplayGeometry(displayRotation, viewportW, viewportH)
            viewportChanged = false
        }
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available during update", e)
            return null
        }
        if (drawCamera) background.draw(frame)
        if (replay && s.playbackStatus == PlaybackStatus.FINISHED) playbackFinished = true
        // 새 카메라 프레임이 아니다. 시각 0은 아직 카메라 영상이 없는 프레임이다(재생 첫 update가 0을 주면 재생 시계가 세션 시각과 어긋났다, M65 E2)
        if (frame.timestamp == 0L || frame.timestamp == lastFrameTNs) return null
        lastFrameTNs = frame.timestamp
        if (clockOffsetNs == null) clockOffsetNs = SystemClock.elapsedRealtimeNanos() - frame.timestamp

        val camera = frame.camera
        tracking = camera.trackingState.name
        val pose = PoseFrame(
            frame.timestamp,
            FrameAdapter.toCore(camera.trackingState),
            SessionReader.toWorldFromCv(FrameAdapter.toPoseGl(camera.pose)),
        )
        recentPoses.addLast(pose)
        if (recentPoses.size > RECENT_POSES) recentPoses.removeFirst()
        if (semanticOn) readSemantic(frame, camera)
        val p = pipeline ?: return camera
        p.publishPose(pose)
        if (vision) readRaw(frame, camera, p)
        surface?.let { sm ->
            if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING && sm.wants(frame.timestamp)) {
                val t0 = System.nanoTime()
                val img = FrameAdapter.readCameraNv21(frame)
                p.logInput(frame.timestamp, "CAMERA", img?.tNs, System.nanoTime() - t0, if (img == null) FrameAdapter.lastFailure ?: "NULL" else "NEW", null)
                if (img != null) sm.offer(img, pose.worldFromCam, FrameAdapter.toIntrinsics(camera.imageIntrinsics))
            }
        }

        // M61: 깊이는 프레임마다 결과를 남긴다(깊이가 느린 경로로 넘어가지 않은 구간의 이유)
        val trk = if (camera.trackingState == com.google.ar.core.TrackingState.TRACKING) "TRACKING" else "${camera.trackingState.name}:${camera.trackingFailureReason.name}"
        val raw = config.depth.source == DepthSource.RAW
        val t0 = System.nanoTime()
        val d = FrameAdapter.readDepth(frame, raw) { it != lastDepthTNs }
        if (d == null) {
            p.logInput(frame.timestamp, "DEPTH", null, System.nanoTime() - t0, FrameAdapter.lastFailure ?: "NULL", trk)
            return camera
        }
        val mm = d.mm
        if (mm == null) {
            p.logInput(frame.timestamp, "DEPTH", d.tNs, System.nanoTime() - t0, "SAME", trk)
            return camera
        }
        lastDepthTNs = d.tNs
        // 깊이 시각에 가장 가까운, 이미 받은 자세(미래 자세 없음, SessionReader와 같은 규칙)
        val dp = recentPoses.minBy { abs(it.tCaptureNs - d.tNs) }
        if (dp.tracking != TrackingState.TRACKING) {
            p.logInput(frame.timestamp, "DEPTH", d.tNs, System.nanoTime() - t0, "NOT_TRACKING", trk)
            return camera
        }
        val conf = if (raw) FrameAdapter.readConfidence(frame)?.values?.takeIf { it.size == mm.size } else null
        p.publishDepth(
            DepthFrame(
                tCaptureNs = d.tNs,
                depthMm = mm,
                confidence = conf,
                K = SessionReader.scaleTextureK(FrameAdapter.toIntrinsics(camera.textureIntrinsics), d.width, d.height),
                worldFromCam = dp.worldFromCam,
                source = if (raw) "arcore_raw_depth" else "arcore_depth",
                labels = if (vision) lastLabels else null,
                raw = if (vision) lastRaw else null,
            ),
        )
        p.logInput(frame.timestamp, "DEPTH", d.tNs, System.nanoTime() - t0, "PUBLISHED", trk)
        return camera
    }

    /** 새 의미 영상이면 복사해 [lastLabels]로 둔다. 자세는 영상 촬영 시각에 가장 가까운, 이미 받은 프레임의 것(VisionFrames와 같은 규칙). */
    private fun readSemantic(frame: com.google.ar.core.Frame, camera: Camera) {
        semanticSaver?.let { it.onFrame(frame); return }
        val t0 = System.nanoTime()
        val c = FrameAdapter.readSemantic(frame) { t -> (t != lastSemanticTNs).also { lastSemanticTNs = t } } ?: return
        pipeline?.logInput(frame.timestamp, "LABEL", c.tNs, System.nanoTime() - t0, "NEW", null)
        nSemantic++
        semanticSize = "${c.width}x${c.height}"
        if (!vision) return
        val pose = recentPoses.minBy { abs(it.tCaptureNs - c.tNs) }
        val k = VisionFrames.labelIntrinsics(FrameAdapter.toIntrinsics(camera.textureIntrinsics), FrameAdapter.toIntrinsics(camera.imageIntrinsics), c.width, c.height)
        lastLabels = LabelImage(c.tNs, c.labels, k, pose.worldFromCam)
    }

    /** 새 원시 깊이 장이면 신뢰도와 함께 복사해 [lastRaw]로 둔다. 그 시각 자세가 추적 중이 아니면 비운다(SessionReader.rawDepthAt과 같은 규칙). */
    private fun readRaw(frame: com.google.ar.core.Frame, camera: Camera, p: LivePipeline) {
        val t0 = System.nanoTime()
        val d = FrameAdapter.readDepth(frame, raw = true) { it != lastRawTNs } ?: return
        val mm = d.mm ?: return
        lastRawTNs = d.tNs
        val dp = recentPoses.minBy { abs(it.tCaptureNs - d.tNs) }
        p.logInput(frame.timestamp, "RAW", d.tNs, System.nanoTime() - t0, if (dp.tracking != TrackingState.TRACKING) "NOT_TRACKING" else "NEW", null)
        lastRaw = if (dp.tracking != TrackingState.TRACKING) null else DepthFrame(
            tCaptureNs = d.tNs,
            depthMm = mm,
            confidence = FrameAdapter.readConfidence(frame)?.values?.takeIf { it.size == mm.size },
            K = SessionReader.scaleTextureK(FrameAdapter.toIntrinsics(camera.textureIntrinsics), d.width, d.height),
            worldFromCam = dp.worldFromCam,
            source = "arcore_raw_depth",
        )
    }

    companion object {
        private const val RECENT_POSES = 30
    }
}

/** 화면 회전(`Surface.ROTATION_*`). ARCore `setDisplayGeometry`용. */
fun android.app.Activity.displayRotationCompat(): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        display!!.rotation
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }
