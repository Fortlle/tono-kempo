package com.tonokempo.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Golpes (frames de 20 ms) y su número dentro del compás (1 = primer tiempo). */
class DbnResult(val frames: IntArray, val numbers: IntArray, val meter: Int)

/**
 * Seguimiento de golpes y compases con un modelo oculto de Markov (Viterbi), como el DBN de madmom
 * que usa también el puerto en C++ de Beat This! con la opción --dbn.
 *
 * Estados: (compás, tempo, posición dentro del compás). El tempo solo puede cambiar al empezar
 * un compás y se penaliza con exp(-100 * |cambio relativo|), así que los golpes sueltos que
 * rompen el pulso se descartan y los primeros tiempos quedan en posiciones coherentes.
 */
object DbnTracker {
    private const val FPS = 50.0
    private const val MIN_BPM = 55.0
    private const val MAX_BPM = 215.0
    private const val TRANSITION_LAMBDA = 100.0
    private const val OBS_LAMBDA = 16.0
    private const val THRESHOLD = 0.05f
    private const val EPS = 1e-6

    /**
     * [beatProb] y [downProb]: probabilidades por frame (0..1) de golpe y de primer tiempo.
     * [meters]: tiempos por compás permitidos (por ejemplo [3, 4]). Devuelve null si no hay señal.
     */
    fun decode(beatProb: FloatArray, downProb: FloatArray, meters: IntArray): DbnResult? {
        val n = beatProb.size
        if (n < 50 || meters.isEmpty()) return null

        // Columna 0: golpes que no son primer tiempo. Columna 1: primeros tiempos.
        val a0 = FloatArray(n) { max(beatProb[it] - downProb[it], 0f) }
        val a1 = downProb

        // Recortar silencios al principio y al final
        var first = -1
        var last = -1
        for (t in 0 until n) {
            if (max(a0[t], a1[t]) >= THRESHOLD) {
                if (first < 0) first = t
                last = t
            }
        }
        if (first < 0) return null
        val len = last - first + 1
        if (len < 100) return null

        val minI = (60.0 * FPS / MAX_BPM).roundToInt()
        val maxI = (60.0 * FPS / MIN_BPM).roundToInt()
        val tempi = IntArray(maxI - minI + 1) { minI + it }
        val k = tempi.size

        // Log-verosimilitud de cada frame para: sin golpe, golpe, primer tiempo
        val oNon = DoubleArray(len)
        val oBeat = DoubleArray(len)
        val oDown = DoubleArray(len)
        for (i in 0 until len) {
            val b = a0[first + i].toDouble()
            val d = a1[first + i].toDouble()
            oNon[i] = ln(max(1.0 - b - d, EPS) / (OBS_LAMBDA - 1.0))
            oBeat[i] = ln(max(b, EPS))
            oDown[i] = ln(max(d, EPS))
        }

        // Transición de tempo al empezar un compás
        val logP = Array(k) { DoubleArray(k) }
        for (i in 0 until k) {
            val row = DoubleArray(k)
            var sum = 0.0
            for (j in 0 until k) {
                val p = exp(-TRANSITION_LAMBDA * abs(tempi[j].toDouble() / tempi[i] - 1.0))
                row[j] = if (p <= 2.220446049250313e-16) 0.0 else p
                sum += row[j]
            }
            for (j in 0 until k) {
                logP[i][j] = if (row[j] > 0.0) ln(row[j] / sum) else Double.NEGATIVE_INFINITY
            }
        }

        val nm = meters.size
        val delta = Array(nm) { mi -> Array(k) { ti -> DoubleArray(meters[mi] * tempi[ti]) } }
        val cls = Array(nm) { mi ->
            Array(k) { ti ->
                val tau = tempi[ti]
                val win = tau / OBS_LAMBDA
                ByteArray(meters[mi] * tau) { q ->
                    when {
                        q < win -> 2.toByte()
                        (q % tau) < win -> 1.toByte()
                        else -> 0.toByte()
                    }
                }
            }
        }
        val bp = ByteArray(len * nm * k)
        val obs = DoubleArray(3)
        val endVal = DoubleArray(k)

        for (i in 0 until len) {
            obs[0] = oNon[i]
            obs[1] = oBeat[i]
            obs[2] = oDown[i]
            for (mi in 0 until nm) {
                val m = meters[mi]
                if (i == 0) {
                    for (ti in 0 until k) {
                        val d = delta[mi][ti]
                        val c = cls[mi][ti]
                        for (q in d.indices) d[q] += obs[c[q].toInt()]
                    }
                } else {
                    for (ti in 0 until k) endVal[ti] = delta[mi][ti][m * tempi[ti] - 1]
                    for (tj in 0 until k) {
                        var best = Double.NEGATIVE_INFINITY
                        var arg = 0
                        for (ti in 0 until k) {
                            val v = endVal[ti] + logP[ti][tj]
                            if (v > best) {
                                best = v
                                arg = ti
                            }
                        }
                        bp[(i * nm + mi) * k + tj] = arg.toByte()
                        val d = delta[mi][tj]
                        val c = cls[mi][tj]
                        for (q in d.size - 1 downTo 1) d[q] = d[q - 1] + obs[c[q].toInt()]
                        d[0] = best + obs[c[0].toInt()]
                    }
                }
            }
        }

        // Mejor estado final
        var bestV = Double.NEGATIVE_INFINITY
        var bMi = 0
        var bTi = 0
        var bQ = 0
        for (mi in 0 until nm) {
            for (ti in 0 until k) {
                val d = delta[mi][ti]
                for (q in d.indices) {
                    if (d[q] > bestV) {
                        bestV = d[q]
                        bMi = mi
                        bTi = ti
                        bQ = q
                    }
                }
            }
        }
        if (bestV == Double.NEGATIVE_INFINITY) return null

        // Camino hacia atrás
        val frames = ArrayList<Int>()
        val numbers = ArrayList<Int>()
        val windows = ArrayList<Int>()
        var ti = bTi
        var q = bQ
        var i = len - 1
        while (i >= 0) {
            val tau = tempi[ti]
            if (q % tau == 0) {
                frames.add(first + i)
                numbers.add(q / tau + 1)
                windows.add(ceil(tau / OBS_LAMBDA).toInt())
            }
            if (i == 0) break
            if (q >= 1) {
                q -= 1
            } else {
                val prev = bp[(i * nm + bMi) * k + ti].toInt()
                ti = prev
                q = meters[bMi] * tempi[prev] - 1
            }
            i--
        }
        frames.reverse()
        numbers.reverse()
        windows.reverse()
        if (frames.size < 2) return null

        // El golpe real es el máximo de actividad dentro de la ventana del estado de golpe
        val snapped = IntArray(frames.size)
        var prevFrame = -1
        for (idx in frames.indices) {
            val f = frames[idx]
            val hi = min(n - 1, f + windows[idx])
            var bestF = f
            var bestP = -1f
            for (g in f..hi) {
                if (beatProb[g] > bestP) {
                    bestP = beatProb[g]
                    bestF = g
                }
            }
            if (bestF <= prevFrame) bestF = prevFrame + 1
            snapped[idx] = bestF
            prevFrame = bestF
        }

        return DbnResult(snapped, numbers.toIntArray(), meters[bMi])
    }
}
