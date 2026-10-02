// receivers/ReminderReceiver.kt
package com.lockjaw.claudeagent.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Claude Reminder"
        val nm = context.getSystemService(NotificationManager::class.java)
        // Ensure channel exists even if service hasn't run yet
        if (nm.getNotificationChannel("claude_alerts") == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel("claude_alerts", "My Phone Alerts",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, "claude_alerts")
            .setContentTitle("⏰ $title")
            .setContentText("Reminder set by Claude Agent")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
