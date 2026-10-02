// phone/ShizukuBridge.kt
package com.lockjaw.claudeagent.phone

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shizuku bridge — ADB-level permissions without root.
 *
 * What Shizuku unlocks that normal Android APIs can't do:
 *   • Grant/revoke ANY permission on ANY app silently
 *   • Install/uninstall apps without user confirmation prompts
 *   • Write SECURE settings (android.permission.WRITE_SECURE_SETTINGS)
 *   • Force-stop any app
 *   • Clear app data silently
 *   • Run arbitrary shell commands with ADB privileges
 *   • Access restricted system APIs via reflection
 *   • Change system settings normally requiring root
 *
 * Setup (one-time, no root required):
 *   1. Enable Developer Options on phone
 *   2. Enable Wireless Debugging (Android 11+)
 *   3. Install Shizuku from Play Store
 *   4. In Shizuku: tap "Start via Wireless Debugging"
 *   5. Grant My Phone permission in Shizuku
 *   Note: Must re-activate Shizuku after reboot (or use root for persistent)
 *
 * Dependency in build.gradle.kts:
 *   implementation("dev.rikka.shizuku:api:13.1.5")
 *   implementation("dev.rikka.shizuku:provider:13.1.5")
 */
class ShizukuBridge(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuBridge"
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    }

    // ─── Availability ──────────────────────────────────────────────────────────

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    } catch (_: Exception) { false }

    fun isShizukuRunning(): Boolean = try {
        // Check if Shizuku binder is available
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val method = cls.getMethod("pingBinder")
        method.invoke(null) as Boolean
    } catch (_: Exception) { false }

    fun isShizukuPermissionGranted(): Boolean = try {
        val cls = Class.forName("rikka.shizuku.Shizuku")
        val method = cls.getMethod("checkSelfPermission")
        val result = method.invoke(null) as Int
        result == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun getShizukuStatus(): String = when {
        !isShizukuInstalled()       -> "not_installed"
        !isShizukuRunning()         -> "not_running"
        !isShizukuPermissionGranted() -> "permission_denied"
        else                        -> "ready"
    }

    private fun checkReady(): String? {
        return when (getShizukuStatus()) {
            "not_installed" -> "Shizuku not installed — install from Play Store"
            "not_running"   -> "Shizuku not running — open Shizuku and tap 'Start via Wireless Debugging'"
            "permission_denied" -> "Shizuku permission not granted — open Shizuku and grant access to My Phone"
            else -> null // ready
        }
    }

    // ─── Shell execution ───────────────────────────────────────────────────────

    /**
     * Run a shell command with ADB-level privileges via Shizuku.
     * This is the core power function — almost everything else uses this.
     *
     * @param command  Shell command to run e.g. "settings put secure location_mode 3"
     * @return stdout output of the command
     */
    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        checkReady()?.let { return@withContext "Error: $it" }

        return@withContext try {
            // Shizuku.newProcess() is the correct API in shizuku-api 13.x
            // It takes String[] args, String[] env, String dir and returns Process
            val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            @Suppress("UNCHECKED_CAST")
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null as Array<String>?,
                null as String?
            ) as Process

            val output = process.inputStream.bufferedReader().readText()
            val error  = process.errorStream.bufferedReader().readText()
            process.waitFor()
            process.destroy()

            Log.d(TAG, "shell($command) → ${output.take(100)}")
            if (error.isNotBlank() && output.isBlank()) "ERROR: $error"
            else output.trim().ifBlank { if (error.isNotBlank()) "STDERR: $error" else "(no output)" }
        } catch (e: Exception) {
            Log.e(TAG, "shell failed: ${e.message}")
            // Fallback: try via Runtime (works if Shizuku already granted us elevated perms)
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                process.waitFor()
                if (out.isNotBlank()) out.trim() else if (err.isNotBlank()) "STDERR: $err" else "(no output)"
            } catch (e2: Exception) {
                "Shell failed: ${e.message}. Try running shizuku_grant_self first."
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    /**
     * Grant a permission to an app silently — no user prompt.
     * This is what normally requires root or ADB.
     *
     * @param packageName  Target app package
     * @param permission   Full permission string e.g. "android.permission.WRITE_SECURE_SETTINGS"
     */
    suspend fun grantPermission(packageName: String, permission: String): String {
        checkReady()?.let { return it }
        val result = shell("pm grant $packageName $permission")
        return if (result.isBlank()) "Granted $permission to $packageName"
        else "Grant result: $result"
    }

    /**
     * Revoke a permission from an app.
     */
    suspend fun revokePermission(packageName: String, permission: String): String {
        checkReady()?.let { return it }
        val result = shell("pm revoke $packageName $permission")
        return if (result.isBlank()) "Revoked $permission from $packageName"
        else "Revoke result: $result"
    }

    /**
     * Grant My Phone all permissions it might need — run once during setup.
     */
    suspend fun grantSelfElevatedPermissions(): String {
        checkReady()?.let { return it }
        val myPkg = context.packageName
        val permissions = listOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.WRITE_SETTINGS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.READ_LOGS",
            "android.permission.DUMP",
            "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
        )
        val results = permissions.map { perm ->
            val r = shell("pm grant $myPkg $perm")
            "$perm: ${if (r.isBlank()) "✓" else r}"
        }
        return "Self-elevated:\n${results.joinToString("\n")}"
    }

    // ─── Settings ─────────────────────────────────────────────────────────────

    /**
     * Write a SECURE setting — normally requires root.
     * Examples:
     *   writeSecureSetting("location_mode", "3")        // GPS mode
     *   writeSecureSetting("install_non_market_apps", "1")
     *   writeSecureSetting("bluetooth_on", "1")
     */
    suspend fun writeSecureSetting(key: String, value: String): String {
        checkReady()?.let { return it }
        return shell("settings put secure $key $value")
            .let { if (it.isBlank()) "Secure setting: $key=$value" else it }
    }

    suspend fun writeSystemSetting(key: String, value: String): String {
        checkReady()?.let { return it }
        return shell("settings put system $key $value")
            .let { if (it.isBlank()) "System setting: $key=$value" else it }
    }

    suspend fun writeGlobalSetting(key: String, value: String): String {
        checkReady()?.let { return it }
        return shell("settings put global $key $value")
            .let { if (it.isBlank()) "Global setting: $key=$value" else it }
    }

    suspend fun readSetting(namespace: String, key: String): String {
        checkReady()?.let { return it }
        return shell("settings get $namespace $key")
    }

    // ─── App management ───────────────────────────────────────────────────────

    /**
     * Force-stop an app silently (no confirmation dialog).
     */
    suspend fun forceStop(packageName: String): String {
        checkReady()?.let { return it }
        return shell("am force-stop $packageName")
            .let { if (it.isBlank()) "Force-stopped $packageName" else it }
    }

    /**
     * Clear an app's data and cache silently.
     */
    suspend fun clearAppData(packageName: String): String {
        checkReady()?.let { return it }
        return shell("pm clear $packageName")
            .let { "Cleared $packageName: $it" }
    }

    /**
     * Disable a system/bloatware app (Samsung system apps, etc.)
     * This doesn't delete it — it's reversible.
     */
    suspend fun disableApp(packageName: String): String {
        checkReady()?.let { return it }
        return shell("pm disable-user --user 0 $packageName")
            .let { "Disabled $packageName: $it" }
    }

    /**
     * Re-enable a disabled app.
     */
    suspend fun enableApp(packageName: String): String {
        checkReady()?.let { return it }
        return shell("pm enable $packageName")
            .let { "Enabled $packageName: $it" }
    }

    /**
     * Uninstall an app silently (user-installed apps only without root).
     */
    suspend fun uninstallApp(packageName: String): String {
        checkReady()?.let { return it }
        return shell("pm uninstall --user 0 $packageName")
    }

    /**
     * Install an APK silently from a path on device storage.
     */
    suspend fun installApk(apkPath: String): String {
        checkReady()?.let { return it }
        return shell("pm install -r $apkPath")
    }

    // ─── System controls (ADB-level) ──────────────────────────────────────────

    /**
     * Set screen timeout in milliseconds.
     */
    suspend fun setScreenTimeout(ms: Int): String {
        checkReady()?.let { return it }
        return writeSystemSetting("screen_off_timeout", ms.toString())
    }

    /**
     * Set font scale (1.0 = default, 0.85 = small, 1.15 = large).
     */
    suspend fun setFontScale(scale: Float): String {
        checkReady()?.let { return it }
        return writeSystemSetting("font_scale", scale.toString())
    }

    /**
     * Set display density (DPI).
     * Default Z Flip 7 is ~424 DPI.
     */
    suspend fun setDisplayDensity(dpi: Int): String {
        checkReady()?.let { return it }
        return shell("wm density $dpi")
    }

    /**
     * Reset display density to device default.
     */
    suspend fun resetDisplayDensity(): String {
        checkReady()?.let { return it }
        return shell("wm density reset")
    }

    /**
     * Set window animation scale (0 = off, 1 = default, 0.5 = fast).
     */
    suspend fun setAnimationScale(scale: Float): String {
        checkReady()?.let { return it }
        val s = scale.toString()
        return listOf(
            shell("settings put global window_animation_scale $s"),
            shell("settings put global transition_animation_scale $s"),
            shell("settings put global animator_duration_scale $s")
        ).let { "Animation scale: $s" }
    }

    /**
     * Toggle dark mode globally.
     */
    suspend fun setDarkMode(enabled: Boolean): String {
        checkReady()?.let { return it }
        val uiMode = if (enabled) "yes" else "no"
        return shell("cmd uimode night $uiMode")
            .let { if (it.isBlank()) "Dark mode: $enabled" else it }
    }

    /**
     * Take a screenshot and save to Downloads.
     */
    suspend fun screenshot(outputPath: String = "/sdcard/Download/screenshot_${System.currentTimeMillis()}.png"): String {
        checkReady()?.let { return it }
        return shell("screencap -p $outputPath")
            .let { if (it.isBlank()) "Screenshot saved: $outputPath" else it }
    }

    /**
     * Simulate a key press (KeyEvent).
     * Common keys: 3=HOME, 4=BACK, 26=POWER, 24=VOL_UP, 25=VOL_DOWN,
     *              64=BROWSER, 27=CAMERA, 82=MENU, 187=RECENTS
     */
    suspend fun pressKey(keyCode: Int): String {
        checkReady()?.let { return it }
        return shell("input keyevent $keyCode")
            .let { "Key $keyCode pressed" }
    }

    /**
     * Simulate a tap at screen coordinates.
     */
    suspend fun tap(x: Int, y: Int): String {
        checkReady()?.let { return it }
        return shell("input tap $x $y")
            .let { "Tapped ($x, $y)" }
    }

    /**
     * Simulate a swipe gesture.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): String {
        checkReady()?.let { return it }
        return shell("input swipe $x1 $y1 $x2 $y2 $durationMs")
            .let { "Swipe ($x1,$y1)→($x2,$y2)" }
    }

    /**
     * Type text (simulates keyboard input).
     */
    suspend fun typeText(text: String): String {
        checkReady()?.let { return it }
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        return shell("input text $escaped")
            .let { "Typed: $text" }
    }

    /**
     * Set default app for an intent action.
     */
    suspend fun setDefaultApp(packageName: String, activityClass: String, action: String): String {
        checkReady()?.let { return it }
        return shell("cmd package set-home-activity $packageName/$activityClass")
    }

    /**
     * Get all running processes.
     */
    suspend fun getRunningProcesses(): String {
        checkReady()?.let { return it }
        return shell("ps -A | grep -v kthread | head -30")
    }

    /**
     * Get detailed battery stats.
     */
    suspend fun getBatteryStats(): String {
        checkReady()?.let { return it }
        return shell("dumpsys battery")
    }

    /**
     * Get memory info.
     */
    suspend fun getMemoryInfo(): String {
        checkReady()?.let { return it }
        return shell("dumpsys meminfo | head -40")
    }

    /**
     * List installed packages with filter.
     */
    suspend fun listPackages(filter: String = ""): String {
        checkReady()?.let { return it }
        val cmd = if (filter.isBlank()) "pm list packages" else "pm list packages | grep $filter"
        return shell(cmd)
    }

    /**
     * Samsung DeX mode toggle (if supported).
     */
    suspend fun toggleDeX(enabled: Boolean): String {
        checkReady()?.let { return it }
        return writeSecureSetting("dex_enabled", if (enabled) "1" else "0")
    }

    // ─── Setup instructions ────────────────────────────────────────────────────

    fun getSetupInstructions(): String = """
SHIZUKU SETUP — Elevated Permissions (No Root Required)

Shizuku gives My Phone ADB-level system access. One-time setup:

1. Enable Developer Options
   Settings → About phone → tap "Build number" 7 times

2. Enable Wireless Debugging
   Settings → Developer options → Wireless debugging → ON

3. Install Shizuku
   Play Store → search "Shizuku" by Rikka

4. Start Shizuku
   Open Shizuku → "Start via Wireless Debugging" → follow prompts

5. Grant My Phone access
   Shizuku will prompt when My Phone first uses it — tap Allow

WHAT THIS UNLOCKS:
• Grant/revoke any app permission silently
• Write system/secure settings (DPI, fonts, timeouts)
• Force-stop apps, clear data, disable bloatware
• Run shell commands with ADB privileges
• Dark mode, animation speed, display density control
• Silent APK install/uninstall
• Screen tap/swipe/key simulation (beyond Accessibility)

NOTE: Shizuku needs to be re-activated after each reboot
(unless you have root — then it persists automatically).
""".trimIndent()
}
