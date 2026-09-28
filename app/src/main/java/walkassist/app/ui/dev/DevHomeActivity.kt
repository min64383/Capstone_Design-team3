package walkassist.app.ui.dev

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import walkassist.app.AppConfig
import walkassist.app.TAG
import walkassist.core.types.ConfigException

/** 개발 모드 홈(§11.3): 실시간 / 녹화 / 재생 / 세션 목록. 실시간·재생은 M7에서 연결한다. */
class DevHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configOk = try {
            val config = AppConfig.get(this)
            Log.i(TAG, "config loaded: $config")
            true
        } catch (e: ConfigException) {
            Log.e(TAG, "config load failed", e)
            false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = if (configOk) "WalkAssist 개발 모드" else "설정 로드 실패 — logcat 확인"
            textSize = 22f
        })
        fun button(label: String, enabled: Boolean, onClick: () -> Unit) {
            root.addView(
                Button(this).apply {
                    text = label
                    isEnabled = enabled && configOk
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        button("실시간 (M7)", enabled = false) {}
        button("녹화", enabled = true) { startActivity(Intent(this, RecordScreen::class.java)) }
        button("재생 (M7)", enabled = false) {}
        button("세션 목록", enabled = true) { startActivity(Intent(this, SessionList::class.java)) }
        button(
            "오디오 테스트",
            enabled = true,
        ) {
            startActivity(
                Intent(
                    this,
                    AudioTestActivity::class.java,
                )
            )
        }
        setContentView(root)
    }
}
