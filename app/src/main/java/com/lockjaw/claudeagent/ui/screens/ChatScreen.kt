// ui/screens/ChatScreen.kt
@file:OptIn(ExperimentalLayoutApi::class)
package com.lockjaw.claudeagent.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.lockjaw.claudeagent.data.PhoneAction
import com.lockjaw.claudeagent.ui.*
import com.lockjaw.claudeagent.ui.AgentViewModel
import com.lockjaw.claudeagent.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AgentViewModel,
    onNavigateToSettings: () -> Unit,
    onVoiceInput: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Green)
                        )
                        Text("My Phone", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F)),
                actions = {
                    IconButton(onClick = {
                        viewModel.clearSession(context)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Clear", tint = TextSecondary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding()) {
                // Pending action confirmation banner
                AnimatedVisibility(visible = uiState.pendingActions.isNotEmpty()) {
                    ActionConfirmBanner(
                        actions = uiState.pendingActions,
                        onConfirm = { viewModel.confirmActions(context) },
                        onReject = { viewModel.rejectActions() }
                    )
                }

                // Error snackbar
                AnimatedVisibility(visible = uiState.error != null) {
                    Surface(color = Red.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(uiState.error ?: "", color = Red, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.clearError() }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Input row
                InputRow(
                    value = inputText,
                    onValueChange = { inputText = it },
                    isLoading = uiState.isLoading,
                    onSend = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            viewModel.sendMessage(context, text)
                            inputText = ""
                        }
                    },
                    onVoice = onVoiceInput
                )
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            EmptyState(
                onQuickAction = { text ->
                    viewModel.sendMessage(context, text)
                },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(message = msg)
                }
                if (uiState.isLoading) {
                    item { TypingIndicator() }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"

    if (isSystem) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.content,
                color = TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(SurfaceVariant)
                    .border(1.dp, Orange, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✦", fontSize = 12.sp, color = Orange)
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = if (isUser) Orange else SurfaceVariant,
                border = if (isUser) null else BorderStroke(1.dp, Border),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        message.content,
                        color = if (isUser) Color.White else TextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )
                    if (message.actions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            message.actions.forEach { action ->
                                ActionChip(action)
                            }
                        }
                    }
                }
            }
            Text(
                formatTime(message.timestamp),
                color = TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun ActionChip(action: PhoneAction) {
    val icon = actionIcon(action.type)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Green.copy(alpha = 0.1f)
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(icon, fontSize = 10.sp)
            Text(
                action.type.replace("_", " "),
                color = Green,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ActionConfirmBanner(
    actions: List<PhoneAction>,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        color = Color(0xFF1A0F00),
        border = BorderStroke(1.dp, Orange.copy(alpha = 0.5f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡", fontSize = 16.sp)
                Text(
                    "${actions.size} action${if (actions.size > 1) "s" else ""} ready",
                    color = Orange,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReject) {
                    Text("Cancel", color = TextSecondary)
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange)
                ) {
                    Text("Run ▶", color = Color(0xFF0A0A0A), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(
        Modifier.padding(start = 34.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val infiniteTransition = rememberInfiniteTransition(label = "dot$i")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(600, delayMillis = i * 200),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "alpha$i"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Orange.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    isLoading: Boolean,
    onSend: () -> Unit,
    onVoice: () -> Unit
) {
    Surface(color = Color(0xFF0F0F0F), border = BorderStroke(1.dp, Border)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Claude...", color = TextDim, fontSize = 15.sp) },
                maxLines = 4,
                shape = RoundedCornerShape(21.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceVariant,
                    unfocusedContainerColor = SurfaceVariant,
                    focusedBorderColor = Orange.copy(alpha = 0.5f),
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Orange
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
            )

            // Mic button
            IconButton(onClick = onVoice) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = TextSecondary)
            }

            // Send button
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Orange,
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank()
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) Orange else TextDim,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(onQuickAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val quickActions = listOf(
        "🌤" to "What's the weather in Tucson right now?",
        "📅" to "What's on my calendar today?",
        "🥏" to "Check disc golf conditions for Brandi Fenton Park",
        "🔆" to "Set my screen brightness to 30%",
        "🔔" to "What notifications do I have?",
        "🎨" to "Give me a fresh Good Lock aesthetic direction"
    )

    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✦", fontSize = 48.sp, color = Orange)
        Spacer(Modifier.height(12.dp))
        Text("My Phone", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Full phone access · Always ready", fontSize = 14.sp, color = TextSecondary)
        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            quickActions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (emoji, text) ->
                        Surface(
                            onClick = { onQuickAction(text) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariant,
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(emoji, fontSize = 16.sp)
                                Text(text.take(22), color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helpers
fun formatTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(ts))
}

fun actionIcon(type: String) = when (type) {
    "send_sms" -> "💬"
    "make_call" -> "📞"
    "send_email" -> "📧"
    "set_brightness" -> "☀️"
    "set_volume" -> "🔊"
    "toggle_wifi" -> "📶"
    "toggle_bluetooth" -> "🔵"
    "toggle_flashlight" -> "🔦"
    "toggle_dnd" -> "🔕"
    "open_app" -> "📱"
    "open_settings" -> "⚙️"
    "click_ui_element" -> "👆"
    "copy_clipboard" -> "📋"
    "speak" -> "🗣️"
    "vibrate" -> "📳"
    "notification" -> "🔔"
    "create_calendar_event" -> "📅"
    "search_contacts" -> "👥"
    "good_lock_refresh" -> "🎨"
    "read_screen_content" -> "👁️"
    else -> "⚡"
}

