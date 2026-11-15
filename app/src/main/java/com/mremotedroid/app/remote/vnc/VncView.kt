package com.mremotedroid.app.remote.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/**
 * Shows the remote framebuffer and turns touches into mouse events:
 * tap = click, double tap = double click, long press then move = drag,
 * two-finger tap = right click, two-finger drag = wheel, pinch = zoom,
 * one-finger drag = pan the zoomed desktop.
 */
class VncView(context: Context) : View(context) {

    interface Listener {
        fun onPointer(x: Int, y: Int, buttons: Int)
        fun onKeysym(keysym: Int, down: Boolean)
        /** A character or key typed on the keyboard; the activity adds sticky modifiers. */
        fun onTyped(keysym: Int)
    }

    var listener: Listener? = null

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var dragging = false
    private var multiTouch = false
    private var twoFingerStart = 0L
    private var twoFingerMoved = false
    private var lastTwoFingerY = 0f
    private var wheelRemainder = 0f

    fun setFramebuffer(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    /** Copies a changed region; safe to call from the connection thread. */
    fun update(pixels: IntArray, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        val b = bitmap ?: return
        if (x + w > b.width || y + h > b.height || pixels.size < stride * b.height) return
        b.setPixels(pixels, y * stride + x, stride, x, y, w, h)
        postInvalidateOnAnimation()
    }

    private fun fitScale(): Float {
        val b = bitmap ?: return 1f
        return minOf(width / b.width.toFloat(), height / b.height.toFloat())
    }

    private fun scale() = fitScale() * zoom

    private fun clampOffsets() {
        val b = bitmap ?: return
        val s = scale()
        val contentW = b.width * s
        val contentH = b.height * s
        offsetX = if (contentW <= width) (width - contentW) / 2 else offsetX.coerceIn(width - contentW, 0f)
        offsetY = if (contentH <= height) (height - contentH) / 2 else offsetY.coerceIn(height - contentH, 0f)
    }

    fun resetZoom() {
        zoom = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bitmap ?: return
        clampOffsets()
        val s = scale()
        matrix.setScale(s, s)
        matrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(b, matrix, paint)
    }

    private fun remoteX(x: Float) = ((x - offsetX) / scale()).toInt()
    private fun remoteY(y: Float) = ((y - offsetY) / scale()).toInt()

    private fun click(e: MotionEvent, button: Int) {
        val x = remoteX(e.x)
        val y = remoteY(e.y)
        listener?.onPointer(x, y, 0)
        listener?.onPointer(x, y, button)
        listener?.onPointer(x, y, 0)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            click(e, BUTTON_LEFT)
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) click(e, BUTTON_LEFT)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (multiTouch) return
            dragging = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onPointer(remoteX(e.x), remoteY(e.y), BUTTON_LEFT)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dragging || multiTouch) return false
            offsetX -= dx
            offsetY -= dy
            invalidate()
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale()
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            val s = scale()
            // keep the point under the fingers in place
            offsetX = detector.focusX - (detector.focusX - offsetX) * s / old
            offsetY = detector.focusY - (detector.focusY - offsetY) * s / old
            if (abs(detector.scaleFactor - 1f) > 0.01f) twoFingerMoved = true
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                multiTouch = true
                twoFingerStart = event.eventTime
                twoFingerMoved = false
                lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2
                wheelRemainder = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    listener?.onPointer(remoteX(event.x), remoteY(event.y), BUTTON_LEFT)
                } else if (multiTouch && event.pointerCount >= 2 && !scaler.isInProgress) {
                    val y = (event.getY(0) + event.getY(1)) / 2
                    wheelRemainder += y - lastTwoFingerY
                    lastTwoFingerY = y
                    val step = 40 * resources.displayMetrics.density
                    while (abs(wheelRemainder) >= step) {
                        twoFingerMoved = true
                        val button = if (wheelRemainder > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
                        val cx = remoteX((event.getX(0) + event.getX(1)) / 2)
                        val cy = remoteY(y)
                        listener?.onPointer(cx, cy, button)
                        listener?.onPointer(cx, cy, 0)
                        wheelRemainder -= if (wheelRemainder > 0) step else -step
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount == 2 && multiTouch) {
                val quick = event.eventTime - twoFingerStart < TWO_FINGER_TAP_MS
                if (quick && !twoFingerMoved) {
                    val i = if (event.actionIndex == 0) 1 else 0
                    val x = remoteX(event.getX(i))
                    val y = remoteY(event.getY(i))
                    listener?.onPointer(x, y, BUTTON_RIGHT)
                    listener?.onPointer(x, y, 0)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) listener?.onPointer(remoteX(event.x), remoteY(event.y), 0)
                dragging = false
                if (multiTouch) {
                    multiTouch = false
                    return true
                }
            }
        }
        if (!multiTouch) gestures.onTouchEvent(event)
        return true
    }

    /** Mouse or touchpad (DeX, OTG): hover moves the pointer, buttons click. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val x = remoteX(event.x)
        val y = remoteY(event.y)
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                listener?.onPointer(x, y, 0)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    listener?.onPointer(x, y, if (v > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN)
                    listener?.onPointer(x, y, 0)
                }
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    // --- keyboard ---

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.codePoints().forEach { listener?.onTyped(Keysyms.forChar(it)) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(maxOf(1, beforeLength)) { listener?.onTyped(Keysyms.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val special = Keysyms.forKeyCode(keyCode)
        if (special != 0) {
            // hardware modifiers go through as they are; other keys as one press
            if (special in MODIFIERS) listener?.onKeysym(special, true) else listener?.onTyped(special)
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (unicode != 0) {
            listener?.onTyped(Keysyms.forChar(unicode))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val special = Keysyms.forKeyCode(keyCode)
        if (special in MODIFIERS) {
            listener?.onKeysym(special, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16
        private const val TWO_FINGER_TAP_MS = 300L
        private val MODIFIERS = setOf(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.SHIFT_L, Keysyms.SUPER_L)
    }
}
