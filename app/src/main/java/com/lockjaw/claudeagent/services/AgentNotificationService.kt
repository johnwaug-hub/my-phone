// services/AgentNotificationService.kt
package com.lockjaw.claudeagent.services

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lockjaw.claudeagent.data.CachedNotification
import java.util.concurrent.ConcurrentLinkedDeque

class AgentNotificationService : NotificationListenerService() {

    companion object {
        var instance: AgentNotificationService? = null
        private const val TAG = "NotificationAgent"
        private const val MAX_CACHED = 50
    }

    private val recent = ConcurrentLinkedDeque<CachedNotification>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getString(Notification.EXTRA_TEXT)
            ?: extras.getString(Notification.EXTRA_BIG_TEXT)

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) { sbn.packageName }

        val cached = CachedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text
        )

        recent.addFirst(cached)
        while (recent.size > MAX_CACHED) recent.removeLast()

        Log.d(TAG, "Notification: [$appName] $title — $text")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        recent.removeIf { it.key == sbn.key }
    }

    fun getRecentNotifications(): List<CachedNotification> = recent.toList().take(10)

    fun getNotificationsForApp(packageName: String): List<CachedNotification> =
        recent.filter { it.packageName == packageName }

    fun dismissNotification(key: String) {
        cancelNotification(key)
        recent.removeIf { it.key == key }
    }

    fun replyToNotification(key: String, replyText: String) {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
        val notification = sbn.notification ?: return

        // Find a reply action
        val replyAction = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true &&
                    (action.title?.toString()?.contains("reply", ignoreCase = true) == true ||
                            action.title?.toString()?.contains("respond", ignoreCase = true) == true ||
                            action.remoteInputs?.isNotEmpty() == true)
        } ?: notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

        replyAction?.let { action ->
            val remoteInput = action.remoteInputs?.firstOrNull() ?: return
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, replyText)
            }
            val fillIntent = android.content.Intent().apply {
                android.app.RemoteInput.addResultsToIntent(action.remoteInputs, this, bundle)
            }
            action.actionIntent.send(applicationContext, 0, fillIntent)
        }
    }
}
