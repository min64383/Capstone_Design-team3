package hearspace.app.runtime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import hearspace.core.types.AlertKind
import hearspace.core.types.HapticsConfig

/** 상태 전이별 진동(§9.3, 가설): 준비 완료 짧게 1회, 일시정지 짧게 2회, 확인 불가 길게, 종료 길게. 어느 스레드에서 불러도 된다. */
class Haptics(context: Context, private val cfg: HapticsConfig) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun on(kind: AlertKind) {
        when (kind) {
            AlertKind.READY -> once(cfg.readyMs)
            AlertKind.PAUSE -> twice(cfg.pauseMs, cfg.pauseGapMs)
            AlertKind.UNKNOWN -> once(cfg.unknownMs)
            AlertKind.START, AlertKind.WAITING -> Unit // 소리만
        }
    }

    fun exit() = once(cfg.exitMs)

    private fun once(ms: Int) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun twice(ms: Int, gapMs: Int) {
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, ms.toLong(), gapMs.toLong(), ms.toLong()), -1))
    }
}
