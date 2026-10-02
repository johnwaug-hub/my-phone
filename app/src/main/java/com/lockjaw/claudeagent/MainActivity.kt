// MainActivity.kt
package com.lockjaw.claudeagent

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.*
import com.lockjaw.claudeagent.overlay.OverlayManager
import com.lockjaw.claudeagent.services.ClaudeAgentService
import com.lockjaw.claudeagent.ui.AgentViewModel
import com.lockjaw.claudeagent.ui.screens.*
import com.lockjaw.claudeagent.ui.theme.*
import com.lockjaw.claudeagent.voice.VoiceManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()
    private var overlayManager: OverlayManager? = null
    private var voiceManager: VoiceManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handle results if needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Start the always-on service
        ClaudeAgentService.start(this)

        // Request runtime permissions
        requestPermissions()

        // Voice
        voiceManager = VoiceManager(this)

        setContent {
            ClaudeAgentTheme {
                AgentApp(viewModel, onVoiceInput = { startVoiceInput() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.registerReceiver(this)
        // Start overlay if enabled and permission granted
        if (Settings.canDrawOverlays(this)) {
            if (overlayManager == null) {
                overlayManager = OverlayManager(this)
                overlayManager?.show()
            }
        }
    }

    override fun onPause() {
        viewModel.unregisterReceiver(this)
        super.onPause()
    }

    override fun onDestroy() {
        overlayManager?.destroy()
        voiceManager?.shutdown()
        super.onDestroy()
    }

    private fun startVoiceInput() {
        voiceManager?.let { vm ->
            viewModel.setListening(true)
            vm.onSpeechResult = { text ->
                viewModel.setListening(false)
                viewModel.sendMessage(this, text)
            }
            vm.onSpeechError = { _ ->
                viewModel.setListening(false)
            }
            vm.startListening()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }

        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApp(viewModel: AgentViewModel, onVoiceInput: () -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        containerColor = Background,
        bottomBar = {
            if (currentRoute in listOf("chat", "actions", "profile")) {
                NavigationBar(
                    containerColor = Color(0xFF0F0F0F),
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                        label = { Text("Claude") },
                        selected = currentRoute == "chat",
                        onClick = { navController.navigate("chat") { popUpTo("chat") { inclusive = true } } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange, selectedTextColor = Orange,
                            unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                            indicatorColor = Orange.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.FlashOn, contentDescription = "Actions") },
                        label = { Text("Actions") },
                        selected = currentRoute == "actions",
                        onClick = { navController.navigate("actions") { popUpTo("actions") { inclusive = true } } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange, selectedTextColor = Orange,
                            unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                            indicatorColor = Orange.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                        label = { Text("Profile") },
                        selected = currentRoute == "profile",
                        onClick = { navController.navigate("profile") { popUpTo("profile") { inclusive = true } } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orange, selectedTextColor = Orange,
                            unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                            indicatorColor = Orange.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) }
        ) {
            composable("chat") {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { navController.navigate("settings") },
                    onVoiceInput = onVoiceInput
                )
            }
            composable("actions") {
                ActionsScreen(viewModel = viewModel)
            }
            composable("profile") {
                ProfileScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
