// workers/AppScanWorker.kt
package com.lockjaw.claudeagent.workers

import android.content.Context
import androidx.work.*
import com.google.gson.Gson
import com.lockjaw.claudeagent.data.AppPreferences
import com.lockjaw.claudeagent.data.PrefKeys
import com.lockjaw.claudeagent.phone.ContextCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AppScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val collector = ContextCollector(applicationContext)
            val apps = collector.getInstalledApps()
            val json = Gson().toJson(apps)
            AppPreferences(applicationContext).set(PrefKeys.INSTALLED_APPS_JSON, json)
            android.util.Log.i("AppScanWorker", "Scanned ${apps.size} installed apps")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("AppScanWorker", "Scan failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "app_scan_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AppScanWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
