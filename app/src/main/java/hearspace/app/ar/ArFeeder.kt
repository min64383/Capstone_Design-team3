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
import hearspace.core.session.SessionReader
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.DepthSource
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState
import kotlin.math.abs

/**
 * GL 스레드 쪽 ARCore 입력(§9.1): `update()` → 자세 슬롯, 새 깊이면 복사해 깊이 슬롯. 처리는 하지 않는다.
 * 실시간·재생 화면(`LiveScreen`)과 사용자 모드가 같이 쓴다. [drawCamera]가 거짓이면 카메라 영상을 그리지 않는다(검은 화면).
 *
 * 시계: 실시간은 `elapsedRealtimeNanos()`(ARCore 프레임 시각과 같은 기준, F5). 재생은 프레임 시각이 녹화 당시 값이므로
 * 첫 프레임에서 (수신 시각 − 프레임 시각)을 빼 맞춘다(F8).
 */
class ArFeeder(private val config: Config, private val replay: Boolean, private val drawCamera: Boolean) {

    private val background = BackgroundRenderer()

    @Volatile
    var session: Session? = null

    @Volatile
    var pipeline: LivePipeline? = null

    @Volatile
    private var clockOffsetNs: Long? = null

    @Volatile
    var tracking = "-"
        private set

    @Volatile
    var playbackFinished = false
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
        if (frame.timestamp == lastFrameTNs) return null // 새 카메라 프레임이 아니다
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
        val p = pipeline ?: return camera
        p.publishPose(pose)

        val raw = config.depth.source == DepthSource.RAW
        val d = FrameAdapter.readDepth(frame, raw) { it != lastDepthTNs } ?: return camera
        val mm = d.mm ?: return camera
        lastDepthTNs = d.tNs
        // 깊이 시각에 가장 가까운, 이미 받은 자세(미래 자세 없음, SessionReader와 같은 규칙)
        val dp = recentPoses.minBy { abs(it.tCaptureNs - d.tNs) }
        if (dp.tracking != TrackingState.TRACKING) return camera
        val conf = if (raw) FrameAdapter.readConfidence(frame)?.values?.takeIf { it.size == mm.size } else null
        p.publishDepth(
            DepthFrame(
                tCaptureNs = d.tNs,
                depthMm = mm,
                confidence = conf,
                K = SessionReader.scaleTextureK(FrameAdapter.toIntrinsics(camera.textureIntrinsics), d.width, d.height),
                worldFromCam = dp.worldFromCam,
                source = if (raw) "arcore_raw_depth" else "arcore_depth",
            ),
        )
        return camera
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
