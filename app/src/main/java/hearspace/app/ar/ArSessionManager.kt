package hearspace.app.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.PlaybackFailedException
import com.google.ar.core.exceptions.UnavailableException
import hearspace.app.TAG
import hearspace.core.types.DepthSensorUsage
import java.io.File
import java.util.EnumSet

/** 깊이 지원 확인 결과(F1)와 실제 설정한 모드, 고른 카메라 설정의 깊이 센서(ToF) 사용(M47). */
data class DepthSupport(
    val automatic: Boolean,
    val rawDepthOnly: Boolean,
    val modeUsed: Config.DepthMode,
    /** `CameraConfig.DepthSensorUsage` 이름. */
    val sensorUsed: String,
)

/**
 * ARCore 설치 확인·카메라 권한·세션 생성·깊이 모드 설정·재개/일시정지.
 * UI 스레드에서만 호출한다. 흐름은 공식 샘플(hello_ar_kotlin)의 onResume 처리와 같다.
 * [playbackMp4]가 있으면 카메라 대신 녹화 MP4를 재생한다(재생 모드, §9.2).
 * [sensorUsage]가 AUTO가 아니면 그 깊이 센서 사용의 카메라 설정을 고른다(M47 ToF 확인, `depth.sensorUsage`). 재생 모드에서는 고르지 않는다.
 */
class ArSessionManager(
    private val activity: Activity,
    private val playbackMp4: File? = null,
    private val sensorUsage: DepthSensorUsage = DepthSensorUsage.AUTO,
) {

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
                try {
                    depthSupport = configure(s)
                    // VERIFY: 재생 데이터셋은 세션이 일시정지 상태일 때(첫 resume 전) 지정해야 한다(ARCore Playback API 문서)
                    playbackMp4?.let { s.setPlaybackDatasetUri(Uri.fromFile(it)) }
                } catch (e: Exception) {
                    s.close() // 설정에 실패한 세션을 남기지 않는다(A1 FA13)
                    depthSupport = null
                    throw e
                }
                session = s
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore unavailable", e)
                return "ARCore 사용 불가: ${e.javaClass.simpleName}"
            } catch (e: PlaybackFailedException) {
                Log.e(TAG, "playback dataset failed: $playbackMp4", e)
                return "재생 파일을 열 수 없습니다"
            } catch (e: NoSensorConfigException) {
                Log.e(TAG, e.message ?: "no camera config")
                return "깊이 센서 설정(${sensorUsage.name})을 쓸 수 없습니다. 기기 설정 덮어쓰기를 확인하세요"
            }
        }
        return try {
            session!!.resume()
            null
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available", e)
            close()
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

    /**
     * 지원 카메라 설정을 로그에 남기고(M47), [sensorUsage]가 AUTO가 아니면 그 깊이 센서 사용의 설정 중 ARCore 기본 설정과 영상 크기·fps가
     * 같은 것을(없으면 첫 번째) 고른다. 크기가 다르면 ToF 효과와 섞이므로 로그에 남긴다.
     */
    private fun selectCameraConfig(s: Session) {
        val all = s.getSupportedCameraConfigs(CameraConfigFilter(s))
        all.forEach { Log.i(TAG, "camera config: ${describe(it)}") }
        val current = s.cameraConfig
        Log.i(TAG, "camera config (ARCore default): ${describe(current)}")
        if (sensorUsage == DepthSensorUsage.AUTO || playbackMp4 != null) return
        val want = CameraConfig.DepthSensorUsage.valueOf(sensorUsage.name)
        val candidates = s.getSupportedCameraConfigs(CameraConfigFilter(s).setDepthSensorUsage(EnumSet.of(want)))
        val pick = candidates.firstOrNull {
            it.imageSize == current.imageSize && it.textureSize == current.textureSize && it.fpsRange == current.fpsRange
        } ?: candidates.firstOrNull() ?: throw NoSensorConfigException("no camera config with depth sensor usage $want")
        // VERIFY: setCameraConfig는 세션이 일시정지(첫 resume 전)일 때 부르고 configure 전에 둔다(ARCore Session.setCameraConfig 문서)
        s.cameraConfig = pick
        Log.i(TAG, "camera config (selected $want): ${describe(pick)}")
    }

    private fun describe(c: CameraConfig) =
        "image ${c.imageSize} texture ${c.textureSize} fps ${c.fpsRange} depthSensor ${c.depthSensorUsage}"

    private class NoSensorConfigException(message: String) : Exception(message)

    private fun configure(s: Session): DepthSupport {
        selectCameraConfig(s)
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
        val sensor = s.cameraConfig.depthSensorUsage.name
        Log.i(TAG, "depth support: automatic=$automatic rawDepthOnly=$raw -> $mode, depth sensor $sensor")
        return DepthSupport(automatic, raw, mode, sensor)
    }

    companion object {
        /** onRequestPermissionsResult에서 구분할 요청 코드. */
        const val CAMERA_PERMISSION_REQUEST = 1
    }
}
