// overlay/OverlayManager.kt
package com.lockjaw.claudeagent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.lockjaw.claudeagent.services.ClaudeAgentService
import kotlinx.coroutines.*

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null
    private var isExpanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = 24
            y = 200
        }

        // Build the floating button view programmatically
        val layout = buildOverlayLayout()
        overlayView = layout

        var lastX = 0f
        var lastY = 0f
        var moved = false

        layout.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        moved = true
                        params.x = maxOf(0, params.x - dx.toInt())
                        params.y = maxOf(0, params.y + dy.toInt())
                        windowManager.updateViewLayout(view, params)
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpanded(layout, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(layout, params)
    }

    private fun buildOverlayLayout(): FrameLayout {
        val ctx = context
        val layout = FrameLayout(ctx)

        // The collapsed FAB button
        val fab = TextView(ctx).apply {
            text = "✦"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            width = 120
            height = 120
            gravity = Gravity.CENTER
        }
        fab.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFF6B35.toInt())
            setSize(120, 120)
        }
        layout.addView(fab)
        return layout
    }

    private fun toggleExpanded(layout: ViewGroup, params: WindowManager.LayoutParams) {
        if (isExpanded) {
            collapseOverlay(layout, params)
        } else {
            expandOverlay(layout, params)
        }
        isExpanded = !isExpanded
    }

    private fun expandOverlay(layout: ViewGroup, params: WindowManager.LayoutParams) {
        layout.removeAllViews()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }
        container.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(0xEE0F0F0F.toInt())
            setStroke(2, 0xFFFF6B35.toInt())
        }

        val input = EditText(context).apply {
            hint = "Ask Claude..."
            setHintTextColor(0xFF555555.toInt())
            setTextColor(0xFFEEEEEE.toInt())
            textSize = 14f
            minWidth = 700
            maxLines = 3
            background = null
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }

        val sendBtn = Button(context).apply {
            text = "Send"
            setTextColor(0xFF0A0A0A.toInt())
            textSize = 13f
        }
        sendBtn.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16f
            setColor(0xFFFF6B35.toInt())
        }
        sendBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                ClaudeAgentService.sendMessage(context, text)
                input.text.clear()
                collapseOverlay(layout, params)
                isExpanded = false
            }
        }

        val closeBtn = Button(context).apply {
            text = "✕"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            background = null
        }
        closeBtn.setOnClickListener {
            collapseOverlay(layout, params)
            isExpanded = false
        }

        btnRow.addView(closeBtn)
        btnRow.addView(sendBtn)
        container.addView(input)
        container.addView(btnRow)
        layout.addView(container)

        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.updateViewLayout(layout, params)
    }

    private fun collapseOverlay(layout: ViewGroup, params: WindowManager.LayoutParams) {
        layout.removeAllViews()
        val fab = TextView(context).apply {
            text = "✦"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            width = 120
            height = 120
        }
        fab.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFF6B35.toInt())
            setSize(120, 120)
        }
        layout.addView(fab)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(layout, params)
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    fun destroy() {
        hide()
        scope.cancel()
    }
}
