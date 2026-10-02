// data/Models.kt
package com.lockjaw.claudeagent.data

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── Message ────────────────────────────────────────────────────────────────

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,           // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionsJson: String? = null,  // serialized list of PhoneAction
    val sessionId: String = "default"
)

// ─── Action Log ─────────────────────────────────────────────────────────────

@Entity(tableName = "action_log")
data class ActionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionsJson: String,
    val resultsJson: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Notification Cache ──────────────────────────────────────────────────────

data class CachedNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Phone Action (Claude's structured output) ───────────────────────────────

data class PhoneAction(
    val type: String,
    // send_sms / make_call / send_email
    val to: String? = null,
    val message: String? = null,
    val subject: String? = null,
    val body: String? = null,        // email body, Tasker variables JSON, shell commands
    // set_brightness / set_volume
    val value: Float? = null,
    val stream: String? = null,
    // open_app
    val packageName: String? = null,
    val deepLink: String? = null,
    // open_settings
    val setting: String? = null,
    // copy_clipboard / speak / notification
    val text: String? = null,
    val title: String? = null,
    // create_calendar_event
    val startDate: String? = null,
    val endDate: String? = null,
    val notes: String? = null,       // applyGradientTo for ThemePark; general notes
    val location: String? = null,
    // search_contacts
    val query: String? = null,
    // toggle
    val enabled: Boolean? = null,
    // click_ui_element (accessibility)
    val elementText: String? = null,
    val elementId: String? = null,
    // good_lock_refresh / module name
    val module: String? = null,
    val instructions: String? = null,
    // create_reminder
    val triggerMs: Long? = null,
    // reply_notification
    val notificationKey: String? = null,
    val reply: String? = null,
    // vibrate pattern
    val pattern: List<Long>? = null
)

data class ActionResult(
    val action: PhoneAction,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)

// ─── Claude API ──────────────────────────────────────────────────────────────

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeResponse(
    val text: String,
    val actions: List<PhoneAction>,
    val rawResponse: String
)

// ─── Phone Context ───────────────────────────────────────────────────────────

data class PhoneContext(
    val timestamp: String,
    val localTime: String,
    val localDate: String,
    val battery: BatteryInfo,
    val network: NetworkInfo,
    val location: LocationInfo?,
    val device: DeviceInfo,
    val recentNotifications: List<CachedNotification> = emptyList(),
    val currentForegroundApp: String? = null,
    val installedApps: Map<String, String> = emptyMap() // "App Name" -> "package.name"
)

data class BatteryInfo(val level: Int, val charging: Boolean, val temperature: Float?)
data class NetworkInfo(val type: String, val connected: Boolean, val ssid: String?)
data class LocationInfo(val city: String?, val region: String?, val lat: Double, val lng: Double)
data class DeviceInfo(val model: String, val brand: String, val androidVersion: Int)

// ─── Converters for Room ─────────────────────────────────────────────────────

class Converters {
    private val gson = Gson()
    @TypeConverter fun fromActionList(value: List<PhoneAction>?): String = gson.toJson(value)
    @TypeConverter fun toActionList(value: String): List<PhoneAction>? {
        val type = object : TypeToken<List<PhoneAction>>() {}.type
        return gson.fromJson(value, type)
    }
}
