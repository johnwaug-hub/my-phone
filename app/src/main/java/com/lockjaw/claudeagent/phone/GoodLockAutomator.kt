// phone/GoodLockAutomator.kt
package com.lockjaw.claudeagent.phone

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lockjaw.claudeagent.services.AgentAccessibilityService
import kotlinx.coroutines.delay

/**
 * Deep Good Lock automation via Accessibility Service.
 *
 * DUAL-VERSION SUPPORT: Automatically detects One UI version and uses the correct
 * UI label set. tryTap() always lists 8.5 labels first, then 7.x fallbacks, then
 * older variants — so the first match wins regardless of which version is installed.
 *
 * One UI 8.5 / Good Lock 3.0.13+ changes mapped:
 *   ThemePark v1.1.01.47 : 3-tab icon editor (Shape/Effect/Image), Liquid Glass,
 *     emboss/3D relief, gradient panels, monochrome, bookmark themes
 *   LockStar v8.5.00.10  : Swirl animation (NEW), AOD brightness, free positioning
 *   QuickStar v9.0.x     : tile images, numeric sliders, landscape edit, resize, clock side
 *   NavStar v8.0.00.69   : icon borders (NEW), gesture sensitivity
 *   Home Up 8.5          : icon label font+color, folder/widget blur, widget size
 *   NotiStar v8.0.88.0   : Work Profile + Secure Folder + Dual Messenger capture (all NEW)
 *   Sound Assistant      : per-app volume, step count 15-150, routing, panel style
 *   One Hand Operation+  : 12 gesture slots, handle size
 *   MultiStar            : Flex Mode (Z Flip critical), cover screen, split ratio
 *
 * One UI 7.x fallback labels also included throughout.
 * Older One UI (5/6) best-effort — core features still work.
 */
class GoodLockAutomator(private val context: Context) {

    companion object {
        private const val TAG        = "GoodLockAutomator"
        private const val TAP_DELAY  = 700L
        private const val OPEN_DELAY = 2000L

        const val PKG_GOODLOCK       = "com.samsung.android.goodlock"
        const val PKG_THEMEPARK      = "com.samsung.android.themedesigner"
        const val PKG_LOCKSTAR       = "com.samsung.systemui.nicklaus"
        const val PKG_HOMEUP         = "com.samsung.android.app.homestar"
        const val PKG_QUICKSTAR      = "com.samsung.systemui.quickstar"
        const val PKG_NOTISTAR       = "com.samsung.android.app.notistar"
        const val PKG_NAVSTAR        = "com.samsung.systemui.navstar"
        const val PKG_MULTISTAR      = "com.samsung.android.app.multistar"
        const val PKG_SOUNDASSISTANT = "com.samsung.android.soundassistant"
        const val PKG_ONEHAND        = "com.samsung.android.sidegesturepad"
        const val PKG_CLOCKFACE      = "com.samsung.android.app.clockpack"
        const val PKG_WONDERLAND     = "com.samsung.android.app.wonderland"
        const val PKG_REGISTAR       = "com.samsung.android.app.registar"
        const val PKG_KEYSCAFE       = "com.samsung.android.app.keyscafe"
        const val PKG_ROUTINES       = "com.samsung.android.app.routinestar"
        const val PKG_DISPLAYASST    = "com.samsung.android.app.displayassistant"

        // One UI version thresholds (SEM_PLATFORM_INT ranges)
        private const val ONE_UI_7_0 = 70000
        private const val ONE_UI_8_0 = 80000
        private const val ONE_UI_8_5 = 80500
    }

    private val a11y: AgentAccessibilityService
        get() = AgentAccessibilityService.instance
            ?: throw Exception("Accessibility service not running — enable in Settings → Accessibility → Installed services → My Phone")

    // ─── Device & version detection ───────────────────────────────────────────

    private fun isSamsung() =
        android.os.Build.MANUFACTURER.lowercase().contains("samsung") ||
                android.os.Build.BRAND.lowercase().contains("samsung")

    private fun requireSamsung(module: String): String? =
        if (!isSamsung()) "Good Lock ($module) requires a Samsung device." else null

    /**
     * Detect One UI version as an integer for comparison.
     * Returns values like 80500 (8.5), 80000 (8.0), 70000 (7.0), 60000 (6.0), etc.
     * Returns 0 if not Samsung or undetectable.
     */
    private val oneUiVersionInt: Int by lazy {
        if (!isSamsung()) return@lazy 0
        runCatching {
            val semInt = Class.forName("android.os.Build\$VERSION")
                .getField("SEM_PLATFORM_INT").getInt(null)
            // SEM_PLATFORM_INT: 180500 → 8.5 → we normalize to 80500
            // Major is semInt/10000, minor is (semInt%10000)/100
            val major = semInt / 10000
            val minor = (semInt % 10000) / 100
            major * 10000 + minor * 100
        }.getOrElse {
            // Fallback: estimate from Android SDK version
            when {
                android.os.Build.VERSION.SDK_INT >= 36 -> ONE_UI_8_5
                android.os.Build.VERSION.SDK_INT >= 35 -> ONE_UI_7_0
                android.os.Build.VERSION.SDK_INT >= 34 -> 60000
                android.os.Build.VERSION.SDK_INT >= 33 -> 50000
                else -> 40000
            }
        }
    }

