// phone/ContextCollector.kt
package com.lockjaw.claudeagent.phone

import android.content.*
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.lockjaw.claudeagent.data.*
import com.lockjaw.claudeagent.services.AgentNotificationService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.*

class ContextCollector(private val context: Context) {

    private val TAG = "ContextCollector"

    suspend fun collect(includeLocation: Boolean = false): PhoneContext = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        PhoneContext(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(now)),
            localTime = SimpleDateFormat("h:mm a", Locale.US).format(Date(now)),
            localDate = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.US).format(Date(now)),
            battery = getBattery(),
            network = getNetwork(),
            location = if (includeLocation) getLocation() else LocationInfo(
                city = "Tucson", region = "AZ", lat = 32.2226, lng = -110.9747
            ),
            device = DeviceInfo(
                model = Build.MODEL,
                brand = Build.BRAND,
                androidVersion = Build.VERSION.SDK_INT
            ),
            recentNotifications = AgentNotificationService.instance?.getRecentNotifications() ?: emptyList(),
            currentForegroundApp = getForegroundApp(),
            installedApps = runBlocking {
                try {
                    val json = AppPreferences(context).get(PrefKeys.INSTALLED_APPS_JSON).first()
                    if (!json.isNullOrBlank()) {
                        Gson().fromJson(json, object : TypeToken<Map<String, String>>() {}.type)
                    } else {
                        getInstalledApps() // fallback to live scan if cache empty
                    }
                } catch (e: Exception) { getInstalledApps() }
            }
        )
    }

    private fun getBattery(): BatteryInfo {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            BatteryInfo(
                level = if (scale > 0) (level * 100 / scale) else -1,
                charging = charging,
                temperature = temp
            )
        } catch (e: Exception) {
            BatteryInfo(-1, false, null)
        }
    }

    private fun getNetwork(): NetworkInfo {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val type = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "None"
            }
            val ssid = if (type == "WiFi") {
                val wm = context.applicationContext.getSystemService(WifiManager::class.java)
                wm.connectionInfo?.ssid?.replace("\"", "") ?: "Unknown"
            } else null
            NetworkInfo(type, network != null, ssid)
        } catch (e: Exception) {
            NetworkInfo("Unknown", false, null)
        }
    }

    private suspend fun getLocation(): LocationInfo? {
        return try {
            val lm = context.getSystemService(LocationManager::class.java)
            val fusedProvider = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                LocationManager.FUSED_PROVIDER else null
            val location = (fusedProvider?.let { runCatching { lm.getLastKnownLocation(it) }.getOrNull() })
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            location?.let { loc ->
                val geocoder = Geocoder(context, Locale.US)
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    var result: android.location.Address? = null
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { result = it.firstOrNull() }
                    listOfNotNull(result)
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1) ?: emptyList()
                }
                val address = addresses.firstOrNull()
                LocationInfo(
                    city = address?.locality,
                    region = address?.adminArea,
                    lat = loc.latitude,
                    lng = loc.longitude
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location failed: ${e.message}")
            null
        }
    }

    private fun getForegroundApp(): String? {
        return try {
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 1000 * 60, now
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) { null }
    }

    fun getInstalledApps(): Map<String, String> {
        return try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.GET_META_DATA)
                .associate { resolveInfo ->
                    val label = resolveInfo.loadLabel(pm).toString()
                    val pkg = resolveInfo.activityInfo.packageName
                    label to pkg
                }
                .toSortedMap()
        } catch (e: Exception) {
            android.util.Log.e("ContextCollector", "Failed to get installed apps: ${e.message}")
            emptyMap()
        }
    }
}
