// receivers/CallReceiver.kt
package com.lockjaw.claudeagent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.i("CallReceiver", "Incoming call from: $number")
                val broadcast = Intent("com.lockjaw.claudeagent.CALL_INCOMING").apply {
                    putExtra("number", number ?: "Unknown")
                }
                context.sendBroadcast(broadcast)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> Log.i("CallReceiver", "Call answered")
            TelephonyManager.EXTRA_STATE_IDLE -> Log.i("CallReceiver", "Call ended")
        }
    }
}

// receivers/ReminderReceiver.kt
// (Separate file in same package for clarity, included here as companion)
