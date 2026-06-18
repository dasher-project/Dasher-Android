package org.dasherproject.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Renders one Dasher frame from the flat `[op, a, b, c, d, argb]` command buffer
 * produced by [org.dasherproject.android.NativeBridge.nativeFrame].
 *
 * Directly adapted from Dasher-Mobile's `DasherCanvasView` (the command-buffer
 * protocol is identical to DasherCore's C API `dasher_frame` output), with the
 * package updated and the pause overlay retained.
 *
 * | op | Primitive        | Parameters                       |
 * |----|------------------|----------------------------------|
 * | 0  | Clear            | argb                             |
 * | 1  | Circle           | cx,cy,r,filled(0/1)              |
 * | 2  | Line             | x1,y1 → x2,y2                    |
 * | 3  | Stroked rect     | x1,y1 → x2,y2                    |
 * | 4  | Filled rect      | x1,y1 → x2,y2                    |
 * | 5  | Text             | x,y,fontSize,stringIndex         |
 */
class DasherCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        // DESIGN.md §Canvas Glyphs: heavy weight (700) + subpixel smoothing for
        // high-speed zoom legibility. Android's default bold sans (Roboto/Noto) is
        // the platform equivalent of the guide's "Arial / Noto Sans".
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }

    private var commands: IntArray = IntArray(0)
    private var strings: Array<String> = emptyArray()

    /** When true, a translucent overlay is drawn to signal the engine is paused. */
    var showPauseOverlay: Boolean = false

    /** `(width, height)` in px when the view lays out. Wire to the engine. */
    var onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null

    /** `(action, x, y)` per [MotionEvent]; action is 0/1/2 (DOWN/MOVE/UP). */
    var onTouchInput: ((Int, Float, Float) -> Unit)? = null

    /** Accepts a new frame and schedules a redraw on the next animation frame. */
    fun submitFrame(frameCommands: IntArray, frameStrings: Array<String> = emptyArray()) {
        commands = frameCommands
        strings = frameStrings
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onSurfaceSizeChanged?.invoke(w, h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouchInput?.invoke(0, event.x, event.y)
            MotionEvent.ACTION_MOVE -> onTouchInput?.invoke(1, event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchInput?.invoke(2, event.x, event.y)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = commands
        if (data.isEmpty()) return

        if (showPauseOverlay) {
            fillPaint.color = 0x80D3D3D3.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }

        // Compute the bounding box of all primitives to optionally normalise into view.
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        var k = 0
        while (k + 5 < data.size) {
            when (data[k]) {
                1 -> {
                    val a = data[k + 1]; val b = data[k + 2]; val c = data[k + 3]
                    minX = min(minX, a - c); maxX = max(maxX, a + c)
                    minY = min(minY, b - c); maxY = max(maxY, b + c)
                }
                2, 3, 4 -> {
                    val a = data[k + 1]; val b = data[k + 2]; val c = data[k + 3]; val d = data[k + 4]
                    minX = min(minX, min(a, c)); maxX = max(maxX, max(a, c))
                    minY = min(minY, min(b, d)); maxY = max(maxY, max(b, d))
                }
            }
            k += 6
        }

        val hasBounds = minX <= maxX && minY <= maxY
        val spanX = if (hasBounds) max(1, maxX - minX) else 1
        val spanY = if (hasBounds) max(1, maxY - minY) else 1
        val offScreen = hasBounds && (maxX < 0 || minX > width || maxY < 0 || minY > height)
        val hugeSpan = hasBounds && (spanX > width * 4 || spanY > height * 4)
        val normalize = hasBounds && width > 0 && height > 0 && (offScreen || hugeSpan)

        val sx = if (normalize) width.toFloat() / spanX else 1f
        val sy = if (normalize) height.toFloat() / spanY else 1f
        val tx = if (normalize) (-minX).toFloat() else 0f
        val ty = if (normalize) (-minY).toFloat() else 0f

        fun mapX(v: Int) = if (normalize) (v + tx) * sx else v.toFloat()
        fun mapY(v: Int) = if (normalize) (v + ty) * sy else v.toFloat()
        fun mapR(v: Int) = if (normalize) v * (sx + sy) * 0.5f else v.toFloat()

        val localStrings = strings
        var i = 0
        while (i + 5 < data.size) {
            val op = data[i]
            val a = data[i + 1]; val b = data[i + 2]; val c = data[i + 3]
            val d = data[i + 4]; val color = data[i + 5]
            when (op) {
                0 -> canvas.drawColor(color)
                1 -> {
                    val p = if (d != 0) fillPaint else strokePaint
                    p.color = color
                    canvas.drawCircle(mapX(a), mapY(b), mapR(c), p)
                }
                2 -> {
                    strokePaint.color = color
                    canvas.drawLine(mapX(a), mapY(b), mapX(c), mapY(d), strokePaint)
                }
                3 -> {
                    strokePaint.color = color
                    canvas.drawRect(min(mapX(a), mapX(c)), min(mapY(b), mapY(d)),
                        max(mapX(a), mapX(c)), max(mapY(b), mapY(d)), strokePaint)
                }
                4 -> {
                    fillPaint.color = color
                    canvas.drawRect(min(mapX(a), mapX(c)), min(mapY(b), mapY(d)),
                        max(mapX(a), mapX(c)), max(mapY(b), mapY(d)), fillPaint)
                }
                5 -> {
                    val idx = d
                    if (idx in localStrings.indices) {
                        textPaint.textSize = (mapR(c) * 2.5f).coerceAtLeast(8f)
                        textPaint.color = color
                        val fm = textPaint.fontMetrics
                        canvas.drawText(localStrings[idx], mapX(a), mapY(b) - fm.top, textPaint)
                    }
                }
            }
            i += 6
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
    }
}
