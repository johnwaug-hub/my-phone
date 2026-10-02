// phone/PhoneActionExecutor.kt
package com.lockjaw.claudeagent.phone

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.lockjaw.claudeagent.data.ActionResult
import com.lockjaw.claudeagent.data.PhoneAction
import com.lockjaw.claudeagent.services.AgentAccessibilityService
import com.lockjaw.claudeagent.phone.TaskerBridge
import com.lockjaw.claudeagent.phone.ShizukuBridge
import com.lockjaw.claudeagent.services.AgentNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class PhoneActionExecutor(private val context: Context) {

    private val TAG = "PhoneActionExecutor"
    private val goodLock = GoodLockAutomator(context)
    private val tasker = TaskerBridge(context)
    private val shizuku = ShizukuBridge(context)

    suspend fun executeAll(actions: List<PhoneAction>): List<ActionResult> {
        return actions.map { action ->
            try {
                val output = execute(action)
                ActionResult(action, true, output)
            } catch (e: Exception) {
                Log.e(TAG, "Action failed [${action.type}]: ${e.message}")
                ActionResult(action, false, error = e.message)
            }
        }
    }

    private suspend fun execute(action: PhoneAction): String = withContext(Dispatchers.IO) {
        when (action.type) {
            "send_sms" -> sendSms(action)
            "make_call" -> makeCall(action)
            "send_email" -> sendEmail(action)
            "reply_notification" -> replyNotification(action)
            "dismiss_notification" -> dismissNotification(action)
            "set_brightness" -> setBrightness(action)
            "set_volume" -> setVolume(action)
            "toggle_wifi" -> toggleWifi(action)
            "toggle_bluetooth" -> toggleBluetooth(action)
            "toggle_flashlight" -> toggleFlashlight(action)
            "toggle_dnd" -> toggleDnd(action)
            "open_app" -> openApp(action)
            "open_settings" -> openSettings(action)
            "launch_url" -> launchUrl(action)
            "click_ui_element" -> clickUiElement(action)
            "input_text" -> inputText(action)
            "scroll_down" -> scrollDown()
            "press_back" -> pressBack()
            "press_home" -> pressHome()
            "take_screenshot" -> takeScreenshot()
            "copy_clipboard" -> copyClipboard(action)
            "read_clipboard" -> readClipboard()
            "search_contacts" -> searchContacts(action)
            "create_calendar_event" -> createCalendarEvent(action)
            "create_reminder" -> createReminder(action)
            "speak" -> speak(action)
            "vibrate" -> vibrate(action)
            "notification" -> showNotification(action)
            "good_lock_refresh" -> goodLockRefresh(action)
            "read_screen_content" -> readScreenContent()

            // ── Tasker Bridge ─────────────────────────────────────────────────
            "tasker_run_task" -> {
                val taskName = action.text ?: action.title ?: throw Exception("No task name")
                val vars = action.body?.let { parseVarsString(it) } ?: emptyMap()
                tasker.triggerTask(taskName, vars)
            }
            "tasker_run_task_vars" -> {
                val taskName = action.text ?: throw Exception("No task name")
                val vars = buildMap<String, String> {
                    action.to?.let { put("param1", it) }
                    action.message?.let { put("param2", it) }
                    action.body?.let { put("param3", it) }
                }
                tasker.triggerTask(taskName, vars)
            }
            "tasker_status" -> {
                val installed = tasker.isTaskerInstalled()
                if (installed) "Tasker installed. External access: enable in Tasker > Settings > Misc"
                else "Tasker not installed"
            }
            "tasker_setup_info" -> tasker.getSetupInstructions()

            // ── Shizuku Bridge ────────────────────────────────────────────────
            "shizuku_shell" -> {
                val cmd = action.text ?: action.body ?: throw Exception("No command")
                shizuku.shell(cmd)
            }
            "shizuku_grant_permission" -> shizuku.grantPermission(
                action.packageName ?: throw Exception("No package"),
                action.text ?: throw Exception("No permission")
            )
            "shizuku_revoke_permission" -> shizuku.revokePermission(
                action.packageName ?: throw Exception("No package"),
                action.text ?: throw Exception("No permission")
            )
            "shizuku_grant_self" -> shizuku.grantSelfElevatedPermissions()
            "shizuku_force_stop" -> shizuku.forceStop(action.packageName ?: throw Exception("No package"))
            "shizuku_clear_data" -> shizuku.clearAppData(action.packageName ?: throw Exception("No package"))
            "shizuku_disable_app" -> shizuku.disableApp(action.packageName ?: throw Exception("No package"))
            "shizuku_enable_app" -> shizuku.enableApp(action.packageName ?: throw Exception("No package"))
            "shizuku_uninstall" -> shizuku.uninstallApp(action.packageName ?: throw Exception("No package"))
            "shizuku_set_font_scale" -> shizuku.setFontScale(action.value ?: 1.0f)
            "shizuku_set_density" -> shizuku.setDisplayDensity(action.value?.toInt() ?: 424)
            "shizuku_reset_density" -> shizuku.resetDisplayDensity()
            "shizuku_set_animation" -> shizuku.setAnimationScale(action.value ?: 1.0f)
            "shizuku_dark_mode" -> shizuku.setDarkMode(action.enabled ?: true)
            "shizuku_screen_timeout" -> shizuku.setScreenTimeout(action.value?.toInt() ?: 60000)
            "shizuku_write_secure" -> shizuku.writeSecureSetting(
                action.setting ?: throw Exception("No key"),
                action.text ?: throw Exception("No value")
            )
            "shizuku_write_system" -> shizuku.writeSystemSetting(
                action.setting ?: throw Exception("No key"),
                action.text ?: throw Exception("No value")
            )
            "shizuku_write_global" -> shizuku.writeGlobalSetting(
                action.setting ?: throw Exception("No key"),
                action.text ?: throw Exception("No value")
            )
            "shizuku_read_setting" -> shizuku.readSetting(
                action.setting ?: "system",
                action.text ?: throw Exception("No key")
            )
            "shizuku_screenshot" -> shizuku.screenshot(
                action.text ?: "/sdcard/Download/screenshot_${System.currentTimeMillis()}.png"
            )
            "shizuku_tap" -> {
                val parts = action.text?.split(",") ?: throw Exception("No coordinates")
                shizuku.tap(parts[0].trim().toInt(), parts[1].trim().toInt())
            }
            "shizuku_swipe" -> {
                val parts = action.text?.split(",") ?: throw Exception("No coordinates")
                shizuku.swipe(parts[0].trim().toInt(), parts[1].trim().toInt(),
                    parts[2].trim().toInt(), parts[3].trim().toInt())
            }
            "shizuku_type_text" -> shizuku.typeText(action.text ?: throw Exception("No text"))
            "shizuku_press_key" -> shizuku.pressKey(action.value?.toInt() ?: throw Exception("No keycode"))
            "shizuku_battery_stats" -> shizuku.getBatteryStats()
            "shizuku_memory_info" -> shizuku.getMemoryInfo()
            "shizuku_processes" -> shizuku.getRunningProcesses()
            "shizuku_list_packages" -> shizuku.listPackages(action.query ?: "")
            "shizuku_status" -> {
                val status = shizuku.getShizukuStatus()
                when (status) {
                    "ready" -> "Shizuku ready — ADB-level access available"
                    "not_installed" -> shizuku.getSetupInstructions()
                    else -> "Shizuku status: $status\n${shizuku.getSetupInstructions()}"
                }
            }
            // ── Good Lock Deep Automation (One UI 8.5) ──
            "goodlock_full_refresh" -> goodLock.applyFullVibeRefresh(action.query ?: "lockjaw")
            "goodlock_theme_vibe"  -> goodLock.applyThemePark(
                accentColor     = action.query   ?: "Default",
                fontStyle       = action.setting ?: "Samsung One",
                iconShape       = action.module  ?: "Squircle",
                iconEffect      = action.text    ?: "None",
                applyGradientTo = action.notes
            )
            "goodlock_lockstar"    -> goodLock.configureLockStar(
                unlockAnimation  = action.setting  ?: "Swirl",
                clockStyle       = action.module   ?: "Default",
                clockPosition    = action.query    ?: "Top center",
                shortcutLeft     = action.to       ?: "Camera",
                shortcutRight    = action.message  ?: "Phone",
                aodBrightness    = action.value?.toInt() ?: -1
            )
            "goodlock_homeup"      -> goodLock.configureHomeUp(
                taskChangerLayout = action.setting ?: "Grid",
                gridColumns       = action.query?.substringBefore("x")?.toIntOrNull() ?: 5,
                gridRows          = action.query?.substringAfter("x")?.toIntOrNull()  ?: 5,
                folderStyle       = action.module  ?: "Pop-up window",
                folderBlur        = action.enabled ?: false
            )
            "goodlock_quickstar"   -> goodLock.configureQuickStar(
                transparency     = action.value?.toInt() ?: 20,
                colorMode        = action.setting ?: "Follow theme",
                statusBarLayout  = action.module  ?: "Default",
                clockSide        = action.query   ?: "Right",
                showSliderValues = action.enabled ?: true
            )
            "goodlock_navstar"     -> goodLock.configureNavStar(
                navType             = action.setting ?: "Gestures",
                gestureSensitivity  = action.module  ?: "Medium",
                gestureHint         = action.enabled ?: false
            )
            "goodlock_multistar"   -> goodLock.configureMultiStar(
                splitRatio = action.setting ?: "50:50"
            )
            "goodlock_sound"       -> goodLock.configureSoundAssistant(
                volumeSteps        = action.value?.toInt() ?: 30,
                mediaVolumeButton  = action.enabled ?: true
            )
            "goodlock_onehand"     -> goodLock.configureOneHandOperation()
            "goodlock_clockface"   -> goodLock.configureClockFace(
                clockName    = action.setting ?: "Minimal",
                primaryColor = action.query   ?: "Follow theme",
                showDate     = action.enabled ?: true
            )
            "goodlock_registar"    -> goodLock.configureRegiStar(
                powerDoubleTap = action.setting ?: "Camera",
                powerLongPress = action.query   ?: "Power menu"
            )
            "goodlock_notistar"    -> goodLock.configureNotiStar(
                keepDuration = action.setting ?: "30 days",
                groupByApp   = action.enabled ?: true
            )
            "goodlock_keyscafe"    -> goodLock.configureKeysCafe(
                theme      = action.setting ?: "Dark",
                sizePreset = action.module  ?: "Default"
            )
            "goodlock_wonderland"  -> goodLock.configureWonderland(
                preset     = action.setting ?: "Aurora",
                speed      = action.module  ?: "Normal",
                density    = action.query   ?: "Medium",
                applyToAod = action.enabled ?: true
            )
            "get_app_usage" -> getAppUsage()
            else -> throw Exception("Unknown action type: ${action.type}")
        }
    }

    // ─── Communication ───────────────────────────────────────────────────────

    private fun sendSms(action: PhoneAction): String {
        val to = action.to ?: throw Exception("No recipient")
        val msg = action.message ?: throw Exception("No message")

        // Send via Google Messages intent so message appears in thread
        // Try Samsung Messages first (default on Z Flip), fall back to Google Messages
        val samsungPkg = "com.samsung.android.messaging"
        val googlePkg = "com.google.android.apps.messaging"

        val smsPkg = when {
            context.packageManager.getLaunchIntentForPackage(googlePkg) != null -> googlePkg
            context.packageManager.getLaunchIntentForPackage(samsungPkg) != null -> samsungPkg
            else -> null
        }

        if (smsPkg != null) {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$to")
                putExtra("sms_body", msg)
                putExtra("exit_on_sent", true)
                setPackage(smsPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "SMS sent to $to via ${if (smsPkg == samsungPkg) "Samsung Messages" else "Google Messages"}"
            } catch (e: Exception) {
                android.util.Log.w("PhoneActionExecutor", "Messaging app intent failed: ${e.message}")
            }
        }

        // Fallback: send directly via SmsManager if no messaging app found
        val smsManager = context.getSystemService(SmsManager::class.java)
        if (msg.length > 160) {
            val parts = smsManager.divideMessage(msg)
            smsManager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(to, null, msg, null, null)
        }
        return "SMS sent to $to (direct, may not appear in thread)"
    }

    private fun makeCall(action: PhoneAction): String {
        val to = action.to ?: throw Exception("No number")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$to"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Calling $to"
    }

    private fun sendEmail(action: PhoneAction): String {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(action.to ?: ""))
            putExtra(Intent.EXTRA_SUBJECT, action.subject ?: "")
            putExtra(Intent.EXTRA_TEXT, action.body ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Email composer opened to ${action.to}"
    }

    private fun replyNotification(action: PhoneAction): String {
        val key = action.notificationKey ?: throw Exception("No notification key")
        val reply = action.reply ?: throw Exception("No reply text")
        AgentNotificationService.instance?.replyToNotification(key, reply)
            ?: throw Exception("Notification service not running")
        return "Replied to notification"
    }

    private fun dismissNotification(action: PhoneAction): String {
        val key = action.notificationKey ?: throw Exception("No key")
        AgentNotificationService.instance?.dismissNotification(key)
            ?: throw Exception("Notification service not running")
        return "Notification dismissed"
    }

    // ─── System Controls ─────────────────────────────────────────────────────

    private fun setBrightness(action: PhoneAction): String {
        val value = action.value ?: throw Exception("No value")
        val brightness = (value.coerceIn(0f, 1f) * 255).toInt()
        android.provider.Settings.System.putInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
            brightness
        )
        return "Brightness set to ${(value * 100).toInt()}%"
    }

    private fun setVolume(action: PhoneAction): String {
        val value = action.value ?: throw Exception("No value")
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = when (action.stream?.lowercase()) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "alarm" -> AudioManager.STREAM_ALARM
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(stream)
        audio.setStreamVolume(stream, (value * max).toInt(), 0)
        return "${action.stream ?: "music"} volume set to ${(value * 100).toInt()}%"
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(action: PhoneAction): String {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // isWifiEnabled setter removed in API 29 - open settings panel
            val intent = android.content.Intent("android.settings.WIFI_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Opened WiFi settings (direct toggle requires system permission)"
        } else {
            // Android 10+: open WiFi settings panel
            val intent = Intent(android.provider.Settings.Panel.ACTION_WIFI)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "WiFi settings panel opened (Android 10+ restriction)"
        }
    }

    private fun toggleBluetooth(action: PhoneAction): String {
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val btAdapter = btManager.adapter ?: throw Exception("No Bluetooth adapter")
        val enable = action.enabled ?: !btAdapter.isEnabled
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            if (enable) btAdapter.enable() else btAdapter.disable()
            return "Bluetooth ${if (enable) "enabled" else "disabled"}"
        } else {
            // API 33+: direct enable/disable removed, open settings panel
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Bluetooth settings opened (Android 13+ requires manual toggle)"
        }
    }

    private fun toggleFlashlight(action: PhoneAction): String {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: throw Exception("No camera")
        val enable = action.enabled ?: false
        cameraManager.setTorchMode(cameraId, enable)
        return "Flashlight ${if (enable) "on" else "off"}"
    }

    private fun toggleDnd(action: PhoneAction): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "DND permission needed — settings opened"
        }
        val enable = action.enabled ?: false
        nm.setInterruptionFilter(
            if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return "DND ${if (enable) "enabled" else "disabled"}"
    }

    // ─── App Navigation ───────────────────────────────────────────────────────

    private fun openApp(action: PhoneAction): String {
        val pkg = action.packageName ?: throw Exception("No package name")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivity(intent)
            "Opened $pkg"
        } catch (e: Exception) {
            android.util.Log.e("PhoneActionExecutor", "openApp failed for $pkg: ${e.message}")
            try {
                val playIntent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(playIntent)
                "App not installed — opened Play Store for $pkg"
            } catch (e2: Exception) {
                throw Exception("Could not open $pkg: ${e.message}")
            }
        }
    }


    private fun openSettings(action: PhoneAction): String {
        val settingAction = when (action.setting?.lowercase()) {
            "wifi"          -> "android.settings.WIFI_SETTINGS"
            "bluetooth"     -> "android.settings.BLUETOOTH_SETTINGS"
            "display"       -> "android.settings.DISPLAY_SETTINGS"
            "sound"         -> "android.settings.SOUND_SETTINGS"
            "apps"          -> "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"
            "battery"       -> "android.settings.BATTERY_SAVER_SETTINGS"
            "location"      -> "android.settings.LOCATION_SOURCE_SETTINGS"
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "accessibility" -> "android.settings.ACCESSIBILITY_SETTINGS"
            "developer"     -> "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
            "network"       -> "android.settings.WIRELESS_SETTINGS"
            "accounts"      -> "android.settings.SYNC_SETTINGS"
            else            -> "android.settings.SETTINGS"
        }
        val intent = Intent(settingAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened ${action.setting} settings"
    }

    private fun launchUrl(action: PhoneAction): String {
        val url = action.deepLink ?: throw Exception("No URL")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Launched $url"
    }

    // ─── Accessibility Actions ────────────────────────────────────────────────

    private fun clickUiElement(action: PhoneAction): String {
        val svc = AgentAccessibilityService.instance
            ?: throw Exception("Accessibility service not running. Enable it in Settings > Accessibility > Claude Agent")
        svc.clickElement(action.elementText, action.elementId)
        return "Clicked '${action.elementText ?: action.elementId}'"
    }

    private fun inputText(action: PhoneAction): String {
        val svc = AgentAccessibilityService.instance
            ?: throw Exception("Accessibility service not running")
        svc.inputText(action.elementId, action.text ?: "")
        return "Input text to ${action.elementId}"
    }

    private fun scrollDown(): String {
        AgentAccessibilityService.instance?.scrollDown()
            ?: throw Exception("Accessibility service not running")
        return "Scrolled down"
    }

    private fun pressBack(): String {
        AgentAccessibilityService.instance?.pressBack()
            ?: throw Exception("Accessibility service not running")
        return "Pressed back"
    }

    private fun pressHome(): String {
        AgentAccessibilityService.instance?.pressHome()
            ?: throw Exception("Accessibility service not running")
        return "Pressed home"
    }

    private fun takeScreenshot(): String {
        AgentAccessibilityService.instance?.takeScreenshot()
            ?: throw Exception("Accessibility service not running")
        return "Screenshot taken"
    }

    private fun readScreenContent(): String {
        val svc = AgentAccessibilityService.instance
            ?: return "Accessibility service not running"
        return svc.getScreenContent()
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private fun copyClipboard(action: PhoneAction): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("claude", action.text ?: ""))
        return "Copied to clipboard"
    }

    private fun readClipboard(): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "Clipboard empty"
    }

    private fun searchContacts(action: PhoneAction): String {
        val query = action.query ?: throw Exception("No query")
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$query%"),
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        val results = mutableListOf<String>()
        cursor?.use { c ->
            while (c.moveToNext() && results.size < 5) {
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY))
                val id = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                // Get phone number
                val phoneCursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )
                val number = phoneCursor?.use { p ->
                    if (p.moveToFirst()) p.getString(0) else null
                }
                results.add("$name: ${number ?: "no number"}")
            }
        }
        return if (results.isEmpty()) "No contacts found for '$query'"
        else results.joinToString("\n")
    }

    private fun createCalendarEvent(action: PhoneAction): String {
        val start = parseDate(action.startDate ?: throw Exception("No start date"))
        val end = parseDate(action.endDate ?: throw Exception("No end date"))
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.TITLE, action.title ?: "")
            putExtra(CalendarContract.Events.DESCRIPTION, action.notes ?: "")
            putExtra(CalendarContract.Events.EVENT_LOCATION, action.location ?: "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Calendar event creation opened: ${action.title}"
    }

    private fun createReminder(action: PhoneAction): String {
        val title = action.title ?: "Reminder"
        val triggerMs = action.triggerMs ?: 3600000L
        // Use AlarmManager for reliable reminders
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
        val intent = Intent(context, com.lockjaw.claudeagent.receivers.ReminderReceiver::class.java).apply {
            putExtra("title", title)
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context, System.currentTimeMillis().toInt(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + triggerMs,
            pendingIntent
        )
        return "Reminder set: $title in ${triggerMs / 60000} minutes"
    }

    // ─── Media / Feedback ─────────────────────────────────────────────────────

    private fun speak(action: PhoneAction): String {
        val text = action.text ?: throw Exception("No text to speak")
        VoiceManager.instance?.speak(text) ?: run {
            // Fallback TTS init
            android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    // speak inline
                }
            }
        }
        return "Speaking: ${text.take(50)}"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(action: PhoneAction): String {
        val pattern = action.pattern?.toLongArray() ?: longArrayOf(0, 200, 100, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm.defaultVibrator.vibrate(
                android.os.VibrationEffect.createWaveform(pattern, -1)
            )
        } else {
            val v = context.getSystemService(Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }
        return "Vibrated"
    }

    private fun showNotification(action: PhoneAction): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notification = android.app.Notification.Builder(context, "claude_alerts")
            .setContentTitle(action.title ?: "Claude")
            .setContentText(action.body ?: action.text ?: "")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
        return "Notification shown"
    }

    private fun goodLockRefresh(action: PhoneAction): String {
        // Try to open Good Lock
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.samsung.android.goodlock")
                ?: context.packageManager.getLaunchIntentForPackage("com.samsung.systemui.nicklaus")
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (_: Exception) {}
        return "Good Lock opened. Module: ${action.module}\nInstructions: ${action.instructions}"
    }

    private fun getAppUsage(): String {
        val usageManager = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usageManager.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY,
            now - 86400000, now
        )
        return stats?.sortedByDescending { it.totalTimeInForeground }
            ?.take(5)
            ?.joinToString("\n") { "${it.packageName}: ${it.totalTimeInForeground / 60000}min" }
            ?: "Usage stats not available"
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseDate(dateStr: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // Extension for toLongArray from List<Long>
    private fun List<Long>.toLongArray(): LongArray = LongArray(size) { this[it] }

    /**
     * Parse a variable string like "name=John,action=disc_golf" into a map.
     * Also accepts JSON: {"name":"John","action":"disc_golf"}
     */
    private fun parseVarsString(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()
        return runCatching {
            if (input.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(input)
                buildMap { json.keys().forEach { k -> put(k, json.getString(k)) } }
            } else {
                input.split(",").associate { pair ->
                    val idx = pair.indexOf('=')
                    if (idx >= 0) pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
                    else pair.trim() to ""
                }
            }
        }.getOrDefault(emptyMap())
    }
}

// Expose VoiceManager singleton reference
object VoiceManager {
    var instance: com.lockjaw.claudeagent.voice.VoiceManager? = null
}
