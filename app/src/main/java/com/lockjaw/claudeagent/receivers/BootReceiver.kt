// receivers/BootReceiver.kt
package com.lockjaw.claudeagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lockjaw.claudeagent.services.ClaudeAgentService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ClaudeAgentService.start(context)
        }
    }
}
