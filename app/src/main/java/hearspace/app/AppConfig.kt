package hearspace.app

import android.content.Context
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader

/**
 * 기본 설정과 연속음 선택 설정을 병합해 한 번 읽어 둔다. 앱 전용 외부 폴더에 [DEVICE_OVERRIDE_FILE]이 있으면 그 위에 덮어쓴다
 * (M13.4 기기 측정·녹화 설정을 다시 설치 없이 `adb push`로 바꾸기 위해. 팀 기본값은 그대로).
 */
object AppConfig {
    private const val CONFIG_ASSET = "config/default.json"
    private const val SONIFY_OVERRIDE_ASSET = "config/risk-continuous.override.json"
    /** `/sdcard/Android/data/<패키지>/files/config.override.json`. */
    const val DEVICE_OVERRIDE_FILE = "config.override.json"

    /** 기기 덮어쓰기를 쓴 설정(true)과 앱 자산만의 설정(false). */
    private val cached = HashMap<Boolean, Config>()

    /**
     * 설정을 읽는다. 형식 오류는 [hearspace.core.types.ConfigException].
     * [deviceOverride]가 false면 기기 파일을 읽지 않는다(사용자 모드: 기기 파일이 잘못됐을 때 앱 기본 설정으로 안내, A1 FA2).
     */
    fun get(context: Context, deviceOverride: Boolean = true): Config = synchronized(this) {
        cached.getOrPut(deviceOverride) {
            val base = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val override = context.assets.open(SONIFY_OVERRIDE_ASSET).bufferedReader().use { it.readText() }
            val device = if (deviceOverride) deviceOverrideFile(context)?.takeIf { it.isFile }?.readText() else null
            if (device != null) android.util.Log.i(TAG, "device config override: $device")
            ConfigLoader.load(base, listOfNotNull(override, device))
        }
    }

    /** 기기 덮어쓰기 파일 경로(없을 수도 있다). */
    fun deviceOverrideFile(context: Context): java.io.File? = context.getExternalFilesDir(null)?.let { java.io.File(it, DEVICE_OVERRIDE_FILE) }
}

/** 앱 자산의 HRIR(SADIE II D1, M6). */
fun loadHrtf(context: Context): hearspace.core.audio.Hrtf =
    context.assets.open("hrtf/sadie2_d1_48k.hrir").use { hearspace.core.audio.Hrtf.parse(it.readBytes()) }

/** Logcat 태그. `adb logcat -s HEARSPACE`. */
const val TAG = "HEARSPACE"

