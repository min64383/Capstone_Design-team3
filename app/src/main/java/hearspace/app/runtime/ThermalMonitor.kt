package hearspace.app.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import hearspace.core.session.DeviceRow

/** 발열·배터리 상태를 읽어 `device.csv` 한 행으로 만든다(§9.3, F7). M1은 녹화 중 저장 스레드가 주기적으로 호출한다. */
class ThermalMonitor(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    /** 현재 상태. 발열 상태는 API 29 이상에서만 제공된다(S10 5G는 Android 12라 제공). */
    fun sample(): DeviceRow {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
        val pct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        // M63: 배터리 온도(고정 방송, 수신기 없이 마지막 값만 읽음; dumpsys battery와 같은 0.1 °C 단위로 봄) // VERIFY: EXTRA_TEMPERATURE 단위
        val temp = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        // 발열 여유(API 30+): 지원하지 않으면 NaN → device.csv 빈칸. 1 s 간격 호출은 문서의 권장 빈도 안
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power?.getThermalHeadroom(0) else null
        val heapMb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024f * 1024f) }
        return DeviceRow(SystemClock.elapsedRealtimeNanos(), thermal, pct, audioOutputLatencyMs = null, temp, headroom, heapMb)
    }

    /** 발열 상태 코드를 짧은 이름으로(표시용). */
    fun thermalName(status: Int?): String = when (status) {
        null -> "N/A"
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> status.toString()
    }
}
