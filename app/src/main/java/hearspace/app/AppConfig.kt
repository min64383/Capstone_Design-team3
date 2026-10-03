package hearspace.app

import android.content.Context
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader

/** 기본 설정과 연속음 선택 설정을 병합해 한 번 읽어 둔다. */
object AppConfig {
    private const val CONFIG_ASSET = "config/default.json"
    private const val SONIFY_OVERRIDE_ASSET = "config/risk-continuous.override.json"

    @Volatile
    private var cached: Config? = null

    /** 설정을 읽는다. 형식 오류는 [hearspace.core.types.ConfigException]. */
    fun get(context: Context): Config = cached ?: synchronized(this) {
        cached ?: run {
            val base = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val override = context.assets.open(SONIFY_OVERRIDE_ASSET).bufferedReader().use { it.readText() }
            ConfigLoader.load(base, override).also { cached = it }
        }
    }
}

/** 앱 자산의 HRIR(SADIE II D1, M6). */
fun loadHrtf(context: Context): hearspace.core.audio.Hrtf =
    context.assets.open("hrtf/sadie2_d1_48k.hrir").use { hearspace.core.audio.Hrtf.parse(it.readBytes()) }

/** Logcat 태그. `adb logcat -s HEARSPACE`. */
const val TAG = "HEARSPACE"

