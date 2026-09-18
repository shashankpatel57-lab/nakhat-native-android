package com.familyconnect.app.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Process
import com.familyconnect.app.model.DeviceSnapshot

object DeviceRepository {
    fun snapshot(context: Context): DeviceSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val battery = if (level >= 0) ((level * 100f) / scale).toInt() else 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                else -> "Connected"
            }
        } ?: "Offline"

        val usageAccess = hasUsageAccess(context)
        val lastApp = if (usageAccess) lastUsedApp(context) else null

        val p = context.getSharedPreferences("tracking", Context.MODE_PRIVATE)
        val hasLocation = p.contains("lat") && p.contains("lon")
        val time = p.getLong("time", 0L)
        val age = if (time > 0) ((System.currentTimeMillis() - time) / 1000L).coerceAtLeast(0) else null

        return DeviceSnapshot(
            battery = battery,
            charging = charging,
            network = network,
            usageAccess = usageAccess,
            lastUsedApp = lastApp,
            latitude = if (hasLocation) java.lang.Double.longBitsToDouble(p.getLong("lat", 0L)) else null,
            longitude = if (hasLocation) java.lang.Double.longBitsToDouble(p.getLong("lon", 0L)) else null,
            speedKmh = p.getInt("speed", 0),
            locationAgeSeconds = age
        )
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun lastUsedApp(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 24 * 60 * 60 * 1000L, end)
            val recent = stats.maxByOrNull { it.lastTimeUsed } ?: return null
            if (recent.packageName == context.packageName) return "Family Connect"
            val ai = context.packageManager.getApplicationInfo(recent.packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { null }
    }
}
