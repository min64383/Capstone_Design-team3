package hearspace.app.ui.dev

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.TAG
import hearspace.core.types.ConfigException

/** 개발 모드 홈(§11.3): 실시간 / 녹화 / 재생 / 세션 목록. */
class DevHomeActivity : Activity() {

    private companion object {
        const val EXTRA_AUTO_START = "autoStart"
    }

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

        // 기기 자동화(M49 0단계, 디버그 빌드만): `am start -n hearspace.app/.ui.dev.DevHomeActivity --es autoStart live|<세션ID>`로
        // 실시간 또는 그 세션의 재생 화면을 바로 연다(화면을 누르지 않고 adb로 돌리기 위해. LiveScreen은 내보내지 않는다)
        if (configOk && hearspace.app.BuildConfig.DEBUG) intent.getStringExtra(EXTRA_AUTO_START)?.let { target ->
            val dir = if (target == "live") null else java.io.File(RecordScreen.sessionsRoot(this), target)
            if (dir == null || dir.isDirectory) startActivity(LiveScreen.intent(this, dir)) else Log.e(TAG, "autoStart: no session $target")
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = if (configOk) "HEARSPACE 개발 모드" else "설정 로드 실패 — logcat 확인"
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
        button("실시간", enabled = true) { startActivity(LiveScreen.intent(this, null)) }
        button("녹화", enabled = true) { startActivity(Intent(this, RecordScreen::class.java)) }
        button("재생", enabled = true) { startActivity(SessionList.pickIntent(this)) }
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
