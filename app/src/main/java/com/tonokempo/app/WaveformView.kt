package com.tonokempo.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Onda de la canción con los clics del metrónomo encima. Arrastrar mueve el tiempo, pellizcar hace zoom
 * y tocar pone un marcador. Los tiempos son los de la canción original, en segundos.
 */
class WaveformView(context: Context) : View(context) {

    var env: FloatArray? = null
        set(v) {
            field = v
            invalidate()
        }
    var durationSec = 0.0
    var windowSec = 6.0
    var viewStart = 0.0
    var marker = Double.NaN
    var playhead = Double.NaN
    var autoFollow = true
    var onMarkerChange: ((Double) -> Unit)? = null

    private var clicks = DoubleArray(0)
    private var accents = BooleanArray(0)
    private var segStarts = DoubleArray(0)
    private var segLabels = emptyArray<String>()

    private val density = resources.displayMetrics.density
    private val wavePaint = Paint().apply { strokeWidth = 1f }
    private val clickPaint = Paint().apply { strokeWidth = 1.5f * density }
    private val accentPaint = Paint().apply { strokeWidth = 3.5f * density }
    private val markerPaint = Paint().apply {
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val playPaint = Paint().apply { strokeWidth = 2f * density }
    private val segPaint = Paint().apply { strokeWidth = 2f * density }
    private val textPaint = Paint().apply {
        textSize = 11f * density
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply { strokeWidth = 1f }
    private val dash = Path()

    fun setColors(wave: Int, click: Int, accent: Int, markerC: Int, play: Int, text: Int, seg: Int, axis: Int) {
        wavePaint.color = wave
        clickPaint.color = click
        accentPaint.color = accent
        markerPaint.color = markerC
        playPaint.color = play
        textPaint.color = text
        segPaint.color = seg
        axisPaint.color = axis
        invalidate()
    }

    fun setGrid(times: DoubleArray?, accent: BooleanArray?) {
        clicks = times ?: DoubleArray(0)
        accents = accent ?: BooleanArray(0)
        invalidate()
    }

    fun setSegments(starts: DoubleArray, labels: Array<String>) {
        segStarts = starts
        segLabels = labels
        invalidate()
    }

    fun zoom(factor: Double) {
        val center = viewStart + windowSec / 2
        windowSec = (windowSec / factor).coerceIn(0.2, 60.0)
        viewStart = center - windowSec / 2
        clamp()
        invalidate()
    }

    fun showTime(t: Double) {
        viewStart = t - windowSec / 2
        clamp()
        invalidate()
    }

    /** Si el cursor de reproducción sale de la vista, la mueve para seguirlo. */
    fun followIfNeeded(t: Double) {
        if (!autoFollow) return
        if (t < viewStart || t > viewStart + windowSec * 0.9) {
            viewStart = t - windowSec * 0.2
            clamp()
        }
    }

    private fun clamp() {
        val maxStart = max(0.0, durationSec - windowSec)
        viewStart = viewStart.coerceIn(0.0, maxStart)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val dt = windowSec / w
        val t0 = viewStart
        val mid = h / 2f
        val amp = h / 2f - 16f * density

        canvas.drawLine(0f, mid, w.toFloat(), mid, axisPaint)

        val e = env
        if (e != null) {
            for (x in 0 until w) {
                val a = t0 + x * dt
                val b = a + dt
                val i0 = max(0, floor(a * 1000).toInt())
                var i1 = ceil(b * 1000).toInt()
                if (i1 <= i0) i1 = i0 + 1
                var m = 0f
                for (i in i0 until min(e.size, i1)) if (e[i] > m) m = e[i]
                if (m > 0f) {
                    val half = m * amp
                    canvas.drawLine(x.toFloat(), mid - half, x.toFloat(), mid + half, wavePaint)
                }
            }
        }

        // Marcas de tiempo
        val tick = niceTick(dt * 90)
        var t = ceil(t0 / tick) * tick
        while (t < t0 + windowSec) {
            val x = ((t - t0) / dt).toFloat()
            canvas.drawLine(x, h - 8f * density, x, h.toFloat(), axisPaint)
            canvas.drawText(label(t), x + 2f * density, h - 10f * density, textPaint)
            t += tick
        }

        // Clics del metrónomo
        for (i in clicks.indices) {
            val ct = clicks[i]
            if (ct < t0 || ct > t0 + windowSec) continue
            val x = ((ct - t0) / dt).toFloat()
            val isAcc = i < accents.size && accents[i]
            canvas.drawLine(x, 0f, x, h.toFloat(), if (isAcc) accentPaint else clickPaint)
        }

        // Cambios de compás
        for (i in segStarts.indices) {
            val st = segStarts[i]
            if (st < t0 || st > t0 + windowSec) continue
            val x = ((st - t0) / dt).toFloat()
            canvas.drawLine(x, 0f, x, h.toFloat(), segPaint)
            canvas.drawText(segLabels.getOrElse(i) { "" }, x + 3f * density, 12f * density, textPaint)
        }

        // Marcador (línea punteada)
        if (!marker.isNaN() && marker >= t0 && marker <= t0 + windowSec) {
            val x = ((marker - t0) / dt).toFloat()
            var y = 0f
            while (y < h) {
                canvas.drawLine(x, y, x, min(h.toFloat(), y + 8f * density), markerPaint)
                y += 14f * density
            }
        }

        // Cursor de reproducción
        if (!playhead.isNaN() && playhead >= t0 && playhead <= t0 + windowSec) {
            val x = ((playhead - t0) / dt).toFloat()
            canvas.drawLine(x, 0f, x, h.toFloat(), playPaint)
        }
    }

    private fun niceTick(minSec: Double): Double {
        for (c in doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)) if (c >= minSec) return c
        return 60.0
    }

    private fun label(t: Double): String {
        val m = (t / 60).toInt()
        val s = t - m * 60
        return String.format(Locale.US, "%d:%04.1f", m, s)
    }

    // ---- Gestos ----

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (width > 0) {
                viewStart += distanceX * (windowSec / width)
                clamp()
                autoFollow = false
                invalidate()
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (width > 0) {
                val t = viewStart + e.x * windowSec / width
                marker = t
                invalidate()
                onMarkerChange?.invoke(t)
            }
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (width <= 0) return true
            val focusT = viewStart + detector.focusX * windowSec / width
            windowSec = (windowSec / detector.scaleFactor).coerceIn(0.2, 60.0)
            viewStart = focusT - detector.focusX * windowSec / width
            clamp()
            autoFollow = false
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        scaler.onTouchEvent(ev)
        if (!scaler.isInProgress) gestures.onTouchEvent(ev)
        return true
    }
}
