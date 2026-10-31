package hearspace.app.ui.user

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import hearspace.app.AppConfig
import hearspace.app.BuildConfig

/**
 * 설정(M45 시안 §4). 작동 화면의 설정 단추(시작 전에만 보임)로 연다.
 * 넣지 않은 것: 진동 끄기(소리가 끊겼을 때 알리는 길이 진동뿐, M40), 앱 안 음량(너무 낮추면 안내가 안 들림),
 * 안내 거리·민감도(명세의 가설 값, 개발 모드 실험 패널 소관), 개발 모드(MVP §11.3대로 별도 런처 아이콘).
 */
class SettingsActivity : Activity() {

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = userScreen("설정")
        body.section("사용 방법")
        body.item("사용 방법 듣기", "두 번 탭·길게 누르기와 소리 뜻") { open(InfoActivity.HOW_TO) }
        body.section("소리")
        body.item("헤드폰 확인", "왼쪽·오른쪽·앞·뒤에서 소리가 나요\n음량은 기기의 음량 버튼으로 조절해요") {
            startActivity(Intent(this, HeadphoneCheckActivity::class.java))
        }
        body.section("화면 테마", "작동 화면은 늘 검은 화면")
        body.addView(themeChoices(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.section("정보")
        AppConfig.overrideNotice(this)?.let { body.item(it) }
        body.item("ARCore 고지") { open(InfoActivity.ARCORE) }
        body.item("오픈소스 라이선스") { open(InfoActivity.LICENSES) }
        body.item("버전", "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})")
    }

    private fun open(kind: String) = startActivity(Intent(this, InfoActivity::class.java).putExtra(InfoActivity.EXTRA_KIND, kind))

    /** 단일 선택 묶음(TalkBack: "3개 중 1번째, 선택됨"). Android 12 이상은 바꾸면 시스템이 화면을 다시 그리고, 그 아래는 직접 다시 만든다. */
    private fun themeChoices() = RadioGroup(this).apply {
        val current = ThemePref.get(context)
        setPadding(dp(14), 0, dp(20), 0)
        for ((mode, label) in listOf(ThemePref.Mode.SYSTEM to "기기 설정 따르기", ThemePref.Mode.LIGHT to "밝게", ThemePref.Mode.DARK to "어둡게")) {
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                minHeight = dp(56)
                setPadding(dp(10), 0, 0, 0)
                isChecked = mode == current
                setOnClickListener {
                    if (ThemePref.get(context) == mode) return@setOnClickListener
                    ThemePref.set(context, mode)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) recreate()
                }
            }, RadioGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
}
