// ui/screens/ProfileScreen.kt
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.lockjaw.claudeagent.data.DeviceProfile
import com.lockjaw.claudeagent.ui.AgentViewModel
import com.lockjaw.claudeagent.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: AgentViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load existing profile on entry, always refresh device fields from Build.*
    var profile by remember { mutableStateOf(DeviceProfile.autoDetect()) }
    var saved by remember { mutableStateOf(false) }
    var previewExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val existing = viewModel.getDeviceProfile()
        val detected = DeviceProfile.autoDetect()

        // Check if Good Lock is actually installed
        val goodLockInstalled = runCatching {
            context.packageManager.getPackageInfo("com.samsung.android.goodlock", 0)
            true
        }.getOrDefault(false)

        // Always use live Build.* for device fields, keep saved personal fields
        profile = existing.copy(
            manufacturer = detected.manufacturer,
            model = detected.model,
            androidVersion = detected.androidVersion,
            uiLayer = detected.uiLayer,
            isSamsung = detected.isSamsung,
            oneUiVersion = detected.oneUiVersion,
            isFoldable = detected.isFoldable,
            hasFlip = detected.hasFlip,
            hasFold = detected.hasFold,
            goodLockInstalled = goodLockInstalled
        )
    }

    fun save() {
        scope.launch {
            viewModel.saveDeviceProfile(profile)
            saved = true
            kotlinx.coroutines.delay(2000)
            saved = false
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Phone Profile", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Orange)
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) {
                        Text(if (saved) "✓ Saved" else "Save", color = Orange, fontWeight = FontWeight.Bold)
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
            // ── Info banner ──────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Orange.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, Orange.copy(alpha = 0.3f))
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Orange, modifier = Modifier.size(18.dp))
                    Text(
                        "This profile is injected into every Claude message as context. " +
                                "The more accurate it is, the better Claude understands your phone and situation.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            // ── Personal ─────────────────────────────────────────────────────
            ProfileSection(title = "PERSONAL", icon = Icons.Default.Person) {
                ProfileField("Your name", profile.userName, Icons.Default.Badge) {
                    profile = profile.copy(userName = it)
                }
                ProfileField("Nickname / callsign", profile.nickname, Icons.Default.EmojiEmotions) {
                    profile = profile.copy(nickname = it)
                }
                ProfileField("Location (city, state)", profile.location, Icons.Default.LocationOn) {
                    profile = profile.copy(location = it)
                }
                ProfileField("Timezone", profile.timezone, Icons.Default.Schedule) {
                    profile = profile.copy(timezone = it)
                }
                ProfileField("Occupation", profile.occupation, Icons.Default.Work) {
                    profile = profile.copy(occupation = it)
                }
                ProfileField(
                    "Interests (comma-separated)",
                    profile.interests,
                    Icons.Default.Favorite,
                    placeholder = "disc golf, coding, teaching..."
                ) { profile = profile.copy(interests = it) }
            }

            // ── Device ───────────────────────────────────────────────────────
            ProfileSection(title = "DEVICE", icon = Icons.Default.PhoneAndroid) {
                ProfileField("Manufacturer", profile.manufacturer, Icons.Default.Business) {
                    profile = profile.copy(manufacturer = it, isSamsung = it.lowercase().contains("samsung"))
                }
                ProfileField("Model", profile.model, Icons.Default.Devices, placeholder = "Galaxy Z Flip 6") {
                    profile = profile.copy(
                        model = it,
                        hasFlip = it.lowercase().contains("flip"),
                        hasFold = it.lowercase().contains("fold"),
                        isFoldable = it.lowercase().contains("flip") || it.lowercase().contains("fold")
                    )
                }
                ProfileField("UI layer / skin", profile.uiLayer, Icons.Default.Layers, placeholder = "One UI 8.5 / Pixel UI / OxygenOS") {
                    profile = profile.copy(uiLayer = it)
                }
                ProfileField("Android version", profile.androidVersion, Icons.Default.Android, placeholder = "15") {
                    profile = profile.copy(androidVersion = it)
                }

                // Samsung-specific
                if (profile.isSamsung) {
                    Divider(color = Border, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Samsung", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    ProfileField("One UI version", profile.oneUiVersion, Icons.Default.Star, placeholder = "8.5") {
                        profile = profile.copy(oneUiVersion = it)
                    }
                    ToggleField("Good Lock installed", profile.goodLockInstalled, Icons.Default.Lock) {
                        profile = profile.copy(goodLockInstalled = it)
                    }
                    ToggleField("Galaxy Z Flip (cover screen)", profile.hasFlip, Icons.Default.PhoneIphone) {
                        profile = profile.copy(hasFlip = it, isFoldable = it || profile.hasFold)
                    }
                    ToggleField("Galaxy Z Fold (inner display)", profile.hasFold, Icons.Default.Tablet) {
                        profile = profile.copy(hasFold = it, isFoldable = it || profile.hasFlip)
                    }
                }
            }

            // ── Apps & Context ────────────────────────────────────────────────
            ProfileSection(title = "APPS & CONTEXT", icon = Icons.Default.Apps) {
                ProfileField(
                    "Key apps Claude should know",
                    profile.frequentApps,
                    Icons.Default.GridView,
                    placeholder = "Putting Improver, Spotify, Disc Golf Scene..."
                ) { profile = profile.copy(frequentApps = it) }
                ProfileField(
                    "Anything else Claude should know",
                    profile.customContext,
                    Icons.Default.Notes,
                    placeholder = "I'm a teacher, I disc golf weekends, I build apps...",
                    singleLine = false
                ) { profile = profile.copy(customContext = it) }
            }

            // ── Response Style ────────────────────────────────────────────────
            ProfileSection(title = "RESPONSE STYLE", icon = Icons.Default.Tune) {
                Text("How should Claude respond?", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("concise", "detailed", "casual").forEach { style ->
                        val selected = profile.responseStyle == style
                        Surface(
                            onClick = { profile = profile.copy(responseStyle = style) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) Orange else SurfaceVariant,
                            border = if (selected) null else BorderStroke(1.dp, Border)
                        ) {
                            Text(
                                style.replaceFirstChar { it.uppercase() },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = if (selected) Color(0xFF0A0A0A) else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // ── Preview ───────────────────────────────────────────────────────
            ProfileSection(title = "SYSTEM PROMPT PREVIEW", icon = Icons.Default.Preview) {
                Surface(
                    onClick = { previewExpanded = !previewExpanded },
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0A0A),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("What Claude sees about you", color = TextSecondary, fontSize = 12.sp)
                            Icon(
                                if (previewExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (previewExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                profile.toSystemPromptBlock(),
                                color = Green.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ── Auto-detect button ────────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    val detected = DeviceProfile.autoDetect()
                    val goodLockInstalled = runCatching {
                        context.packageManager.getPackageInfo("com.samsung.android.goodlock", 0)
                        true
                    }.getOrDefault(false)
                    profile = profile.copy(
                        manufacturer = detected.manufacturer,
                        model = detected.model,
                        uiLayer = detected.uiLayer,
                        androidVersion = detected.androidVersion,
                        isSamsung = detected.isSamsung,
                        oneUiVersion = detected.oneUiVersion,
                        isFoldable = detected.isFoldable,
                        hasFlip = detected.hasFlip,
                        hasFold = detected.hasFold,
                        goodLockInstalled = goodLockInstalled
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Re-detect device info")
            }

            // Save button
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) {
                Text(
                    if (saved) "✓ Profile Saved!" else "Save Profile",
                    color = Color(0xFF0A0A0A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Reusable Components ──────────────────────────────────────────────────────

@Composable
fun ProfileSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(14.dp))
            Text(title, color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Surface,
            border = BorderStroke(1.dp, Border)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
fun ProfileField(
    label: String,
    value: String,
    icon: ImageVector,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Orange.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder.ifBlank { label }, color = TextDim, fontSize = 13.sp) },
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
                focusedBorderColor = Orange.copy(alpha = 0.5f),
                unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Orange
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )
    }
}

@Composable
fun ToggleField(label: String, checked: Boolean, icon: ImageVector, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Orange.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            Text(label, color = TextPrimary, fontSize = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Orange,
                uncheckedTrackColor = SurfaceVariant
            )
        )
    }
}
