// claude/ScreenEventBus.kt
package com.lockjaw.claudeagent.claude

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ScreenEventBus {
    private val _events = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    fun emit(event: AccessibilityEvent) {
        _events.tryEmit(event)
    }
}
