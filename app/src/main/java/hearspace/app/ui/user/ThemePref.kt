package hearspace.app.ui.user

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * 스플래시·설정 화면의 테마(M45). 작동 화면은 늘 검정이라 따르지 않는다.
 * Android 12 이상은 시스템(UiModeManager)이 앱 테마를 기억해 스플래시까지 맞춘다. 그 아래는 설정 화면들이 [wrap]으로 맞추고,
 * 스플래시(시작 창)는 시스템이 그리므로 기기 설정을 따른다.
 */
object ThemePref {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private const val PREFS = "user_settings"
    private const val KEY = "theme"

    fun get(context: Context): Mode =
        runCatching { Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: Mode.SYSTEM.name) }
            .getOrDefault(Mode.SYSTEM)

    fun set(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // VERIFY: MODE_NIGHT_AUTO가 앱 단위에서 "기기 설정 따르기"로 동작하는지 기기에서 확인(문서: 시스템 야간 모드를 따름)
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (mode) {
                    Mode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    Mode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    Mode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    /** Android 11 이하: 화면의 설정을 덮어써 라이트·다크 리소스를 고르게 한다(Activity.attachBaseContext에서). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return base
        val night = when (get(base)) {
            Mode.SYSTEM -> return base
            Mode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Mode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val c = Configuration(base.resources.configuration)
        c.uiMode = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(c)
    }
}
