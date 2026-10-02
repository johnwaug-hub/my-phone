// ui/screens/ActionsScreen.kt
package com.lockjaw.claudeagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lockjaw.claudeagent.data.ActionLogEntry
import com.lockjaw.claudeagent.data.ActionResult
import com.lockjaw.claudeagent.data.PhoneAction
import com.lockjaw.claudeagent.ui.AgentViewModel
import com.lockjaw.claudeagent.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(viewModel: AgentViewModel) {
    val log by viewModel.actionLog.collectAsState()
    val gson = remember { Gson() }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Action Log", color = TextPrimary, fontWeight = FontWeight.Bold)
                        if (log.isNotEmpty()) {
                            Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariant) {
                                Text(
                                    "${log.size}",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F))
            )
        }
    ) { padding ->
        if (log.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚡", fontSize = 48.sp)
                    Text("No actions yet", color = TextSecondary, fontSize = 16.sp)
                    Text("Ask Claude to do something on your phone", color = TextDim, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(log, key = { it.id }) { entry ->
                    ActionLogCard(entry, gson)
                }
            }
        }
    }
}

@Composable
fun ActionLogCard(entry: ActionLogEntry, gson: Gson) {
    val actions = remember(entry.actionsJson) {
        try {
            val type = object : TypeToken<List<PhoneAction>>() {}.type
            gson.fromJson<List<PhoneAction>>(entry.actionsJson, type)
        } catch (_: Exception) { emptyList() }
    }
    val results = remember(entry.resultsJson) {
        try {
            val type = object : TypeToken<List<ActionResult>>() {}.type
            gson.fromJson<List<ActionResult>>(entry.resultsJson, type)
        } catch (_: Exception) { emptyList() }
    }
    val allSuccess = results.all { it.success }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (allSuccess) Border else Red.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTimestamp(entry.timestamp),
                    color = TextDim,
                    fontSize = 11.sp
                )
                Text(
                    if (allSuccess) "✓ all passed" else "⚠ some failed",
                    color = if (allSuccess) Green else Red,
                    fontSize = 11.sp
                )
            }
            actions.forEachIndexed { i, action ->
                val result = results.getOrNull(i)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(actionIcon(action.type), fontSize = 13.sp)
                    Column {
                        Text(
                            action.type.replace("_", " "),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val detail = action.to ?: action.text ?: action.message ?: action.packageName
                        if (detail != null) {
                            Text(detail.take(60), color = TextSecondary, fontSize = 11.sp)
                        }
                        if (result?.success == false) {
                            Text("✗ ${result.error}", color = Red, fontSize = 11.sp)
                        } else if (result?.output != null) {
                            Text(result.output.take(80), color = Green.copy(alpha = 0.8f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.US).format(Date(ts))
