package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Low-cost, read-only observation of *battery* temperature and level.
 * Not an SoC die thermometer, not proof of NPU execution, and not an estimate
 * of app-only power consumption. Battery history needs controlled tests.
 */
object BatteryThermalSampler {
    data class Snapshot(val batteryC: Float?, val level: Int?, val currentMa: Int?)
    fun read(context: Context): Snapshot {
        val state = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenth = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val lv = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val current = try {
            (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (_: Exception) { Int.MIN_VALUE }
        return Snapshot(
            if (tenth >= 0) tenth / 10f else null,
            if (scale > 0 && lv >= 0) lv * 100 / scale else null,
            if (current != Int.MIN_VALUE && current != 0) current / 1000 else null
        )
    }
}
