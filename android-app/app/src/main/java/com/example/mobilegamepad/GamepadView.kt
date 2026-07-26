package com.example.mobilegamepad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * Live picture of the controller state: stick positions, trigger travel and
 * which buttons are down. Reading raw numbers off a diagnostics dump makes it
 * hard to tell a dead control from one that is simply mapped to the wrong
 * axis, which is exactly the failure this project hit on real hardware.
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var state = ControllerState()

    private val idle = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
    private val active = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val onActive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
    private val label = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = outline
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
        isFakeBoldText = true
    }

    fun setState(newState: ControllerState) {
        if (newState != state) {
            state = newState
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 0.52f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(8f)

        val triggerH = dp(14f)
        drawTrigger(canvas, RectF(pad, pad, w / 2 - pad, pad + triggerH), state.leftTrigger, "LT")
        drawTrigger(canvas, RectF(w / 2 + pad, pad, w - pad, pad + triggerH), state.rightTrigger, "RT")

        val shoulderTop = pad + triggerH + dp(6f)
        val shoulderH = dp(18f)
        drawPill(canvas, RectF(pad, shoulderTop, w / 2 - pad, shoulderTop + shoulderH), "LB", GamepadProtocol.BUTTON_LEFT_SHOULDER)
        drawPill(canvas, RectF(w / 2 + pad, shoulderTop, w - pad, shoulderTop + shoulderH), "RB", GamepadProtocol.BUTTON_RIGHT_SHOULDER)

        val bodyTop = shoulderTop + shoulderH + dp(10f)
        val bodyBottom = h - dp(26f)
        val cy = (bodyTop + bodyBottom) / 2f
        val r = min((bodyBottom - bodyTop) / 2f, w / 9f)

        drawStick(canvas, w * 0.14f, cy, r, state.leftStickX, state.leftStickY, GamepadProtocol.BUTTON_LEFT_THUMB)
        drawStick(canvas, w * 0.86f, cy, r, state.rightStickX, state.rightStickY, GamepadProtocol.BUTTON_RIGHT_THUMB)
        drawDpad(canvas, w * 0.37f, cy, r * 0.92f)
        drawFaceButtons(canvas, w * 0.63f, cy, r * 0.92f)

        val pillW = dp(46f)
        val pillH = dp(16f)
        val pillY = h - pillH - dp(4f)
        val gap = dp(6f)
        val totalW = pillW * 3 + gap * 2
        var x = (w - totalW) / 2f
        listOf(
            "BACK" to GamepadProtocol.BUTTON_BACK,
            "GUIDE" to GamepadProtocol.BUTTON_GUIDE,
            "START" to GamepadProtocol.BUTTON_START
        ).forEach { (name, bit) ->
            drawPill(canvas, RectF(x, pillY, x + pillW, pillY + pillH), name, bit)
            x += pillW + gap
        }
    }

    private fun drawTrigger(canvas: Canvas, bounds: RectF, value: Int, name: String) {
        val radius = bounds.height() / 2f
        fill.color = idle
        canvas.drawRoundRect(bounds, radius, radius, fill)

        if (value > 0) {
            val filled = RectF(bounds)
            filled.right = bounds.left + bounds.width() * (value / 255f)
            fill.color = active
            canvas.drawRoundRect(filled, radius, radius, fill)
        }
        canvas.drawRoundRect(bounds, radius, radius, stroke)

        text.color = if (value > 128) onActive else label
        canvas.drawText(name, bounds.centerX(), bounds.centerY() + text.textSize / 3f, text)
    }

    private fun drawPill(canvas: Canvas, bounds: RectF, name: String, bit: Int) {
        val pressed = state.isPressed(bit)
        val radius = bounds.height() / 2f
        fill.color = if (pressed) active else idle
        canvas.drawRoundRect(bounds, radius, radius, fill)
        canvas.drawRoundRect(bounds, radius, radius, stroke)
        text.color = if (pressed) onActive else label
        canvas.drawText(name, bounds.centerX(), bounds.centerY() + text.textSize / 3f, text)
    }

    private fun drawStick(canvas: Canvas, cx: Float, cy: Float, r: Float, x: Short, y: Short, clickBit: Int) {
        val clicked = state.isPressed(clickBit)
        fill.color = if (clicked) active else idle
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)

        // Screen Y grows downward while the protocol uses positive-up.
        val knobR = r * 0.34f
        val travel = r - knobR - dp(2f)
        val knobX = cx + (x / 32767f) * travel
        val knobY = cy - (y / 32767f) * travel
        fill.color = if (clicked) onActive else active
        canvas.drawCircle(knobX, knobY, knobR, fill)
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arm = r * 0.42f
        val thick = r * 0.34f
        data class Arm(val bit: Int, val dx: Float, val dy: Float)
        listOf(
            Arm(GamepadProtocol.BUTTON_DPAD_UP, 0f, -1f),
            Arm(GamepadProtocol.BUTTON_DPAD_DOWN, 0f, 1f),
            Arm(GamepadProtocol.BUTTON_DPAD_LEFT, -1f, 0f),
            Arm(GamepadProtocol.BUTTON_DPAD_RIGHT, 1f, 0f)
        ).forEach { a ->
            val ccx = cx + a.dx * (arm + thick / 2f)
            val ccy = cy + a.dy * (arm + thick / 2f)
            val halfW = if (a.dx != 0f) arm / 1.6f else thick / 2f
            val halfH = if (a.dy != 0f) arm / 1.6f else thick / 2f
            val rect = RectF(ccx - halfW, ccy - halfH, ccx + halfW, ccy + halfH)
            fill.color = if (state.isPressed(a.bit)) active else idle
            canvas.drawRoundRect(rect, dp(3f), dp(3f), fill)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), stroke)
        }
    }

    private fun drawFaceButtons(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val offset = r * 0.58f
        val br = r * 0.30f
        data class Face(val name: String, val bit: Int, val dx: Float, val dy: Float)
        listOf(
            Face("Y", GamepadProtocol.BUTTON_Y, 0f, -1f),
            Face("A", GamepadProtocol.BUTTON_A, 0f, 1f),
            Face("X", GamepadProtocol.BUTTON_X, -1f, 0f),
            Face("B", GamepadProtocol.BUTTON_B, 1f, 0f)
        ).forEach { f ->
            val bx = cx + f.dx * offset
            val by = cy + f.dy * offset
            val pressed = state.isPressed(f.bit)
            fill.color = if (pressed) active else idle
            canvas.drawCircle(bx, by, br, fill)
            canvas.drawCircle(bx, by, br, stroke)
            text.color = if (pressed) onActive else label
            canvas.drawText(f.name, bx, by + text.textSize / 3f, text)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
