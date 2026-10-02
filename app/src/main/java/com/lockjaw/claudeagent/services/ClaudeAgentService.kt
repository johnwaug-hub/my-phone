// services/ClaudeAgentService.kt
package com.lockjaw.claudeagent.services

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.lockjaw.claudeagent.MainActivity
import com.lockjaw.claudeagent.claude.ClaudeApiClient
import com.lockjaw.claudeagent.claude.ScreenEventBus
import com.lockjaw.claudeagent.data.*
import com.lockjaw.claudeagent.workers.AppScanWorker
import com.lockjaw.claudeagent.phone.ContextCollector
import com.lockjaw.claudeagent.phone.TaskerBridge
import com.lockjaw.claudeagent.phone.ShizukuBridge
import com.lockjaw.claudeagent.phone.PhoneActionExecutor
import com.lockjaw.claudeagent.voice.VoiceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ClaudeAgentService : Service() {

    companion object {
        var instance: ClaudeAgentService? = null
        const val CHANNEL_ID = "claude_agent"
        const val NOTIF_ID = 1001
        const val TAG = "ClaudeAgentService"

        const val ACTION_SEND_MESSAGE = "com.lockjaw.claudeagent.SEND_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SESSION = "session"

        fun start(context: Context) {
            val intent = Intent(context, ClaudeAgentService::class.java)
            context.startForegroundService(intent)
        }

        fun sendMessage(context: Context, message: String, session: String = "default") {
            val intent = Intent(context, ClaudeAgentService::class.java).apply {
                action = ACTION_SEND_MESSAGE
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_SESSION, session)
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val apiClient = ClaudeApiClient()
    private lateinit var db: AgentDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var contextCollector: ContextCollector
    private lateinit var actionExecutor: PhoneActionExecutor
    private lateinit var voiceManager: VoiceManager
    private lateinit var taskerBridge: TaskerBridge
    private lateinit var shizukuBridge: ShizukuBridge
    private val gson = Gson()

    // Conversation histories per session
    private val conversationHistories = mutableMapOf<String, MutableList<ClaudeMessage>>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AgentDatabase.getInstance(this)
        prefs = AppPreferences(this)
        contextCollector = ContextCollector(this)
        actionExecutor = PhoneActionExecutor(this)
        voiceManager = VoiceManager(this)
        taskerBridge = TaskerBridge(this)
        shizukuBridge = ShizukuBridge(this)
        com.lockjaw.claudeagent.phone.VoiceManager.instance = voiceManager

        createNotificationChannels()
        // Android 14+ requires service type in startForeground()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildForegroundNotification())
        }

        // Register Tasker event receiver (Tasker → Claude pipeline)
        taskerBridge.registerReceiver(feedToClaudeOnReceive = true)

        Log.i(TAG, "Claude Agent Service started")
        // Seed default API key on first run
        scope.launch { prefs.initDefaultApiKey() }
        // Schedule periodic app scan + run immediately on first start
        AppScanWorker.schedule(this)
        AppScanWorker.runNow(this)
    }

    override fun onDestroy() {
        instance = null
        voiceManager.shutdown()
        taskerBridge.unregisterReceiver()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                val session = intent.getStringExtra(EXTRA_SESSION) ?: "default"
                scope.launch { processMessage(message, session) }
            }
        }
        return START_STICKY
    }

    // ─── Core Message Processing ──────────────────────────────────────────────

    suspend fun processMessage(
        userText: String,
        sessionId: String = "default",
        onResult: ((ClaudeResponse, List<ActionResult>) -> Unit)? = null
    ) {
        val apiKey = prefs.apiKey.first()
        if (apiKey.isBlank()) {
            broadcastError("No API key set", sessionId)
            return
        }

        // Save user message
        db.messageDao().insert(Message(role = "user", content = userText, sessionId = sessionId))
        broadcastMessage("user", userText, sessionId)

        try {
            val includeLocation = prefs.includeLocation.first()
            val context = contextCollector.collect(includeLocation)
            val history = getHistory(sessionId)
            val deviceProfile = prefs.getDeviceProfile()

            val response = apiClient.sendMessage(userText, context, history, apiKey, deviceProfile)

            // Update history
            val sessionHistory = conversationHistories.getOrPut(sessionId) { mutableListOf() }
            sessionHistory.add(ClaudeMessage("user", userText))
            sessionHistory.add(ClaudeMessage("assistant", response.rawResponse))
            // Keep last 20 turns
            while (sessionHistory.size > 40) sessionHistory.removeAt(0)

            // Save assistant message
            val actionsJson = if (response.actions.isNotEmpty()) gson.toJson(response.actions) else null
            db.messageDao().insert(
                Message(role = "assistant", content = response.text, sessionId = sessionId, actionsJson = actionsJson)
            )
            broadcastMessage("assistant", response.text, sessionId, response.actions)

            // TTS
            if (prefs.ttsEnabled.first() && response.text.isNotBlank()) {
                voiceManager.speak(response.text)
            }

            // Execute actions
            if (response.actions.isNotEmpty()) {
                val autoExecute = prefs.autoExecute.first()
                val results = if (autoExecute) {
                    val r = actionExecutor.executeAll(response.actions)
                    db.actionLogDao().insert(ActionLogEntry(
                        actionsJson = gson.toJson(response.actions),
                        resultsJson = gson.toJson(r)
                    ))
                    broadcastActionResults(sessionId, r)
                    r
                } else {
                    // Signal to UI that confirmation is needed
                    broadcastPendingActions(sessionId, response.actions)
                    emptyList()
                }
                onResult?.invoke(response, results)
            } else {
                onResult?.invoke(response, emptyList())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Processing failed: ${e.message}", e)
            broadcastError(e.message ?: "Unknown error", sessionId)
        }
    }

    suspend fun executeConfirmedActions(actions: List<PhoneAction>, sessionId: String): List<ActionResult> {
        val results = actionExecutor.executeAll(actions)
        db.actionLogDao().insert(ActionLogEntry(
            actionsJson = gson.toJson(actions),
            resultsJson = gson.toJson(results)
        ))
        broadcastActionResults(sessionId, results)
        return results
    }

    private fun getHistory(sessionId: String): List<ClaudeMessage> =
        conversationHistories[sessionId] ?: emptyList()

    fun clearSession(sessionId: String) {
        conversationHistories.remove(sessionId)
        scope.launch { db.messageDao().clearSession(sessionId) }
    }

    // ─── Broadcasts to UI ─────────────────────────────────────────────────────

    private fun broadcastMessage(role: String, text: String, sessionId: String, actions: List<PhoneAction> = emptyList()) {
        val intent = Intent("com.lockjaw.claudeagent.MESSAGE").apply {
            putExtra("role", role)
            putExtra("text", text)
            putExtra("sessionId", sessionId)
            if (actions.isNotEmpty()) putExtra("actions", gson.toJson(actions))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(error: String, sessionId: String) {
        val intent = Intent("com.lockjaw.claudeagent.ERROR").apply {
            putExtra("error", error)
            putExtra("sessionId", sessionId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastPendingActions(sessionId: String, actions: List<PhoneAction>) {
        val intent = Intent("com.lockjaw.claudeagent.PENDING_ACTIONS").apply {
            putExtra("sessionId", sessionId)
            putExtra("actions", gson.toJson(actions))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastActionResults(sessionId: String, results: List<ActionResult>) {
        val intent = Intent("com.lockjaw.claudeagent.ACTION_RESULTS").apply {
            putExtra("sessionId", sessionId)
            putExtra("results", gson.toJson(results))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ─── Notification Channels ────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Claude Agent", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Persistent service notification" })

        nm.createNotificationChannel(NotificationChannel(
            "claude_alerts", "Claude Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Important alerts from Claude" })

        nm.createNotificationChannel(NotificationChannel(
            "claude_actions", "Claude Actions", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Action confirmations" })
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("My Phone")
            .setContentText("Active and ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
