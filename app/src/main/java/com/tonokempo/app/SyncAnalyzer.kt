package com.tonokempo.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Resultado de comparar los clics con los ataques de la canción.
 * Los desfases son (momento del ataque) − (momento del clic): positivo = el clic suena antes que el ataque.
 */
class SyncReport(
    val clicks: Int,
    val matched: Int,
    val medianMs: Double,
    val within10: Int,
    val within5: Int,
    val slopeMsPerBeat: Double,
    val interceptMs: Double,
    val usable: Boolean
)

object SyncAnalyzer {
    private const val RADIUS_MS = 60

    /** Umbral de "ataque claro": una fracción del ataque fuerte típico de la canción. */
    private fun threshold(onset: FloatArray): Float {
        val vals = onset.filter { it > 0f }.sorted()
        if (vals.isEmpty()) return Float.MAX_VALUE
        val p98 = vals[(vals.size * 0.98).toInt().coerceAtMost(vals.size - 1)]
        return p98 * 0.2f
    }

    /** Mueve un instante (s) al ataque más fuerte en ±[radiusMs] ms; si no hay ataque claro, lo deja igual. */
    fun snapOnset(onset: FloatArray, tSec: Double, radiusMs: Int): Double {
        val c = Math.round(tSec * 1000.0).toInt()
        var bestJ = -1
        var bestV = threshold(onset)
        for (j in max(0, c - radiusMs)..min(onset.size - 1, c + radiusMs)) {
            if (onset[j] > bestV) {
                bestV = onset[j]
                bestJ = j
            }
        }
        return if (bestJ >= 0) bestJ / 1000.0 else tSec
    }

    fun analyze(onset: FloatArray, times: DoubleArray): SyncReport {
        val thr = threshold(onset)
        val offsets = ArrayList<Double>()
        val index = ArrayList<Int>()
        for (k in times.indices) {
            val c = Math.round(times[k] * 1000.0).toInt()
            var bestJ = -1
            var bestV = thr
            for (j in max(0, c - RADIUS_MS)..min(onset.size - 1, c + RADIUS_MS)) {
                if (onset[j] > bestV) {
                    bestV = onset[j]
                    bestJ = j
                }
            }
            if (bestJ >= 0) {
                offsets.add((bestJ - c).toDouble())
                index.add(k)
            }
        }
        if (offsets.size < 8) {
            return SyncReport(times.size, offsets.size, 0.0, 0, 0, 0.0, 0.0, false)
        }

        val sorted = offsets.sorted()
        val median = sorted[sorted.size / 2]
        val devs = offsets.map { abs(it - median) }.sorted()
        val mad = devs[devs.size / 2]
        val limit = max(3.0 * mad, 8.0)

        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        var w10 = 0
        var w5 = 0
        for (i in offsets.indices) {
            val o = offsets[i]
            if (abs(o) <= 10.0) w10++
            if (abs(o) <= 5.0) w5++
            if (abs(o - median) <= limit) {
                val x = index[i].toDouble()
                n++
                sx += x
                sy += o
                sxx += x * x
                sxy += x * o
            }
        }
        val denom = n * sxx - sx * sx
        val slope = if (n >= 4 && abs(denom) > 1e-9) (n * sxy - sx * sy) / denom else 0.0
        val intercept = if (n >= 1) (sy - slope * sx) / n else median
        val usable = offsets.size >= 8 && offsets.size >= times.size * 0.4
        return SyncReport(times.size, offsets.size, median, w10, w5, slope, intercept, usable)
    }
}
