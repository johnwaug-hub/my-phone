// ui/AgentViewModel.kt
package com.lockjaw.claudeagent.ui

import android.app.Application
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.*
import android.widget.Toast
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lockjaw.claudeagent.data.*
import com.lockjaw.claudeagent.services.ClaudeAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class UiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val actions: List<PhoneAction> = emptyList(),
    val actionResults: List<ActionResult> = emptyList(),
    val pendingConfirm: Boolean = false
)

data class AgentUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val pendingActions: List<PhoneAction> = emptyList(),
    val error: String? = null,
    val isListening: Boolean = false
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val db = AgentDatabase.getInstance(application)
    private val prefs = AppPreferences(application)

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    val apiKey = prefs.apiKey
    val ttsEnabled = prefs.ttsEnabled
    val autoExecute = prefs.autoExecute
    val hapticEnabled = prefs.hapticEnabled
    val includeLocation = prefs.includeLocation
    val overlayEnabled = prefs.overlayEnabled
    val wakeWordEnabled = prefs.wakeWordEnabled
    val monitorNotifications = prefs.monitorNotifications
    val monitorSms = prefs.monitorSms
    val proactiveMode = prefs.proactiveMode

    // Room messages stream
    val messages: StateFlow<List<UiMessage>> = db.messageDao()
        .getMessages("default")
        .map { list ->
            list.map { msg ->
                val actions = msg.actionsJson?.let {
                    val type = object : TypeToken<List<PhoneAction>>() {}.type
                    gson.fromJson<List<PhoneAction>>(it, type)
                } ?: emptyList()
                UiMessage(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    actions = actions
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Broadcast receiver for service events
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.lockjaw.claudeagent.MESSAGE" -> {
                    val role = intent.getStringExtra("role") ?: return
                    if (role == "assistant") {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                "com.lockjaw.claudeagent.ERROR" -> {
                    val error = intent.getStringExtra("error")
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    android.util.Log.e("MyPhone", "API ERROR FULL: $error")
                    Toast.makeText(context, error ?: "Unknown error", Toast.LENGTH_LONG).show()
                }
                "com.lockjaw.claudeagent.PENDING_ACTIONS" -> {
                    val actionsJson = intent.getStringExtra("actions") ?: return
                    val type = object : TypeToken<List<PhoneAction>>() {}.type
                    val actions = gson.fromJson<List<PhoneAction>>(actionsJson, type)
                    _uiState.update { it.copy(pendingActions = actions, isLoading = false) }
                }
                "com.lockjaw.claudeagent.ACTION_RESULTS" -> {
                    _uiState.update { it.copy(pendingActions = emptyList()) }
                }
            }
        }
    }

    fun registerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction("com.lockjaw.claudeagent.MESSAGE")
            addAction("com.lockjaw.claudeagent.ERROR")
            addAction("com.lockjaw.claudeagent.PENDING_ACTIONS")
            addAction("com.lockjaw.claudeagent.ACTION_RESULTS")
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
    }

    fun unregisterReceiver(context: Context) {
        try { LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    fun sendMessage(context: Context, text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        ClaudeAgentService.sendMessage(context, text.trim())
    }

    fun confirmActions(context: Context) {
        val actions = _uiState.value.pendingActions
        if (actions.isEmpty()) return
        _uiState.update { it.copy(pendingActions = emptyList()) }
        viewModelScope.launch {
            ClaudeAgentService.instance?.executeConfirmedActions(actions, "default")
        }
    }

    fun rejectActions() {
        _uiState.update { it.copy(pendingActions = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSession(context: Context) {
        ClaudeAgentService.instance?.clearSession("default")
    }

    fun setListening(listening: Boolean) {
        _uiState.update { it.copy(isListening = listening) }
    }

    suspend fun <T> setSetting(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        prefs.set(key, value)
    }

    val actionLog = db.actionLogDao().getLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceProfileJson = prefs.deviceProfileJson
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    suspend fun getDeviceProfile() = prefs.getDeviceProfile()

    suspend fun saveDeviceProfile(profile: com.lockjaw.claudeagent.data.DeviceProfile) {
        prefs.saveDeviceProfile(profile)
    }
}
