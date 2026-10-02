// phone/TaskerBridge.kt
package com.lockjaw.claudeagent.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import com.lockjaw.claudeagent.services.ClaudeAgentService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Two-way Tasker bridge.
 *
 * DIRECTION 1 — My Phone → Tasker:
 *   Claude can trigger any named Tasker task by name, optionally passing variables.
 *   Requires "Allow External Access" enabled in Tasker Settings > Misc.
 *
 * DIRECTION 2 — Tasker → My Phone / Claude:
 *   Tasker sends an intent to My Phone, which optionally feeds it to Claude
 *   as context or triggers a Claude response.
 *   Set up in Tasker: Action → Code → Send Intent
 *     Action: com.lockjaw.claudeagent.TASKER_EVENT
 *     Target: Broadcast Receiver
 *     Extra: message: "What you want Claude to know or do"
 *     Extra: task_name: "Which Tasker task fired" (optional)
 *
 * Tasker package names:
 *   Release:  net.dinglisch.android.taskerm
 *   Play:     net.dinglisch.android.tasker
 *   Both are checked automatically.
 */
class TaskerBridge(private val context: Context) {

    companion object {
        private const val TAG = "TaskerBridge"

        // Tasker package names (release and Play Store versions)
        private val TASKER_PACKAGES = listOf(
            "net.dinglisch.android.taskerm",   // direct purchase / APK
            "net.dinglisch.android.tasker"     // Play Store
        )

        // Intent action Tasker listens to for external task triggers
        private const val TASKER_ACTION_TASK = "net.dinglisch.android.taskerm.ACTION_TASK"

        // Intent Tasker sends to trigger tasks by name (component-based, more reliable)
        private const val TASKER_SERVICE_CLASS = "net.dinglisch.android.taskerm.TaskerAgent"

        // Our inbound intent action — Tasker sends this TO My Phone
        const val MY_PHONE_TASKER_EVENT = "com.lockjaw.claudeagent.TASKER_EVENT"

        // Outbound action — we send this, Tasker listens
        const val MY_PHONE_TASKER_TRIGGER = "com.lockjaw.claudeagent.TASKER_TRIGGER"

        // Variable keys
        const val EXTRA_TASK_NAME  = "task_name"
        const val EXTRA_MESSAGE    = "message"
        const val EXTRA_VARIABLES  = "variables"  // JSON key=value pairs
        const val EXTRA_RESULT     = "result"
    }

    // Emits events from Tasker arriving at My Phone
    private val _taskerEvents = MutableSharedFlow<TaskerEvent>(extraBufferCapacity = 10)
    val taskerEvents = _taskerEvents.asSharedFlow()

    data class TaskerEvent(
        val taskName: String?,
        val message: String?,
        val variables: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    // ─── Detect Tasker ─────────────────────────────────────────────────────────

    fun isTaskerInstalled(): Boolean =
        TASKER_PACKAGES.any { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true }
            catch (_: Exception) { false }
        }

    fun isExternalAccessEnabled(): Boolean {
        // Check by attempting a dry-run broadcast — Tasker will silently ignore
        // if external access is disabled. We can't read the setting directly.
        // Best we can do is check if Tasker is installed and inform the user.
        return isTaskerInstalled() // User must enable it in Tasker > Settings > Misc
    }

