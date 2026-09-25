package walkassist.app.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import walkassist.app.TAG

/** 깊이 지원 확인 결과(F1)와 실제 설정한 모드. */
data class DepthSupport(
    val automatic: Boolean,
    val rawDepthOnly: Boolean,
    val modeUsed: Config.DepthMode,
)

/**
 * ARCore 설치 확인·카메라 권한·세션 생성·깊이 모드 설정·재개/일시정지.
 * UI 스레드에서만 호출한다. 흐름은 공식 샘플(hello_ar_kotlin)의 onResume 처리와 같다.
 */
class ArSessionManager(private val activity: Activity) {

    /** 준비된 세션. [resume]이 성공한 뒤에만 null이 아니다. */
    var session: Session? = null
        private set

    /** 세션 생성 시 확인한 깊이 지원 여부. */
    var depthSupport: DepthSupport? = null
        private set

    private var installRequested = false

    /**
     * Activity.onResume에서 호출한다. 세션이 실행 중이면 null, 아니면 사용자에게 보일 사유를 돌려준다.
     * 권한 요청·ARCore 설치 화면으로 넘어가는 경우도 사유를 돌려주고, 돌아오면 다시 onResume에서 호출된다.
     */
    fun resume(): String? {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return "카메라 권한이 필요합니다"
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return "ARCore 설치 중"
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val s = Session(activity)
                depthSupport = configure(s)
                session = s
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore unavailable", e)
                return "ARCore 사용 불가: ${e.javaClass.simpleName}"
            }
        }
        return try {
            session!!.resume()
            null
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available", e)
            session = null
            "카메라를 열 수 없습니다. 앱을 다시 시작하세요"
        }
    }

    /** Activity.onPause에서 호출. GLSurfaceView.onPause 뒤에 호출해야 GL 스레드와 겹치지 않는다(샘플 순서와 같음). */
    fun pause() {
        session?.pause()
    }

    /** Activity.onDestroy에서 호출. */
    fun close() {
        session?.close()
        session = null
    }

    private fun configure(s: Session): DepthSupport {
        val automatic = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val raw = s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        // AUTOMATIC이면 일반 깊이와 원시 깊이를 모두 얻을 수 있다(F6 비교).
        val mode = when {
            automatic -> Config.DepthMode.AUTOMATIC
            raw -> Config.DepthMode.RAW_DEPTH_ONLY
            else -> Config.DepthMode.DISABLED
        }
        val config = Config(s).apply { depthMode = mode }
        s.configure(config)
        Log.i(TAG, "depth support: automatic=$automatic rawDepthOnly=$raw -> $mode")
        return DepthSupport(automatic, raw, mode)
    }

    companion object {
        /** onRequestPermissionsResult에서 구분할 요청 코드. */
        const val CAMERA_PERMISSION_REQUEST = 1
    }
}
