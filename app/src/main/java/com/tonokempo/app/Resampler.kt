package com.tonokempo.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Remuestreador sinc con ventana Hann (calidad suficiente para alimentar a Demucs). */
object Resampler {
    private const val HALF = 16      // taps a cada lado
    private const val RES = 256      // subdivisiones por muestra en la tabla

    private val table: FloatArray by lazy {
        FloatArray(2 * HALF * RES + 2).also { t ->
            for (i in t.indices) {
                val v = i.toDouble() / RES - HALF
                val sinc = if (abs(v) < 1e-9) 1.0 else sin(PI * v) / (PI * v)
                val win = 0.5 * (1.0 + cos(PI * v / HALF))
                t[i] = (sinc * win).toFloat()
            }
        }
    }

    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input

        val step = srcRate.toDouble() / dstRate
        val cutoff = min(1.0, dstRate.toDouble() / srcRate)
        val support = HALF / cutoff
        val outLen = (input.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(outLen)
        val t = table
        val gain = cutoff.toFloat()

        for (n in 0 until outLen) {
            val pos = n * step
            val lo = max(0, floor(pos - support).toInt() + 1)
            val hi = min(input.size - 1, floor(pos + support).toInt())
            var acc = 0f
            for (k in lo..hi) {
                val v = (pos - k) * cutoff
                val f = (v + HALF) * RES
                val i0 = f.toInt()
                val frac = (f - i0).toFloat()
                val w = t[i0] + (t[i0 + 1] - t[i0]) * frac
                acc += input[k] * w
            }
            out[n] = acc * gain
        }
        return out
    }
}