    private fun taskerPackage(): String? =
        TASKER_PACKAGES.firstOrNull { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true }
            catch (_: Exception) { false }
        }

    // ─── DIRECTION 1: My Phone → Tasker ───────────────────────────────────────

    /**
     * Trigger a Tasker task by name.
     * The task must have "Allow External Access" enabled in Tasker Settings > Misc.
     *
     * @param taskName     Exact name of the Tasker task to run
     * @param variables    Map of %VARIABLE_NAME → value to pass into the task
     *                     Variable names must start with % (Tasker convention)
     * @param waitForResult Whether to wait for a result broadcast back
     */
    fun triggerTask(
        taskName: String,
        variables: Map<String, String> = emptyMap(),
        priority: Int = 10
    ): String {
        if (!isTaskerInstalled()) return "Tasker not installed"

        val pkg = taskerPackage() ?: return "Tasker not found"

        // Method 1: Named task via TaskerAgent service (most reliable)
        val serviceIntent = Intent().apply {
            component = android.content.ComponentName(pkg, "$pkg.TaskerAgent")
            action = "net.dinglisch.android.taskerm.ACTION_TASK"
            putExtra("task_name", taskName)
            putExtra("task_priority", priority)
            // Pass variables
            variables.forEach { (key, value) ->
                // Tasker expects variable names without % prefix in extras
                val cleanKey = key.removePrefix("%")
                putExtra(cleanKey, value)
            }
        }

        try {
            context.startService(serviceIntent)
            Log.i(TAG, "Triggered task via service: $taskName")
            return "Task triggered: $taskName"
        } catch (e: Exception) {
            Log.w(TAG, "Service trigger failed, trying broadcast: ${e.message}")
        }

        // Method 2: Broadcast fallback
        val broadcastIntent = Intent(TASKER_ACTION_TASK).apply {
            setPackage(pkg)
            putExtra("task_name", taskName)
            variables.forEach { (key, value) ->
                putExtra(key.removePrefix("%"), value)
            }
        }

        return try {
            context.sendBroadcast(broadcastIntent)
            Log.i(TAG, "Triggered task via broadcast: $taskName")
            "Task triggered: $taskName"
        } catch (e: Exception) {
            Log.e(TAG, "Both trigger methods failed: ${e.message}")
            "Failed to trigger '$taskName': ${e.message}\nMake sure External Access is enabled in Tasker > Settings > Misc"
        }
    }

    /**
     * Trigger a task and return its result variable.
     * The Tasker task must send a broadcast back to MY_PHONE_TASKER_TRIGGER
     * with the result in the "result" extra.
     *
     * This is async — sets up a one-shot receiver and fires the task.
     * Callers should subscribe to taskerEvents to get the response.
     */
    fun triggerTaskWithResult(
        taskName: String,
        variables: Map<String, String> = emptyMap()
    ): String {
        return triggerTask(taskName, variables + mapOf("return_action" to MY_PHONE_TASKER_TRIGGER))
    }

    /**
     * Run a Tasker task with shell-style variable passing.
     * E.g.: runTask("My Task", "%name" to "John", "%action" to "disc_golf")
     */
    fun runTask(taskName: String, vararg variables: Pair<String, String>): String =
        triggerTask(taskName, variables.toMap())

    // ─── DIRECTION 2: Tasker → My Phone ───────────────────────────────────────

    /**
     * Register to receive events from Tasker.
     * Tasker uses Send Intent action with:
     *   Action: com.lockjaw.claudeagent.TASKER_EVENT
     *   Target: Broadcast Receiver
     */
    private var eventReceiver: BroadcastReceiver? = null

    fun registerReceiver(feedToClaudeOnReceive: Boolean = true) {
        if (eventReceiver != null) return

        eventReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val taskName = intent.getStringExtra(EXTRA_TASK_NAME)
                val message  = intent.getStringExtra(EXTRA_MESSAGE)
                val varJson  = intent.getStringExtra(EXTRA_VARIABLES)

                // Parse extra variables
                val vars = mutableMapOf<String, String>()
                varJson?.let {
                    runCatching {
                        val obj = org.json.JSONObject(it)
                        obj.keys().forEach { k -> vars[k] = obj.getString(k) }
                    }
                }
                // Also scan all extras for Tasker-style %VARIABLE values
                intent.extras?.keySet()?.forEach { key ->
                    if (key != EXTRA_TASK_NAME && key != EXTRA_MESSAGE && key != EXTRA_VARIABLES) {
                        intent.getStringExtra(key)?.let { v -> vars[key] = v }
                    }
                }

                val event = TaskerEvent(taskName, message, vars)
                _taskerEvents.tryEmit(event)
                Log.i(TAG, "Tasker event received: task=$taskName msg=$message vars=$vars")

                // Feed to Claude if requested
                if (feedToClaudeOnReceive && !message.isNullOrBlank()) {
                    val prompt = buildString {
                        append("[TASKER EVENT")
                        if (!taskName.isNullOrBlank()) append(" from task '$taskName'")
                        append("] $message")
                        if (vars.isNotEmpty()) {
                            append("\nVariables: ${vars.entries.joinToString { "${it.key}=${it.value}" }}")
                        }
                    }
                    ClaudeAgentService.sendMessage(ctx, prompt)
                }
            }
        }

        // ContextCompat.registerReceiver handles API < 33 backcompat automatically
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            eventReceiver,
            IntentFilter(MY_PHONE_TASKER_EVENT),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        Log.i(TAG, "Tasker receiver registered")
    }

    fun unregisterReceiver() {
        eventReceiver?.let { context.unregisterReceiver(it) }
        eventReceiver = null
    }

    // ─── Convenience task library ──────────────────────────────────────────────
    // Pre-built calls for common Tasker use cases

    /** Play a specific media file via Tasker */
    fun playAudio(filePath: String) = runTask("Play Audio", "%file" to filePath)

    /** Toggle a Tasker-controlled smart home device */
    fun toggleSmartDevice(deviceName: String, state: Boolean) =
        runTask("Smart Device", "%device" to deviceName, "%state" to if (state) "on" else "off")

    /** Send a notification via Tasker with full customization */
    fun sendRichNotification(title: String, text: String, icon: String = "") =
        runTask("Rich Notification", "%title" to title, "%text" to text, "%icon" to icon)

    /** Run a Tasker-defined AutoVoice command */
    fun speakAutoVoice(text: String) = runTask("Speak", "%text" to text)

    /** Toggle a Tasker scene (custom UI overlay) */
    fun showScene(sceneName: String) = runTask("Show Scene", "%scene" to sceneName)

    /** Trigger a Tasker location-based routine */
    fun triggerLocationEvent(location: String) =
        runTask("Location Event", "%location" to location)

    /** Run a Tasker HTTP request task */
    fun httpRequest(url: String, method: String = "GET", body: String = "") =
        runTask("HTTP Request", "%url" to url, "%method" to method, "%body" to body)

    // ─── Setup instructions ────────────────────────────────────────────────────

    fun getSetupInstructions(): String = """
TASKER SETUP — My Phone Integration

═══ STEP 1: Enable External Access ═══
Tasker → Menu (⋮) → Preferences → Misc
→ Enable "Allow External Access"

═══ STEP 2: My Phone → Tasker (Claude triggers your tasks) ═══
Nothing to configure. Claude will trigger tasks by name.
Make sure the task name matches exactly (case-sensitive).

Example — Claude says: "Run your Good Morning routine"
→ I fire: triggerTask("Good Morning")

═══ STEP 3: Tasker → My Phone (Tasker feeds Claude) ═══
In any Tasker task, add an action:
  Code → Send Intent
    Action:  com.lockjaw.claudeagent.TASKER_EVENT
    Target:  Broadcast Receiver
    Extra 1: message: <what you want Claude to know>
    Extra 2: task_name: <name of this task> (optional)

Example — NFC tag at disc golf course triggers:
  message: "I just arrived at Brandi Fenton disc golf course"
→ Claude receives this as context and can respond

═══ STEP 4: Passing variables ═══
Add extras to the Send Intent action:
  Extra: player_name: John
  Extra: wind_speed: 12mph
Claude receives all extras as context.

═══ COMMON TASKER TASKS TO BUILD ═══
• "Good Morning" — weather, calendar, disc golf conditions
• "Arriving Home" — lights, music, notifications
• "Disc Golf Mode" — DND, brightness up, UDisc open
• "Work Mode" — silence, calendar, focus
• "Sleep Mode" — DND, dim screen, stop music
""".trimIndent()
}
