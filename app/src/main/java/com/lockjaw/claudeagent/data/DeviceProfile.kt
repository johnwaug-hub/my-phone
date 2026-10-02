// data/DeviceProfile.kt
package com.lockjaw.claudeagent.data

import android.os.Build

/**
 * User-configurable profile that feeds directly into Claude's system prompt.
 * Stored in DataStore as JSON. Editable from the Profile settings screen.
 */
data class DeviceProfile(
    // ── Device ──
    val deviceName: String = "",
    val manufacturer: String = "",        // "Samsung" | "Google" | "OnePlus" | etc.
    val model: String = "",               // "Galaxy Z Flip 6" | "Pixel 9" | etc.
    val uiLayer: String = "",             // "One UI 8.5" | "Pixel UI" | "OxygenOS" | etc.
    val androidVersion: String = "",      // "15" | "14" etc.
    val isFoldable: Boolean = false,
    val hasFlip: Boolean = false,         // Z Flip cover screen
    val hasFold: Boolean = false,         // Z Fold flex window

    // ── Samsung / Good Lock specific ──
    val isSamsung: Boolean = false,
    val goodLockInstalled: Boolean = false,
    val oneUiVersion: String = "",        // "8.5" | "7.0" | "" (non-Samsung)

    // ── Personal ──
    val userName: String = "",
    val nickname: String = "",
    val location: String = "",
    val timezone: String = "",
    val occupation: String = "",
    val interests: String = "",           // comma-separated

    // ── Apps & context ──
    val frequentApps: String = "",        // apps Claude should know about
    val customContext: String = "",       // freeform "anything else Claude should know"

    // ── Preferences ──
    val preferredLanguage: String = "en-US",
    val responseStyle: String = "concise" // "concise" | "detailed" | "casual"
) {
    companion object {
        /**
         * Auto-detect as much as possible from Build constants.
         * User can then correct/add anything in the Profile screen.
         */
        fun autoDetect(): DeviceProfile {
            val brand = Build.BRAND.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            val isSamsung = brand.contains("samsung") || manufacturer.contains("samsung")
            val model = Build.MODEL
            val androidVer = Build.VERSION.RELEASE  // e.g. "16", "15", "14"
            val sdkInt = Build.VERSION.SDK_INT       // e.g. 36, 35, 34

            // Detect One UI version — Samsung encodes it in SEM_PLATFORM_INT
            val oneUiVer = if (isSamsung) {
                runCatching {
                    val semInt = Class.forName("android.os.Build\$VERSION")
                        .getField("SEM_PLATFORM_INT").getInt(null)
                    // e.g. 180500 → One UI 8.5, 170000 → One UI 7.0
                    val major = semInt / 10000
                    val minor = (semInt % 10000) / 100
                    if (minor > 0) "$major.$minor" else "$major.0"
                }.getOrElse {
                    // Fallback: map SDK to approximate One UI version
                    when {
                        sdkInt >= 36 -> "8.5"
                        sdkInt >= 35 -> "7.0"
                        sdkInt >= 34 -> "6.0"
                        sdkInt >= 33 -> "5.0"
                        else -> ""
                    }
                }
            } else ""

            val modelLower = model.lowercase()
            val isFlipper = modelLower.contains("flip")
            val isFolded = modelLower.contains("fold")

            // Build a human-readable UI layer string
            val uiLayer = when {
                isSamsung && oneUiVer.isNotBlank() -> "One UI $oneUiVer"
                brand.contains("google") || manufacturer.contains("google") -> "Pixel UI"
                brand.contains("oneplus") || manufacturer.contains("oneplus") -> "OxygenOS"
                brand.contains("xiaomi") || manufacturer.contains("xiaomi") -> "HyperOS"
                brand.contains("oppo") || manufacturer.contains("oppo") -> "ColorOS"
                brand.contains("motorola") || manufacturer.contains("motorola") -> "My UX"
                else -> "Android $androidVer"
            }

            return DeviceProfile(
                manufacturer = Build.MANUFACTURER,
                model = model,
                androidVersion = androidVer,
                isSamsung = isSamsung,
                isFoldable = isFlipper || isFolded,
                hasFlip = isFlipper,
                hasFold = isFolded,
                oneUiVersion = oneUiVer,
                uiLayer = uiLayer,
                // Good Lock presence — check if package is installed
                goodLockInstalled = false // ProfileScreen re-checks this on open
            )
        }
    }

    /**
     * Builds the personal context block injected into Claude's system prompt.
     * Only includes non-empty fields so the prompt stays clean.
     */
    fun toSystemPromptBlock(): String {
        val lines = mutableListOf<String>()

        if (userName.isNotBlank()) lines += "User name: $userName${if (nickname.isNotBlank()) " (\"$nickname\")" else ""}"
        if (location.isNotBlank()) lines += "Location: $location"
        if (timezone.isNotBlank()) lines += "Timezone: $timezone"
        if (occupation.isNotBlank()) lines += "Occupation: $occupation"
        if (interests.isNotBlank()) lines += "Interests: $interests"

        lines += "Device: ${deviceLabel()}"
        lines += "OS: ${uiLayer.ifBlank { "Android $androidVersion" }}"

        if (isSamsung) {
            lines += "Samsung device: yes"
            if (oneUiVersion.isNotBlank()) lines += "One UI version: $oneUiVersion"
            if (hasFlip) lines += "Form factor: Galaxy Z Flip (cover screen + Flex Mode available)"
            if (hasFold) lines += "Form factor: Galaxy Z Fold (inner + outer display)"
            lines += "Good Lock available: ${if (goodLockInstalled) "yes — full automation enabled" else "not installed"}"
        } else {
            lines += "Good Lock: not available (non-Samsung device)"
        }

        if (frequentApps.isNotBlank()) lines += "Key apps: $frequentApps"
        if (customContext.isNotBlank()) lines += "Additional context: $customContext"
        if (responseStyle.isNotBlank()) lines += "Preferred response style: $responseStyle"

        return lines.joinToString("\n")
    }

    fun deviceLabel(): String {
        val parts = listOfNotNull(
            manufacturer.takeIf { it.isNotBlank() },
            model.takeIf { it.isNotBlank() }
        )
        return parts.joinToString(" ").ifBlank { "Unknown device" }
    }

    /** True if Good Lock automation should be offered */
    fun supportsGoodLock(): Boolean = isSamsung && goodLockInstalled
}
