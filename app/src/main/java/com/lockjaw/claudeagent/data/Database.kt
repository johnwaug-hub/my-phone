// data/Database.kt
package com.lockjaw.claudeagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ─── DAOs ────────────────────────────────────────────────────────────────────

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessages(sessionId: String = "default"): Flow<List<Message>>

    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesSync(sessionId: String = "default"): List<Message>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String = "default")

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecentMessages(): List<Message>
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT 100")
    fun getLog(): Flow<List<ActionLogEntry>>

    @Insert
    suspend fun insert(entry: ActionLogEntry)

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

// ─── Database ────────────────────────────────────────────────────────────────

@Database(
    entities = [Message::class, ActionLogEntry::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        @Volatile private var INSTANCE: AgentDatabase? = null
        fun getInstance(context: Context): AgentDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "claude_agent.db"
                ).build().also { INSTANCE = it }
            }
    }
}

// ─── DataStore Preferences ───────────────────────────────────────────────────

// Kotlin 2.0 compatible: use PreferenceDataStoreFactory instead of delegate
private var _dataStore: DataStore<Preferences>? = null
fun Context.getDataStore(): DataStore<Preferences> {
    return _dataStore ?: PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { applicationContext.preferencesDataStoreFile("claude_prefs") }
    ).also { _dataStore = it }
}

object PrefKeys {
    val API_KEY = stringPreferencesKey("api_key")
    val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
    val INCLUDE_LOCATION = booleanPreferencesKey("include_location")
    val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
    val AUTO_EXECUTE = booleanPreferencesKey("auto_execute")
    val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
    val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    val MONITOR_NOTIFICATIONS = booleanPreferencesKey("monitor_notifications")
    val MONITOR_SMS = booleanPreferencesKey("monitor_sms")
    val PROACTIVE_MODE = booleanPreferencesKey("proactive_mode")
    val DEVICE_PROFILE = stringPreferencesKey("device_profile") // JSON-serialized DeviceProfile
    val INSTALLED_APPS_JSON = stringPreferencesKey("installed_apps_json")
}

class AppPreferences(private val context: Context) {
    val apiKey: Flow<String> = context.getDataStore().data.map { it[PrefKeys.API_KEY] ?: "" }
    val ttsEnabled: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.TTS_ENABLED] ?: false }
    val includeLocation: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.INCLUDE_LOCATION] ?: false }
    val hapticEnabled: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.HAPTIC_ENABLED] ?: true }
    val autoExecute: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.AUTO_EXECUTE] ?: false }
    val overlayEnabled: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.OVERLAY_ENABLED] ?: true }
    val wakeWordEnabled: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.WAKE_WORD_ENABLED] ?: false }
    val monitorNotifications: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.MONITOR_NOTIFICATIONS] ?: true }
    val monitorSms: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.MONITOR_SMS] ?: false }
    val proactiveMode: Flow<Boolean> = context.getDataStore().data.map { it[PrefKeys.PROACTIVE_MODE] ?: false }
    val deviceProfileJson: Flow<String> = context.getDataStore().data.map { it[PrefKeys.DEVICE_PROFILE] ?: "" }

    suspend fun getDeviceProfile(): DeviceProfile {
        val json = context.getDataStore().data.map { it[PrefKeys.DEVICE_PROFILE] ?: "" }.first()
        return if (json.isBlank()) DeviceProfile.autoDetect()
        else runCatching { com.google.gson.Gson().fromJson(json, DeviceProfile::class.java) }
            .getOrDefault(DeviceProfile.autoDetect())
    }

    suspend fun saveDeviceProfile(profile: DeviceProfile) {
        context.getDataStore().edit { it[PrefKeys.DEVICE_PROFILE] = com.google.gson.Gson().toJson(profile) }
    }

    fun <T> get(key: androidx.datastore.preferences.core.Preferences.Key<T>) =
        context.getDataStore().data.map { it[key] }

    suspend fun <T> set(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.getDataStore().edit { it[key] = value }
    }

    suspend fun initDefaultApiKey() {
        context.getDataStore().edit { prefs ->
            if (prefs[PrefKeys.DEVICE_PROFILE].isNullOrBlank()) {
                val detected = DeviceProfile.autoDetect()
                val defaultProfile = detected.copy(
                    userName = "John",
                    nickname = "Waugs",
                    location = "Tucson, AZ",
                    timezone = "America/Phoenix",
                    occupation = "1st grade teacher at Tully Magnet Elementary; software developer; disc golfer",
                    interests = "disc golf, app development, fabrication, concrete countertops, woodworking, blockchain, education",
                    frequentApps = "Putting Improver, Tournament Director Tools, Fantasy Disc Golf, Let's Throw, Disc Golf Scene, UDisc, Eternl, Android Studio",
                    customContext = "Runs lockjawdiscgolf.com and waugawork.com. Builds apps in React Native/Expo and Kotlin. Active in Cardano ecosystem. VFW Regional Teacher of the Year 2023.",
                    responseStyle = "concise"
                )
                prefs[PrefKeys.DEVICE_PROFILE] = com.google.gson.Gson().toJson(defaultProfile)
            }
        }
    }
}
