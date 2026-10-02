// ui/screens/SettingsScreen.kt
package com.lockjaw.claudeagent.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import com.lockjaw.claudeagent.data.PrefKeys
import com.lockjaw.claudeagent.ui.AgentViewModel
import com.lockjaw.claudeagent.workers.AppScanWorker
import com.lockjaw.claudeagent.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AgentViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Live permission state — rechecked on each recomposition
    fun isAccessibilityEnabled(): Boolean {
        val service = "${context.packageName}/com.lockjaw.claudeagent.services.AgentAccessibilityService"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(":").any { it.equals(service, ignoreCase = true) }
    }
    fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(context.packageName)
    }
    fun isOverlayEnabled(): Boolean = android.provider.Settings.canDrawOverlays(context)
    fun isUsageAccessEnabled(): Boolean {
        return try {
            val am = context.getSystemService(android.app.AppOpsManager::class.java)
            val mode = am.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }
    fun isDndAccessEnabled(): Boolean {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        return nm.isNotificationPolicyAccessGranted
    }

    var permTick by remember { mutableStateOf(0) }
    val accessibilityOk by remember(permTick) { derivedStateOf { isAccessibilityEnabled() } }
    val notificationOk by remember(permTick) { derivedStateOf { isNotificationListenerEnabled() } }
    val overlayOk by remember(permTick) { derivedStateOf { isOverlayEnabled() } }
    val usageOk by remember(permTick) { derivedStateOf { isUsageAccessEnabled() } }
    val dndOk by remember(permTick) { derivedStateOf { isDndAccessEnabled() } }

    // Refresh permission state every time screen is shown
    LaunchedEffect(Unit) { permTick++ }

    val apiKey by viewModel.apiKey.collectAsState(initial = "")
    val ttsEnabled by viewModel.ttsEnabled.collectAsState(initial = false)
    val autoExecute by viewModel.autoExecute.collectAsState(initial = false)
    val hapticEnabled by viewModel.hapticEnabled.collectAsState(initial = true)
    val includeLocation by viewModel.includeLocation.collectAsState(initial = false)
    val overlayEnabled by viewModel.overlayEnabled.collectAsState(initial = true)
    val monitorNotifications by viewModel.monitorNotifications.collectAsState(initial = true)
    val monitorSms by viewModel.monitorSms.collectAsState(initial = false)

    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Orange)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Key
            SettingSection(title = "API KEY") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Anthropic API Key", color = TextSecondary, fontSize = 13.sp)
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("sk-ant-...", color = TextDim) },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceVariant,
                            unfocusedContainerColor = SurfaceVariant,
                            focusedBorderColor = Orange,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.setSetting(PrefKeys.API_KEY, apiKeyInput.trim())
                                saved = true
                                kotlinx.coroutines.delay(2000)
                                saved = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Orange)
                    ) {
                        Text(if (saved) "✓ Saved!" else "Save Key", color = Color(0xFF0A0A0A), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Get your key at console.anthropic.com",
                        color = TextDim,
                        fontSize = 12.sp
                    )
                }
            }

            // Behavior
            SettingSection(title = "BEHAVIOR") {
                Column {
                    ToggleRow(Icons.Default.VolumeUp, "Text-to-Speech", "Claude reads responses aloud", ttsEnabled) {
                        scope.launch { viewModel.setSetting(PrefKeys.TTS_ENABLED, it) }
                    }
                    Divider(color = Border)
                    ToggleRow(Icons.Default.LocationOn, "Share Location", "Include location in every context", includeLocation) {
                        scope.launch { viewModel.setSetting(PrefKeys.INCLUDE_LOCATION, it) }
                    }
                    Divider(color = Border)
                    ToggleRow(Icons.Default.Vibration, "Haptic Feedback", "Vibrate on response received", hapticEnabled) {
                        scope.launch { viewModel.setSetting(PrefKeys.HAPTIC_ENABLED, it) }
                    }
                    Divider(color = Border)
                    ToggleRow(Icons.Default.FlashOn, "Auto-Execute Actions", "Run actions without confirmation (careful!)", autoExecute) {
                        scope.launch { viewModel.setSetting(PrefKeys.AUTO_EXECUTE, it) }
                    }
                }
            }

            // Deep Integration
            SettingSection(title = "DEEP INTEGRATION") {
                Column {
                    ToggleRow(Icons.Default.Layers, "Floating Overlay", "Show Claude button over all apps", overlayEnabled) {
                        scope.launch { viewModel.setSetting(PrefKeys.OVERLAY_ENABLED, it) }
                    }
                    Divider(color = Border)
                    ToggleRow(Icons.Default.Notifications, "Monitor Notifications", "Claude sees incoming notifications", monitorNotifications) {
                        scope.launch { viewModel.setSetting(PrefKeys.MONITOR_NOTIFICATIONS, it) }
                    }
                    Divider(color = Border)
                    ToggleRow(Icons.Default.Sms, "Monitor SMS", "Claude sees incoming texts", monitorSms) {
                        scope.launch { viewModel.setSetting(PrefKeys.MONITOR_SMS, it) }
                    }
                }
            }

            // Permissions
            SettingSection(title = "PERMISSIONS") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionRow("Accessibility Service", "Settings → Accessibility → Installed apps → My Phone → toggle on (if restricted: Settings → Apps → My Phone → ⋮ → Allow restricted settings first)", granted = accessibilityOk, critical = true)
                    PermissionRow("Notification Listener", "Settings → Apps → ⋮ menu → Special access → Notification access → My Phone → Allow", granted = notificationOk, critical = true)
                    PermissionRow("Display Over Other Apps", "Settings → Apps → My Phone → Special app access → Appear on top", granted = overlayOk, critical = false)
                    PermissionRow("Usage Access", "Settings → Apps → ⋮ → Special access → Usage data access", granted = usageOk, critical = false)
                    PermissionRow("Do Not Disturb Access", "Settings → Apps → ⋮ → Special access → Do not disturb", granted = dndOk, critical = false)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { permTick++ }, modifier = Modifier.align(Alignment.End)) {
                        Text("Refresh status", color = Orange, fontSize = 12.sp)
                    }
                }
            }

            // App List
            SettingSection(title = "INSTALLED APPS") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("App Library", color = TextPrimary, fontSize = 15.sp)
                        Text("Scan so Claude knows your apps", color = TextSecondary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            AppScanWorker.runNow(context)
                            android.widget.Toast.makeText(context, "Scanning apps...", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange)
                    ) {
                        Text("Scan Now", color = Color(0xFF0A0A0A), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Footer
            Text("My Phone v1.9.0 · Lock Jaw Disc Golf · lockjawdiscgolf.com",
                color = TextDim,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface,
            border = BorderStroke(1.dp, Border)
        ) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
fun ToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
            Column {
                Text(label, color = TextPrimary, fontSize = 15.sp)
                Text(description, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Orange,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceVariant
            )
        )
    }
}

@Composable
fun PermissionRow(label: String, path: String, granted: Boolean, critical: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (granted) "✅" else if (critical) "⚠️" else "ℹ️", fontSize = 14.sp)
        Column {
            Text(
                label,
                color = if (granted) Green else if (critical) Orange else TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (granted) "Granted" else path,
                color = if (granted) Green.copy(alpha = 0.7f) else TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}