    private fun isOneUI85OrNewer() = oneUiVersionInt >= ONE_UI_8_5
    private fun isOneUI80OrNewer() = oneUiVersionInt >= ONE_UI_8_0
    private fun isOneUI70OrNewer() = oneUiVersionInt >= ONE_UI_7_0

    // ─── Navigation primitives ─────────────────────────────────────────────────

    private suspend fun openModule(pkg: String, name: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            delay(OPEN_DELAY)
            true
        } else {
            // Open Good Lock hub as fallback so user can install
            context.packageManager.getLaunchIntentForPackage(PKG_GOODLOCK)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                delay(OPEN_DELAY)
            }
            Log.w(TAG, "$name not installed")
            false
        }
    }

    /** Try label variants in order, return true on first success */
    private suspend fun tryTap(vararg labels: String): Boolean {
        for (label in labels) {
            try {
                a11y.clickElement(label, null)
                delay(TAP_DELAY)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private suspend fun back(times: Int = 1) { repeat(times) { a11y.pressBack(); delay(500) } }
    private fun screen() = a11y.getScreenContent()

    // ══════════════════════════════════════════════════════════════════════════
    //  THEMEPARK  v1.1.01.47  (One UI 8.5)
    //  Changes vs 7.x:
    //    • Icon editor split into 3 tabs: Shape / Effect / Image
    //    • New effects: Liquid Glass, Emboss, 3D Relief, Gradient, Grain, Monochrome
    //    • Gradient can apply to specific panels (volume, notification, quick)
    //    • Bookmark themes with heart icon
    //    • Instant apply — no APK install step anymore
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun applyThemePark(
        accentColor: String = "Default",
        fontStyle: String = "Samsung One",
        iconShape: String = "Squircle",
        iconEffect: String = "None",
        applyGradientTo: String? = null,
        bookmarkTheme: Boolean = false
    ): String {
        requireSamsung("ThemePark")?.let { return it }
        if (!openModule(PKG_THEMEPARK, "ThemePark")) return "ThemePark not installed"
        val log = mutableListOf<String>()

        // Colors — "Colors" in 8.5, "Color palette" in 7.x
        if (tryTap("Colors", "Color palette", "Color")) {
            tryTap("Theme color", "Accent color", "Color")
            tryTap(accentColor, "Default")
            log += "Color: $accentColor"
            back()
        }

        // Font — "Text style" in 8.5, "Font style" in 7.x
        if (tryTap("Text style", "Font style", "Font")) {
            tryTap(fontStyle, "Samsung One")
            tryTap("Apply", "Done")
            log += "Font: $fontStyle"
            back()
        }

        // Icons — 3-tab editor in 8.5, flat scroll in 7.x and older
        if (tryTap("Icon shape", "Icons", "Icon style")) {
            if (isOneUI85OrNewer()) {
                // ── One UI 8.5: 3-tab editor ──────────────────────────────────
                // Tab 1: Shape
                tryTap("Shape")
                tryTap(iconShape, "Squircle")
                log += "Shape: $iconShape"

                // Tab 2: Effect (new in 8.5 — doesn't exist in 7.x)
                if (iconEffect != "None") {
                    tryTap("Effect")
                    delay(TAP_DELAY)
                    when (iconEffect) {
                        "Liquid Glass" -> { tryTap("Liquid Glass", "Liquid glass"); tryTap("Transparency", "Blur intensity", "Style") }
                        "Emboss", "3D Relief" -> { tryTap(iconEffect); tryTap("Depth", "Intensity", "Level") }
                        "Gradient" -> { tryTap("Gradient"); tryTap("Gradient style", "Style") }
                        "Monochrome" -> { tryTap("Monochrome"); tryTap("Tone", "Color") }
                        else -> tryTap(iconEffect)
                    }
                    log += "Effect: $iconEffect"
                }
                // Tab 3: Image — skip unless doing custom icons
            } else {
                // ── One UI 7.x and older: flat list ───────────────────────────
                tryTap("Shape", "Icon shape style")
                tryTap(iconShape, "Squircle")
                log += "Shape: $iconShape"
                // No Effect tab in 7.x — Glass is the only option some versions support
                if (iconEffect == "Glass") {
                    tryTap("Glass effect", "Glass icons", "Frosted glass")
                    log += "Effect: Glass (7.x)"
                }
            }

            tryTap("Apply", "Done")
            back()
        }

        // Gradient panels — new in 8.5 only, skip silently on older versions
        if (applyGradientTo != null && isOneUI85OrNewer()) {
            tryTap("Gradient", "Panel gradient", "UI gradient")
            tryTap(applyGradientTo, "Quick panel")
            tryTap("Apply", "Done")
            log += "Gradient→$applyGradientTo"
            back()
        } else if (applyGradientTo != null) {
            log += "Gradient skipped (requires One UI 8.5+)"
        }

        // Apply theme
        tryTap("Apply", "Apply theme", "Done")
        delay(600)
        tryTap("OK", "Confirm", "Apply")
        delay(TAP_DELAY)

        // Bookmark (One UI 8.5 — heart icon on theme card)
        if (bookmarkTheme) {
            tryTap("Add to favorites", "Bookmark", "♡", "Favorite")
            log += "Bookmarked"
        }

        return "ThemePark: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOCKSTAR  v8.5.00.10  (One UI 8.5)
    //  Changes vs 7.x:
    //    • "Swirl" unlock animation added
    //    • Manual AOD brightness for full-screen AOD models
    //    • Free element positioning (drag clock/notif/media anywhere)
    //    • Status bar visibility in unlock animation edit fixed
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureLockStar(
        unlockAnimation: String = "Swirl",
        clockStyle: String = "Default",
        clockPosition: String = "Top center",
        showNotifRibbon: Boolean = true,
        showMediaWidget: Boolean = true,
        showHelpText: Boolean = false,
        shortcutLeft: String = "Camera",
        shortcutRight: String = "Phone",
        aodBrightness: Int = -1
    ): String {
        requireSamsung("LockStar")?.let { return it }
        if (!openModule(PKG_LOCKSTAR, "LockStar")) return "LockStar not installed"
        val log = mutableListOf<String>()

        // Unlock animation
        // "Swirl" is new in One UI 8.5 — fall back to "Ripple" on older versions
        val resolvedAnimation = if (unlockAnimation == "Swirl" && !isOneUI85OrNewer()) {
            "Ripple".also { log += "Note: Swirl requires 8.5, using Ripple" }
        } else unlockAnimation

        if (tryTap("Unlock animation", "Animation", "Lock animation")) {
            tryTap(resolvedAnimation, "Default")
            log += "Animation: $resolvedAnimation"
            back()
        }

        // Clock
        if (tryTap("Clock", "Lock screen clock")) {
            tryTap("Clock style", "Style")
            tryTap(clockStyle, "Default")
            tryTap("Done", "Apply", "Confirm")
            back()
            tryTap("Clock position", "Position")
            tryTap(clockPosition, "Top center")
            back()
            log += "Clock: $clockStyle @ $clockPosition"
        }

        // Notification ribbon
        if (tryTap("Notifications", "Notification ribbon", "Notification display")) {
            tryTap(if (showNotifRibbon) "Show" else "Hide",
                   if (showNotifRibbon) "On" else "Off")
            log += "Notifs: $showNotifRibbon"
            back()
        }

        // Media widget
        tryTap("Media widget", "Media control", "Music widget")
        tryTap(if (showMediaWidget) "Show" else "Hide",
               if (showMediaWidget) "On" else "Off")
        back()

        // Help text (swipe/unlock hint)
        if (!showHelpText) {
            tryTap("Help text", "Swipe to unlock text", "Hint text")
            tryTap("Hide", "Off", "Remove")
            back()
        }

        // Shortcuts
        if (tryTap("Shortcuts", "Lock screen shortcuts", "Quick shortcuts")) {
            tryTap("Left shortcut", "Left")
            tryTap(shortcutLeft, "Camera")
            back()
            tryTap("Right shortcut", "Right")
            tryTap(shortcutRight, "Phone")
            back()
            log += "Shortcuts: $shortcutLeft / $shortcutRight"
            back()
        }

        // AOD brightness — One UI 8.5 full-screen AOD only
        if (aodBrightness in 0..100) {
            if (isOneUI85OrNewer()) {
                tryTap("Always On Display", "AOD", "Always-on display")
                tryTap("Brightness", "AOD brightness", "Manual brightness")
                try { a11y.inputText(null, aodBrightness.toString()) }
                catch (_: Exception) { tryTap("$aodBrightness%", "$aodBrightness") }
                log += "AOD brightness: $aodBrightness"
                back(2)
            } else {
                log += "AOD brightness skipped (requires One UI 8.5+)"
            }
        }

        return "LockStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HOME UP  (One UI 8.5)
    //  Changes vs 7.x:
    //    • Adjust font AND color of app icon labels (new)
    //    • Adjust widget content size (new)
    //    • Remove/add folder background blur (new)
    //    • Remove/add widget background blur (new)
    //    • Adjust large folder size (new)
    //    • Navigate folder pages (new)
    //    • DIY Home Screen finer controls
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureHomeUp(
        taskChangerLayout: String = "Grid",
        gridColumns: Int = 5,
        gridRows: Int = 5,
        folderStyle: String = "Pop-up window",
        folderBlur: Boolean = false,
        iconLabelFont: String = "Default",
        iconLabelColor: String = "Default",
        diyHomeScreen: Boolean = false,
        appDrawerScroll: String = "Vertical",
        widgetBlur: Boolean = false
    ): String {
        requireSamsung("Home Up")?.let { return it }
        if (!openModule(PKG_HOMEUP, "Home Up")) return "Home Up not installed"
        val log = mutableListOf<String>()

        // Task Changer
        if (tryTap("Task Changer", "Recents screen", "Recent apps style")) {
            tryTap("Layout", "Style", "View type")
            tryTap(taskChangerLayout, "Default")
            log += "Task Changer: $taskChangerLayout"
            back()
        }

        // Home Screen
        if (tryTap("Home Screen", "Home screen layout", "Home")) {
            // Grid
            tryTap("Grid", "App grid", "Grid size", "Layout")
            tryTap("${gridColumns}×${gridRows}", "${gridColumns}x${gridRows}", "$gridColumns by $gridRows")
            log += "Grid: ${gridColumns}×${gridRows}"
            back()

            // Icon labels — font+color control is NEW in One UI 8.5
            if (isOneUI85OrNewer()) {
                if (tryTap("App icon labels", "Icon labels", "Labels")) {
                    if (iconLabelFont != "Default") {
                        tryTap("Font", "Label font", "Text font")
                        tryTap(iconLabelFont)
                        back()
                        log += "Label font: $iconLabelFont"
                    }
                    if (iconLabelColor != "Default") {
                        tryTap("Color", "Label color", "Text color")
                        tryTap(iconLabelColor)
                        back()
                        log += "Label color: $iconLabelColor"
                    }
                    back()
                }
            } else if (iconLabelFont != "Default" || iconLabelColor != "Default") {
                log += "Icon label font/color skipped (requires One UI 8.5+)"
            }

            // DIY Home Screen
            if (diyHomeScreen) {
                tryTap("DIY Home Screen", "DIY Home", "Free layout", "Custom layout")
                tryTap("Enable", "Turn on", "On")
                log += "DIY: enabled"
                back()
            }

            back()
        }

        // Folders
        if (tryTap("Folder", "Folders", "Folder style")) {
            tryTap("Style", "Folder style", "Open style")
            tryTap(folderStyle, "Default")
            back()

            // Folder blur — new in 8.5, silently skip on older versions
            if (isOneUI85OrNewer()) {
                tryTap("Background blur", "Blur background", "Folder blur")
                tryTap(if (folderBlur) "On" else "Off",
                       if (folderBlur) "Enable" else "Remove blur")
                log += "Folder: $folderStyle blur=$folderBlur"
            } else {
                log += "Folder: $folderStyle"
            }
            back()
        }

        // Widget blur — new in 8.5
        if (isOneUI85OrNewer() && !widgetBlur &&
            tryTap("Widgets", "Widget settings", "Home screen widgets")) {
            tryTap("Background blur", "Widget blur", "Blur")
            tryTap("Off", "Remove blur", "Disable")
            log += "Widget blur: off"
            back()
        }

        // App Drawer
        if (tryTap("Apps screen", "App screen", "App drawer")) {
            tryTap("Scroll direction", "Scroll", "Layout direction")
            tryTap(appDrawerScroll, "Vertical")
            log += "Drawer: $appDrawerScroll"
            back()
        }

        return "Home Up: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  QUICKSTAR  v9.0.00.x  (One UI 8.5)
    //  Changes vs 7.x:
    //    • Custom images for individual QS tiles (new)
    //    • Landscape mode QS edit (new)
    //    • Resizable tiles (new)
    //    • Numeric brightness/volume slider values (new)
    //    • Quick setting button grid (column count) — restored after beta
    //    • Clock side (left/right)
    //    • Custom color scheme for notification panel
    //    • Status icon hide/show per icon
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureQuickStar(
        transparency: Int = 20,
        colorMode: String = "Follow theme",
        statusBarLayout: String = "Default",
        clockSide: String = "Right",
        hideStatusIcons: List<String> = emptyList(),
        showSliderValues: Boolean = true,
        tileGridColumns: Int = 4
    ): String {
        requireSamsung("QuickStar")?.let { return it }
        if (!openModule(PKG_QUICKSTAR, "QuickStar")) return "QuickStar not installed"
        val log = mutableListOf<String>()

        // Panel color + transparency
        if (tryTap("Quick settings panel", "Panel style", "Quick panel style", "Style")) {
            tryTap("Color", "Panel color", "Background color")
            tryTap(colorMode, "Follow theme")
            log += "Color: $colorMode"
            back()

            tryTap("Transparency", "Panel transparency", "Background opacity")
            try { a11y.inputText(null, transparency.toString()) }
            catch (_: Exception) { tryTap("$transparency%") }
            log += "Transparency: $transparency%"
            back()
        }

        // Tile grid — available in 8.5 (was broken in beta, restored in stable)
        // Also exists in older versions with different label
        if (tryTap("Button grid", "Quick settings grid", "Grid size", "Columns")) {
            tryTap("$tileGridColumns columns", "$tileGridColumns", "Columns: $tileGridColumns")
            log += "Grid: $tileGridColumns cols"
            back()
        }

        // Numeric slider values — new in 8.5 only
        if (isOneUI85OrNewer()) {
            if (tryTap("Slider values", "Show current values", "Numeric values", "Sliders")) {
                tryTap(if (showSliderValues) "Show" else "Hide",
                       if (showSliderValues) "On" else "Off")
                log += "Slider values: $showSliderValues"
                back()
            }
        }

        // Status bar
        if (tryTap("Status bar", "Status bar style", "Notification bar")) {
            tryTap(statusBarLayout, "Default")
            log += "Status bar: $statusBarLayout"

            // Clock side
            tryTap("Clock position", "Clock side", "Clock", "Time position")
            tryTap("${clockSide} side", clockSide, "${clockSide.lowercase()} side")
            log += "Clock: $clockSide"
            back()

            // Hide status icons
            if (hideStatusIcons.isNotEmpty()) {
                tryTap("Status bar icons", "Icons to show", "Show or hide icons", "Status icons")
                hideStatusIcons.forEach { icon -> tryTap(icon); delay(300) }
                log += "Hidden icons: ${hideStatusIcons.joinToString()}"
                back()
            }

            back()
        }

        return "QuickStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NAVSTAR  v8.0.00.69  (One UI 8.5)
    //  Changes vs 7.x:
    //    • Custom borders on navigation bar icons (new)
    //    • Privacy permission cleanup (removed photo/video access)
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureNavStar(
        navType: String = "Gestures",
        buttonOrder: String = "Default",
        iconBorders: Boolean = false,
        iconBorderColor: String = "Default",
        gestureHint: Boolean = false,
        gestureSensitivity: String = "Medium",
        hideNavBar: Boolean = false
    ): String {
        requireSamsung("NavStar")?.let { return it }
        if (!openModule(PKG_NAVSTAR, "NavStar")) return "NavStar not installed"
        val log = mutableListOf<String>()

        // Nav type
        tryTap(navType, "Navigation type", "Navigation style")
        log += "Nav: $navType"

        if (navType == "Buttons" || navType.contains("button", ignoreCase = true)) {
            // Button order
            if (tryTap("Button order", "Order", "Button arrangement")) {
                tryTap(buttonOrder, "Default")
                log += "Order: $buttonOrder"
                back()
            }

            // Icon borders — NEW in NavStar v8.0.00.69 (One UI 8.0+)
            if (isOneUI80OrNewer()) {
                if (tryTap("Icon border", "Button border", "Navigation icon border", "Border")) {
                    tryTap(if (iconBorders) "On" else "Off",
                           if (iconBorders) "Enable" else "Disable")
                    if (iconBorders && iconBorderColor != "Default") {
                        tryTap("Border color", "Color")
                        tryTap(iconBorderColor)
                        back()
                    }
                    log += "Borders: $iconBorders"
                    back()
                }
            } else if (iconBorders) {
                log += "Icon borders skipped (requires One UI 8.0+)"
            }

            if (hideNavBar) {
                tryTap("Hide navigation bar", "Auto hide", "Fully hidden")
                log += "Hidden: true"
            }
        } else {
            // Gesture mode
            tryTap("Gesture hint", "Show gesture hint", "Hint line", "Swipe indicator")
            tryTap(if (gestureHint) "Show" else "Hide", if (gestureHint) "On" else "Off")
            log += "Hint: $gestureHint"
            back()

            tryTap("Sensitivity", "Gesture sensitivity")
            tryTap(gestureSensitivity, "Medium")
            log += "Sensitivity: $gestureSensitivity"
            back()
        }

        return "NavStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MULTISTAR  — Z Flip 7 critical
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureMultiStar(
        flexModeApps: List<String> = emptyList(),
        coverScreenApps: List<String> = emptyList(),
        splitRatio: String = "50:50",
        popupViewApps: List<String> = emptyList()
    ): String {
        requireSamsung("MultiStar")?.let { return it }
        if (!openModule(PKG_MULTISTAR, "MultiStar")) return "MultiStar not installed"
        val log = mutableListOf<String>()

        if (flexModeApps.isNotEmpty()) {
            if (tryTap("Flex mode", "Flex Mode", "Flex Mode panel")) {
                tryTap("Apps", "App list", "Flex Mode apps")
                flexModeApps.forEach { app -> tryTap(app); delay(300) }
                log += "Flex: ${flexModeApps.joinToString()}"
                back(2)
            }
        }

        if (coverScreenApps.isNotEmpty()) {
            if (tryTap("Cover screen", "Front screen", "Outer display", "Cover display")) {
                tryTap("Apps", "Apps on cover screen", "Cover screen apps")
                coverScreenApps.forEach { app -> tryTap(app); delay(300) }
                log += "Cover: ${coverScreenApps.joinToString()}"
                back(2)
            }
        }

        if (tryTap("Multi window", "Split screen", "Dual view", "Multi Window")) {
            tryTap("Default ratio", "Split ratio", "Ratio")
            tryTap(splitRatio, "50:50")
            log += "Split: $splitRatio"
            back()
        }

        if (popupViewApps.isNotEmpty() && tryTap("Pop-up view", "Popup view")) {
            popupViewApps.forEach { app -> tryTap(app); delay(300) }
            log += "Popup: ${popupViewApps.joinToString()}"
            back()
        }

        return "MultiStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SOUND ASSISTANT
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureSoundAssistant(
        volumeSteps: Int = 30,
        perAppVolumes: Map<String, Float> = emptyMap(),
        panelStyle: String = "Default",
        mediaVolumeButton: Boolean = true,
        routeToDevice: Map<String, String> = emptyMap()
    ): String {
        requireSamsung("Sound Assistant")?.let { return it }
        if (!openModule(PKG_SOUNDASSISTANT, "Sound Assistant")) return "Sound Assistant not installed"
        val log = mutableListOf<String>()

        // Step size
        if (tryTap("Volume step size", "Step size", "Volume steps", "Steps")) {
            try { a11y.inputText(null, volumeSteps.toString()) }
            catch (_: Exception) { tryTap("$volumeSteps", "$volumeSteps steps") }
            log += "Steps: $volumeSteps"
            back()
        }

        // Volume button
        if (tryTap("Volume button control", "Volume key", "Volume button action")) {
            tryTap(if (mediaVolumeButton) "Media volume" else "Ring volume",
                   if (mediaVolumeButton) "Media" else "Ring")
            log += "Button→${if (mediaVolumeButton) "media" else "ring"}"
            back()
        }

        // Panel style
        if (panelStyle != "Default" && tryTap("Volume panel style", "Panel style", "Style")) {
            tryTap(panelStyle)
            log += "Panel: $panelStyle"
            back()
        }

        // Per-app volumes
        if (perAppVolumes.isNotEmpty()) {
            if (tryTap("App volume", "Per-app volume", "Separate app sound", "App sound")) {
                perAppVolumes.forEach { (app, level) ->
                    tryTap(app)
                    try { a11y.inputText(null, (level * 100).toInt().toString()) }
                    catch (_: Exception) { tryTap("${(level * 100).toInt()}%") }
                    back()
                }
                log += "Per-app: ${perAppVolumes.keys.joinToString()}"
                back()
            }
        }

        // Routing
        if (routeToDevice.isNotEmpty()) {
            if (tryTap("Sound routing", "Audio routing", "Route audio")) {
                routeToDevice.forEach { (app, device) ->
                    tryTap(app); tryTap(device); back()
                }
                log += "Routing: ${routeToDevice.size} apps"
                back()
            }
        }

        return "Sound Assistant: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ONE HAND OPERATION+  — 12 gesture slots
    // ══════════════════════════════════════════════════════════════════════════

    data class GestureSlot(
        val side: String,        // "Left" | "Right"
        val direction: String,   // "Up" | "Diagonal up" | "Diagonal down"
        val swipeType: String = "Short",  // "Short" | "Long"
        val action: String
    )

    suspend fun configureOneHandOperation(
        gestures: List<GestureSlot> = listOf(
            GestureSlot("Left",  "Up",             "Short", "Recent apps"),
            GestureSlot("Left",  "Diagonal up",    "Short", "Screenshot"),
            GestureSlot("Left",  "Diagonal down",  "Short", "Back"),
            GestureSlot("Right", "Up",             "Short", "Notification panel"),
            GestureSlot("Right", "Diagonal up",    "Short", "Camera"),
            GestureSlot("Right", "Diagonal down",  "Short", "Home")
        ),
        handleSize: String = "Medium",
        vibration: Boolean = true
    ): String {
        requireSamsung("One Hand Operation+")?.let { return it }
        if (!openModule(PKG_ONEHAND, "One Hand Operation+")) return "One Hand Operation+ not installed"
        val log = mutableListOf<String>()

        // Handle size
        if (tryTap("Handle size", "Size", "Gesture handle size")) {
            tryTap(handleSize, "Medium")
            log += "Handle: $handleSize"
            back()
        }

        // Vibration
        tryTap("Vibration", "Vibrate on gesture")
        tryTap(if (vibration) "On" else "Off")
        back()

        // Gesture assignments
        gestures.forEach { g ->
            if (tryTap("${g.side} handle", "${g.side} side")) {
                val dirLabel = when (g.direction) {
                    "Up"            -> "Swipe up"
                    "Diagonal up"   -> "Swipe diagonally up"
                    "Diagonal down" -> "Swipe diagonally down"
                    else            -> g.direction
                }
                val typeLabel = if (g.swipeType == "Long") "Long swipe — $dirLabel" else dirLabel
                if (!tryTap(typeLabel)) tryTap(dirLabel, g.direction)
                tryTap(g.action)
                log += "${g.side[0]}${g.direction[0]}: ${g.action}"
                back(2)
            }
        }

        return "One Hand Op+: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CLOCKFACE
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureClockFace(
        clockName: String = "Minimal",
        primaryColor: String = "Follow theme",
        showDate: Boolean = true,
        showSteps: Boolean = false,
        showHeartRate: Boolean = false,
        applyToLock: Boolean = false
    ): String {
        requireSamsung("ClockFace")?.let { return it }
        if (!openModule(PKG_CLOCKFACE, "ClockFace")) return "ClockFace not installed"
        val log = mutableListOf<String>()

        tryTap(clockName); delay(TAP_DELAY)
        log += "Face: $clockName"

        if (tryTap("Color", "Clock color", "Primary color")) {
            tryTap(primaryColor, "Follow theme")
            log += "Color: $primaryColor"
            back()
        }

        if (tryTap("Information", "Complications", "Sub-info", "Details")) {
            if (showDate) tryTap("Date", "Show date")
            if (showSteps) tryTap("Steps", "Step count", "Daily steps")
            if (showHeartRate) tryTap("Heart rate", "Heart", "BPM")
            log += "Info: date=$showDate steps=$showSteps"
            back()
        }

        tryTap("Select", "Apply", "Set as AOD")
        delay(600)
        if (applyToLock) { tryTap("Apply to lock screen", "Lock screen also"); log += "+Lock" }

        return "ClockFace: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REGISTAR
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureRegiStar(
        powerDoubleTap: String = "Camera",
        powerLongPress: String = "Power menu",
        volDownLongPress: String = "None",
        volUpLongPress: String = "None"
    ): String {
        requireSamsung("RegiStar")?.let { return it }
        if (!openModule(PKG_REGISTAR, "RegiStar")) return "RegiStar not installed"
        val log = mutableListOf<String>()

        if (tryTap("Power button", "Side button")) {
            tryTap("Double press", "Double-tap", "Press twice")
            tryTap(powerDoubleTap, "Camera")
            back()
            log += "Power×2: $powerDoubleTap"

            tryTap("Press and hold", "Long press", "Hold")
            tryTap(powerLongPress, "Power menu")
            back()
            log += "Power hold: $powerLongPress"
            back()
        }

        if (tryTap("Volume buttons", "Volume key", "Volume button")) {
            if (volDownLongPress != "None") {
                tryTap("Volume down long press", "Volume down — hold", "Down hold")
                tryTap(volDownLongPress); back()
                log += "VolDown hold: $volDownLongPress"
            }
            if (volUpLongPress != "None") {
                tryTap("Volume up long press", "Volume up — hold", "Up hold")
                tryTap(volUpLongPress); back()
                log += "VolUp hold: $volUpLongPress"
            }
            back()
        }

        return "RegiStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NOTISTAR  v8.0.88.0  (One UI 8.5)
    //  Changes vs 7.x:
    //    • Work Profile notification saving (new)
    //    • Secure Folder notification saving (new)
    //    • Dual Messenger notification saving (new)
    //    • Duplicate notification fix
    //    • App-freeze fix
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureNotiStar(
        keepDuration: String = "30 days",
        groupByApp: Boolean = true,
        workProfile: Boolean = false,
        secureFolder: Boolean = false,
        dualMessenger: Boolean = false
    ): String {
        requireSamsung("NotiStar")?.let { return it }
        if (!openModule(PKG_NOTISTAR, "NotiStar")) return "NotiStar not installed"
        val log = mutableListOf<String>()

        if (tryTap("Settings", "⚙", "Preferences", "NotiStar settings")) {
            tryTap("Keep notifications for", "Storage duration", "Retention", "Keep for")
            tryTap(keepDuration, "30 days")
            log += "Keep: $keepDuration"
            back()

            if (tryTap("Group by app", "Sort by app", "Grouping")) {
                tryTap(if (groupByApp) "On" else "Off")
                log += "Group: $groupByApp"
                back()
            }

            // New in NotiStar v8.0.88.0 (One UI 8.0+) — Work Profile, Secure Folder, Dual Messenger
            if (isOneUI80OrNewer()) {
                if (workProfile) {
                    tryTap("Work Profile", "Work notifications", "Work")
                    tryTap("Save", "On", "Enable"); back()
                    log += "Work Profile: on"
                }
                if (secureFolder) {
                    tryTap("Secure Folder", "Secure notifications")
                    tryTap("Save", "On", "Enable"); back()
                    log += "Secure Folder: on"
                }
                if (dualMessenger) {
                    tryTap("Dual Messenger", "Dual app")
                    tryTap("Save", "On", "Enable"); back()
                    log += "Dual Messenger: on"
                }
            } else if (workProfile || secureFolder || dualMessenger) {
                log += "Work/Secure/Dual capture skipped (requires One UI 8.0+)"
            }

            back()
        }

        return "NotiStar: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  KEYS CAFE
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureKeysCafe(
        theme: String = "Dark",
        sizePreset: String = "Default",
        showEmoji: Boolean = true
    ): String {
        requireSamsung("Keys Cafe")?.let { return it }
        if (!openModule(PKG_KEYSCAFE, "Keys Cafe")) return "Keys Cafe not installed"
        val log = mutableListOf<String>()

        if (tryTap("Theme", "Keyboard theme")) { tryTap(theme, "Dark"); log += "Theme: $theme"; back() }
        if (tryTap("Size", "Keyboard size")) { tryTap(sizePreset, "Default"); log += "Size: $sizePreset"; back() }
        if (tryTap("Emoji key", "Emoji")) { tryTap(if (showEmoji) "Show" else "Hide"); log += "Emoji: $showEmoji"; back() }
        tryTap("Apply", "Done")

        return "Keys Cafe: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  WONDERLAND
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun configureWonderland(
        preset: String = "Aurora",
        speed: String = "Normal",
        density: String = "Medium",
        applyToAod: Boolean = true
    ): String {
        requireSamsung("Wonderland")?.let { return it }
        if (!openModule(PKG_WONDERLAND, "Wonderland")) return "Wonderland not installed"
        val log = mutableListOf<String>()

        tryTap(preset); delay(TAP_DELAY); log += preset

        if (tryTap("Options", "Settings", "Effect settings", "Customize")) {
            tryTap("Speed"); tryTap(speed, "Normal"); back()
            tryTap("Density", "Amount", "Particles"); tryTap(density, "Medium"); back()
            back()
        }

        if (applyToAod) {
            tryTap("Always On Display", "AOD", "Apply to AOD")
            tryTap("Apply", "On", "Use as AOD")
            log += "AOD: on"
            back()
        }

        tryTap("Set as wallpaper", "Apply", "Set wallpaper")
        tryTap("Home screen", "Home and lock screen", "Home and AOD", "All")
        delay(600)

        return "Wonderland: ${log.joinToString(", ")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ROUTINES+  — Touch Macros (improved for One UI 8.5)
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun openRoutinesPlus(macroName: String = "", startRecording: Boolean = false): String {
        requireSamsung("Routines+")?.let { return it }
        if (!openModule(PKG_ROUTINES, "Routines+")) return "Routines+ not installed"

        if (startRecording) {
            tryTap("Touch Macro", "Macro", "Touch macros")
            tryTap("Add", "+", "Record", "New macro")
            if (macroName.isNotBlank()) {
                tryTap("Name", "Macro name", "Title")
                a11y.inputText(null, macroName)
                back()
            }
            return "Routines+: recording started — '$macroName'"
        }

        tryTap("Touch Macro", "Macro")
        return "Routines+: Touch Macro screen opened"
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FULL VIBE REFRESH
    // ══════════════════════════════════════════════════════════════════════════

    suspend fun applyFullVibeRefresh(vibe: String): String {
        requireSamsung("Good Lock")?.let { return it }
        val results = mutableListOf<String>()

        when (vibe.lowercase().replace(" ", "_").replace("-", "_")) {

            "disc_golf" -> {
                results += applyThemePark("Green", "One UI Sans Rounded", "Circle", "None")
                results += configureLockStar("Swirl", "Minimal", showHelpText = false,
                    shortcutLeft = "Camera", shortcutRight = "Phone")
                results += configureHomeUp("Grid", 5, 5, folderBlur = false)
                results += configureClockFace("Minimal", "Green", showDate = true, showSteps = true)
                results += configureWonderland("Aurora", "Normal", "Medium", applyToAod = true)
                results += configureNavStar("Gestures", gestureHint = false)
                results += configureQuickStar(20, "Follow theme", clockSide = "Right", showSliderValues = true)
            }

            "dark_pro" -> {
                results += applyThemePark("Gray", "Monospace", "Squircle", "None")
                results += configureLockStar("Swirl", "Bold", showHelpText = false)
                results += configureHomeUp("List", 4, 6, folderBlur = false, widgetBlur = false)
                results += configureClockFace("Bold digital", "White", showDate = true)
                results += configureQuickStar(40, "Dark", "Transparent", "Left",
                    showSliderValues = true, tileGridColumns = 4)
                results += configureNavStar("Gestures", gestureHint = false, hideNavBar = true)
            }

            "ocean" -> {
                results += applyThemePark("Blue", "One UI Sans Rounded", "Squircle",
                    "Glass", applyGradientTo = "Quick panel")
                results += configureLockStar("Ripple", "World clock",
                    showNotifRibbon = true, showMediaWidget = true)
                results += configureClockFace("World time", "Blue", showDate = true)
                results += configureWonderland("Bubbles", "Slow", "High", applyToAod = true)
                results += configureQuickStar(15, "Follow theme", clockSide = "Right")
                results += configureNavStar("Buttons", iconBorders = true, iconBorderColor = "Default")
            }

            "sunset" -> {
                results += applyThemePark("Orange", "One UI Sans Rounded", "Circle",
                    "Gradient", applyGradientTo = "Notification panel")
                results += configureLockStar("Slide", "Analog classic", showHelpText = false)
                results += configureClockFace("Analog classic", "Orange", showDate = true)
                results += configureWonderland("Particles", "Normal", "Medium", applyToAod = true)
                results += configureQuickStar(10, "Follow theme")
            }

            "minimal_white" -> {
                results += applyThemePark("Default", "Samsung One", "Squircle", "None")
                results += configureLockStar("Swirl", "Minimal",
                    showNotifRibbon = false, showMediaWidget = false, showHelpText = false)
                results += configureHomeUp("Default", 4, 5, folderBlur = false, widgetBlur = false)
                results += configureClockFace("Minimal", "Follow theme", showDate = false)
                results += configureQuickStar(5, "Light", "Minimal", showSliderValues = false)
                results += configureNavStar("Gestures", gestureHint = false)
            }

            "neon_night" -> {
                results += applyThemePark("Violet", "One UI Sans Rounded", "Squircle",
                    "Liquid Glass", applyGradientTo = "Quick panel")
                results += configureLockStar("Swirl", "Bold", showHelpText = false)
                results += configureClockFace("Bold digital", "Follow theme", showDate = true)
                results += configureWonderland("Galaxy", "Normal", "High", applyToAod = true)
                results += configureQuickStar(60, "Dark", "Default", "Left",
                    showSliderValues = true, tileGridColumns = 4)
                results += configureNavStar("Buttons", iconBorders = true, iconBorderColor = "Accent")
            }

            "lockjaw" -> {
                results += applyThemePark("Orange", "One UI Sans Rounded", "Circle",
                    "Emboss", bookmarkTheme = true)
                results += configureLockStar("Swirl", "Minimal", showHelpText = false,
                    shortcutLeft = "Camera", shortcutRight = "Phone", aodBrightness = 50)
                results += configureHomeUp("Grid", 5, 5, folderBlur = false)
                results += configureClockFace("Minimal", "Orange", showDate = true, showSteps = true)
                results += configureWonderland("Aurora", "Normal", "Medium", applyToAod = true)
                results += configureNavStar("Gestures", gestureHint = false)
                results += configureQuickStar(20, "Follow theme", showSliderValues = true)
                results += configureSoundAssistant(30, mediaVolumeButton = true)
            }

            else -> return "Unknown vibe '$vibe'.\n" +
                    "Available: disc_golf, dark_pro, ocean, sunset, minimal_white, neon_night, lockjaw"
        }

        return "✓ Full '$vibe' refresh:\n${results.joinToString("\n")}"
    }
}
