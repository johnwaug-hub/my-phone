// receivers/TaskerEventReceiver.kt
package com.lockjaw.claudeagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lockjaw.claudeagent.phone.TaskerBridge
import com.lockjaw.claudeagent.services.ClaudeAgentService

/**
 * Manifest-registered receiver for Tasker → My Phone events.
 * This handles intents even when the app is not running in the foreground.
 *
 * Tasker setup: Action → Code → Send Intent
 *   Action:  com.lockjaw.claudeagent.TASKER_EVENT
 *   Target:  Broadcast Receiver
 *   Package: com.lockjaw.claudeagent
 *   Extra:   message → what you want Claude to know/do
 *   Extra:   task_name → name of this Tasker task (optional)
 */
class TaskerEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("TaskerEventReceiver", "Received: $action")

        val taskName = intent.getStringExtra(TaskerBridge.EXTRA_TASK_NAME)
        val message  = intent.getStringExtra(TaskerBridge.EXTRA_MESSAGE)

        when (action) {
            TaskerBridge.MY_PHONE_TASKER_EVENT -> {
                if (!message.isNullOrBlank()) {
                    // Start service if needed and send message to Claude
                    ClaudeAgentService.start(context)
                    val prompt = buildString {
                        append("[Tasker")
                        if (!taskName.isNullOrBlank()) append(" / $taskName")
                        append("] $message")
                        // Append any other extras as context
                        intent.extras?.keySet()
                            ?.filter { it != TaskerBridge.EXTRA_MESSAGE && it != TaskerBridge.EXTRA_TASK_NAME }
                            ?.forEach { key ->
                                intent.getStringExtra(key)?.let { v ->
                                    append("\n  $key: $v")
                                }
                            }
                    }
                    ClaudeAgentService.sendMessage(context, prompt)
                }
            }
            TaskerBridge.MY_PHONE_TASKER_TRIGGER -> {
                // Result from a task Claude triggered — forward to service
                val result = intent.getStringExtra(TaskerBridge.EXTRA_RESULT)
                if (!result.isNullOrBlank()) {
                    ClaudeAgentService.sendMessage(context, "[Tasker result from '$taskName'] $result")
                }
            }
        }
    }
}
