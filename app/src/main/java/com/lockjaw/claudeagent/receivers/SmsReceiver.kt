// receivers/SmsReceiver.kt
package com.lockjaw.claudeagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.lockjaw.claudeagent.services.ClaudeAgentService
import kotlinx.coroutines.*

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        messages.forEach { msg ->
            val from = msg.originatingAddress ?: "Unknown"
            val body = msg.messageBody ?: ""
            Log.i("SmsReceiver", "SMS from $from: $body")
            // Broadcast to the service so Claude can be aware
            val broadcast = Intent("com.lockjaw.claudeagent.SMS_RECEIVED").apply {
                putExtra("from", from)
                putExtra("body", body)
            }
            context.sendBroadcast(broadcast)
        }
    }
}
