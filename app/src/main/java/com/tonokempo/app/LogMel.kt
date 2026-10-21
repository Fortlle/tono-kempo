package com.tonokempo.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Espectrograma log-mel igual al de Beat This!: audio mono a 22050 Hz, FFT 1024 (ventana Hann),
 * salto 441, 128 bandas mel (escala Slaney, 30 Hz a 11 kHz, triángulos sin normalizar),
 * magnitud dividida entre sqrt(1024) y compresión log1p(1000 * x).
 */
object LogMel {
    const val SAMPLE_RATE = 22050
    private const val N_FFT = 1024
    private const val HOP = 441
    const val N_MELS = 128
    private const val N_BINS = N_FFT / 2 + 1
    private const val F_MIN = 30.0
    private const val F_MAX = 11000.0

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val cosT = FloatArray(N_FFT / 2) { cos(2.0 * PI * it / N_FFT).toFloat() }
    private val sinT = FloatArray(N_FFT / 2) { (-sin(2.0 * PI * it / N_FFT)).toFloat() }
    private val bitRev = IntArray(N_FFT) { Integer.reverse(it) ushr (32 - 10) }

    // Filtros mel dispersos: para cada banda, primer bin y pesos
    private val fbStart = IntArray(N_MELS)
    private val fbWeights: Array<FloatArray> = buildFilterbank()

    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3.0
        val logStep = ln(6.4) / 27.0
        return if (f >= 1000.0) 15.0 + ln(f / 1000.0) / logStep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3.0
        val logStep = ln(6.4) / 27.0
        return if (m >= 15.0) 1000.0 * exp(logStep * (m - 15.0)) else m * fSp
    }

    private fun buildFilterbank(): Array<FloatArray> {
        val mMin = hzToMel(F_MIN)
        val mMax = hzToMel(F_MAX)
        val pts = DoubleArray(N_MELS + 2) { melToHz(mMin + (mMax - mMin) * it / (N_MELS + 1)) }
        val freqs = DoubleArray(N_BINS) { it * (SAMPLE_RATE / 2.0) / (N_BINS - 1) }

        return Array(N_MELS) { m ->
            val lo = pts[m]
            val c = pts[m + 1]
            val hi = pts[m + 2]
            var first = -1
            var lastBin = -1
            val tmp = FloatArray(N_BINS)
            for (i in 0 until N_BINS) {
                val down = (freqs[i] - lo) / (c - lo)
                val up = (hi - freqs[i]) / (hi - c)
                val w = maxOf(0.0, minOf(down, up))
                if (w > 0.0) {
                    tmp[i] = w.toFloat()
                    if (first < 0) first = i
                    lastBin = i
                }
            }
            if (first < 0) {
                fbStart[m] = 0
                FloatArray(0)
            } else {
                fbStart[m] = first
                tmp.copyOfRange(first, lastBin + 1)
            }
        }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        for (i in 0 until N_FFT) {
            val j = bitRev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= N_FFT) {
            val halfSize = size / 2
            val tableStep = N_FFT / size
            var i = 0
            while (i < N_FFT) {
                var k = 0
                for (j in i until i + halfSize) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val l = j + halfSize
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += tableStep
                }
                i += size
            }
            size *= 2
        }
    }

    /** Devuelve (espectrograma [frames x 128] fila por fila, número de frames). */
    fun compute(x: FloatArray): Pair<FloatArray, Int> {
        val n = x.size
        require(n > N_FFT) { "Audio demasiado corto para detectar tempo" }
        val frames = 1 + n / HOP
        val out = FloatArray(frames * N_MELS)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val mag = FloatArray(N_BINS)
        val half = N_FFT / 2

        for (f in 0 until frames) {
            val start = f * HOP - half
            for (i in 0 until N_FFT) {
                var idx = start + i
                if (idx < 0) idx = -idx
                if (idx >= n) idx = 2 * (n - 1) - idx
                if (idx < 0) idx = 0
                re[i] = x[idx] * window[i]
                im[i] = 0f
            }
            fft(re, im)
            for (b in 0 until N_BINS) mag[b] = sqrt(re[b] * re[b] + im[b] * im[b]) / 32f
            val base = f * N_MELS
            for (m in 0 until N_MELS) {
                val w = fbWeights[m]
                val s = fbStart[m]
                var acc = 0f
                for (j in w.indices) acc += w[j] * mag[s + j]
                out[base + m] = ln(1.0 + 1000.0 * acc).toFloat()
            }
        }
        return out to frames
    }
}
