package com.tonokempo.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Envolvente de amplitud con una muestra por milisegundo, para dibujar la onda y buscar ataques. */
object Envelope {

    /** Pico absoluto de cada milisegundo (0..1, normalizado al máximo), tomando el mayor de los canales. */
    fun build(pcm: ByteArray, sampleRate: Int, channels: Int): FloatArray {
        val frames = pcm.size / (2 * channels)
        val ms = (frames.toLong() * 1000L / sampleRate).toInt() + 1
        val out = FloatArray(ms)
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until frames) {
            val m = (i.toLong() * 1000L / sampleRate).toInt()
            var v = 0
            for (c in 0 until channels) {
                val s = abs(shorts.get(i * channels + c).toInt())
                if (s > v) v = s
            }
            val f = v / 32768f
            if (f > out[m]) out[m] = f
        }
        var mx = 0f
        for (v in out) if (v > mx) mx = v
        if (mx > 0.02f) for (i in out.indices) out[i] = min(1f, out[i] / mx)
        return out
    }

    fun fromFile(file: File, sampleRate: Int, channels: Int): FloatArray =
        build(file.readBytes(), sampleRate, channels)

    /** Cuánto sube la envolvente en cada milisegundo (suavizado de 3 ms): los máximos son ataques. */
    fun onsetFunction(env: FloatArray): FloatArray {
        val n = env.size
        val d = FloatArray(n)
        for (i in 1 until n) d[i] = max(0f, env[i] - env[i - 1])
        val s = FloatArray(n)
        for (i in 1 until n - 1) s[i] = (d[i - 1] + d[i] + d[i + 1]) / 3f
        return s
    }
}
