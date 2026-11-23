package com.redemption.tvremote

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.redemption.tvremote.protocol.KeyCode
import com.redemption.tvremote.protocol.KeyDirection
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Swipe surface that replaces the arrow keys:
 * - swipe: one D-pad step per [stepDp] of finger travel (faster swipes = more steps)
 * - tap: OK
 * - press and hold without moving: long-press OK
 */
@SuppressLint("ViewConstructor")
class TrackpadView(
    context: Context,
    private val colors: Colors,
    private val onKey: (code: Int, direction: KeyDirection) -> Unit,
) : View(context) {

    data class Colors(val surface: Int, val dots: Int, val glow: Int, val hint: Int, val edge: Int)

    private val density = resources.displayMetrics.density
    private val stepPx = 34 * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val radius = 28 * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.surface }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.dots }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.edge; strokeCap = Paint.Cap.ROUND; strokeWidth = 4 * density }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.hint; textSize = 13 * density; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var accX = 0f; private var accY = 0f
    private var moved = false
    private var longSent = false
    private var touching = false

    private var flashDir = -1
    private var flashAlpha = 0f
    private val flash = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 260
        addUpdateListener { flashAlpha = it.animatedValue as Float; invalidate() }
    }

    private val longPress = Runnable {
        if (touching && !moved) {
            longSent = true
            onKey(KeyCode.DPAD_CENTER, KeyDirection.START_LONG)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        contentDescription = "Trackpad. Swipe to move, tap for OK."
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // Subtle dot grid so the surface reads as touchable.
        val gap = 22 * density
        val r = 1.3f * density
        val bottomLimit = if (touching) height - gap / 2 else height - 44 * density
        var y = gap
        while (y < bottomLimit) {
            var x = gap
            while (x < width - gap / 2) { canvas.drawCircle(x, y, r, dotPaint); x += gap }
            y += gap
        }

        if (touching) {
            val g = 70 * density
            glowPaint.shader = RadialGradient(lastX, lastY, g, colors.glow, colors.glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(lastX, lastY, g, glowPaint)
        } else {
            canvas.drawText("Swipe to move  ·  Tap for OK", width / 2f, height - 18 * density, hintPaint)
        }

        if (flashAlpha > 0f && flashDir >= 0) {
            edgePaint.alpha = (255 * flashAlpha).toInt()
            val inset = 10 * density
            val len = 28 * density
            val cx = width / 2f; val cy = height / 2f
            when (flashDir) {
                KeyCode.DPAD_UP -> canvas.drawLine(cx - len, inset, cx + len, inset, edgePaint)
                KeyCode.DPAD_DOWN -> canvas.drawLine(cx - len, height - inset, cx + len, height - inset, edgePaint)
                KeyCode.DPAD_LEFT -> canvas.drawLine(inset, cy - len, inset, cy + len, edgePaint)
                KeyCode.DPAD_RIGHT -> canvas.drawLine(width - inset, cy - len, width - inset, cy + len, edgePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                accX = 0f; accY = 0f
                moved = false; longSent = false; touching = true
                postDelayed(longPress, 500)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                accX += e.x - lastX
                accY += e.y - lastY
                lastX = e.x; lastY = e.y
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop && !longSent) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (moved) emitSteps()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touching = false
                when {
                    longSent -> onKey(KeyCode.DPAD_CENTER, KeyDirection.END_LONG)
                    !moved && e.actionMasked == MotionEvent.ACTION_UP -> {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onKey(KeyCode.DPAD_CENTER, KeyDirection.SHORT)
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun emitSteps() {
        var guard = 0
        while (guard++ < 6) {
            val horizontal = abs(accX) >= abs(accY)
            val amount = if (horizontal) accX else accY
            if (abs(amount) < stepPx) return
            val key = when {
                horizontal && amount > 0 -> KeyCode.DPAD_RIGHT
                horizontal -> KeyCode.DPAD_LEFT
                amount > 0 -> KeyCode.DPAD_DOWN
                else -> KeyCode.DPAD_UP
            }
            // Keep the remainder on the moving axis; drop drift on the other one.
            if (horizontal) { accX -= Math.signum(accX) * stepPx; accY = 0f }
            else { accY -= Math.signum(accY) * stepPx; accX = 0f }
            onKey(key, KeyDirection.SHORT)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            flashDir = key
            flash.cancel(); flash.start()
        }
    }
}
