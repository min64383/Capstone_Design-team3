package walkassist.app

import android.content.Context
import walkassist.core.types.Config
import walkassist.core.types.ConfigLoader

/** 앱 자산의 기본 설정을 한 번 읽어 둔다. 실험 패널 덮어쓰기는 M7 이후. */
object AppConfig {
    private const val CONFIG_ASSET = "config/default.json"

    @Volatile
    private var cached: Config? = null

    /** 설정을 읽는다. 형식 오류는 [walkassist.core.types.ConfigException]. */
    fun get(context: Context): Config = cached ?: synchronized(this) {
        cached ?: context.assets.open(CONFIG_ASSET).bufferedReader().use { ConfigLoader.load(it.readText()) }
            .also { cached = it }
    }
}

/** Logcat 태그. `adb logcat -s WalkAssist`. */
const val TAG = "WalkAssist"
