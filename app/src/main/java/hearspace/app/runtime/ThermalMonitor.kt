package hearspace.app.runtime

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import hearspace.core.session.DeviceRow

/** 발열·배터리 상태를 읽어 `device.csv` 한 행으로 만든다(§9.3, F7). M1은 녹화 중 저장 스레드가 주기적으로 호출한다. */
class ThermalMonitor(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    /** 현재 상태. 발열 상태는 API 29 이상에서만 제공된다(S10 5G는 Android 12라 제공). */
    fun sample(): DeviceRow {
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
        val pct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        return DeviceRow(SystemClock.elapsedRealtimeNanos(), thermal, pct, audioOutputLatencyMs = null)
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
