package io.github.jackwener.dsh.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Draws one animation of a [PetPack], advancing by each frame's own milliseconds.
 * A tap plays `poke` once, a double tap `happy`, a drag is reported to [onDrag], a long press to [onLongPress].
 */
@SuppressLint("ViewConstructor")
class PetView(
    context: Context,
    private val pack: PetPack,
    bodyHeightPx: Float,
    private val onDrag: (Float, Float) -> Unit,
    private val onLongPress: () -> Unit,
) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var current: PetAnimation = pack.animation("idle") ?: error("pack has no idle animation")
    private var frameIndex = 0

    /** Pixels per art pixel, so the pack's content box is [bodyHeightPx] tall. */
    private val k: Float = bodyHeightPx / pack.content.height().toFloat()

    init {
        isClickable = true
        schedule()
    }

    /** Switch to a state named in the pack's `states` map (unknown names are ignored). */
    fun setState(state: String) {
        val next = pack.animation(state) ?: return
        if (next === current) return
        current = next
        frameIndex = 0
        requestLayout()
        invalidate()
        schedule()
    }

    private fun react(name: String) {
        val anim = pack.animations[name] ?: return
        current = anim
        frameIndex = 0
        requestLayout()
        invalidate()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacksAndMessages(null)
        val frames = current.frames
        if (frames.isEmpty()) return
        val ms = frames[frameIndex.coerceIn(0, frames.size - 1)].ms.toLong()
        handler.postDelayed({
            if (!isAttachedToWindow) return@postDelayed
            val next = frameIndex + 1
            if (next >= frames.size) {
                if (current.loop) {
                    frameIndex = 0
                } else {
                    current = pack.animation("idle") ?: current
                    frameIndex = 0
                    requestLayout()
                }
            } else {
                frameIndex = next
            }
            invalidate()
            schedule()
        }, ms.coerceAtLeast(16))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (current.box.width() * k).toInt().coerceAtLeast(1)
        val h = (current.box.height() * k).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val anim = current
        if (anim.frames.isEmpty()) return
        val idx = anim.frames[frameIndex.coerceIn(0, anim.frames.size - 1)].index
        val dst = RectF(0f, 0f, anim.box.width() * k, anim.box.height() * k)
        canvas.drawBitmap(anim.sheet, anim.srcRect(idx), dst, paint)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var lastTapAt = 0L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPress = Runnable { onLongPress() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                dragging = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    onDrag(event.rawX - lastX, event.rawY - lastY)
                }
                lastX = event.rawX; lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 300) react("happy") else react("poke")
                    lastTapAt = now
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
