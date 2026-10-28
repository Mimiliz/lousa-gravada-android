package br.com.mimiexplica.lousa

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kotlin.math.hypot

/** Janela da câmera sobre a lousa: arrastar com 1 dedo, aumentar ou diminuir com 2 dedos. */
class CameraBubble(context: Context) : FrameLayout(context) {
    val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val ring = GradientDrawable()
    var round = true
        set(v) { field = v; applyShape() }
    var mirror = false
        set(v) { field = v; preview.scaleX = if (v) -1f else 1f }

    /** Pedido de mudança: deslocamento em pixels de tela e fator de escala. */
    var onGesture: ((dx: Float, dy: Float, scale: Float) -> Unit)? = null
    var onGestureEnd: (() -> Unit)? = null

    init {
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        foreground = ring
        clipToOutline = true
        elevation = 8f
        applyShape()
    }

    private fun applyShape() {
        ring.shape = if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        ring.setStroke(5, 0xFFFFFFFF.toInt())
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (round) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.06f)
            }
        }
        if (!round) ring.cornerRadius = width * 0.06f
        invalidateOutline()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!round) ring.cornerRadius = w * 0.06f
        invalidateOutline()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var lastD = 0f

    private fun center(e: MotionEvent): FloatArray {
        var x = 0f; var y = 0f
        val n = e.pointerCount
        for (i in 0 until n) { x += e.getRawX(i); y += e.getRawY(i) }
        return floatArrayOf(x / n, y / n)
    }
    private fun dist(e: MotionEvent) =
        if (e.pointerCount > 1) hypot(e.getRawX(0) - e.getRawX(1), e.getRawY(0) - e.getRawY(1)) else 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val c = center(e); lastX = c[0]; lastY = c[1]; lastD = dist(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val c = center(e); val d = dist(e)
                val sc = if (lastD > 0 && d > 0) d / lastD else 1f
                onGesture?.invoke(c[0] - lastX, c[1] - lastY, sc)
                lastX = c[0]; lastY = c[1]; lastD = d
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onGestureEnd?.invoke()
        }
        return true
    }
}
