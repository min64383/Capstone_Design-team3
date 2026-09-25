package walkassist.app.ui.dev

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import walkassist.core.types.ConfigException
import walkassist.core.types.ConfigLoader

/** M0 빈 개발 모드 홈. 설정 로드만 확인한다. 실시간·녹화·재생 메뉴는 M1 이후. */
class DevHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = try {
            val json = assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val config = ConfigLoader.load(json)
            Log.i(TAG, "config loaded: $config")
            "WalkAssist M0\n설정 로드 성공\nrepPoint=${config.repPoint.strategy}"
        } catch (e: ConfigException) {
            Log.e(TAG, "config load failed", e)
            "WalkAssist M0\n설정 로드 실패\n${e.message}"
        }
        setContentView(TextView(this).apply { text = status; textSize = 22f; setPadding(48, 96, 48, 48) })
    }

    private companion object {
        const val TAG = "WalkAssist"
        const val CONFIG_ASSET = "config/default.json"
    }
}
