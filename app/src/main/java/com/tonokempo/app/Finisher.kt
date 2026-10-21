package com.tonokempo.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/** Recorta un fragmento y superpone el metrónomo sobre audio PCM 16 bits entrelazado. */
object Finisher {

    /** [startMs]/[endMs] en el tiempo del audio recibido; endMs < 0 = hasta el final. */
    fun cropAndClick(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        startMs: Long,
        endMs: Long,
        click: ClickSpec?
    ): ByteArray {
        val bpf = 2 * channels
        val total = (pcm.size / bpf).toLong()
        val startFrame = (startMs * sampleRate / 1000L).coerceIn(0L, total)
        val endFrame = if (endMs < 0) total else (endMs * sampleRate / 1000L).coerceIn(startFrame, total)
        val frames = (endFrame - startFrame).toInt()
        if (frames <= 0) throw IllegalArgumentException("El fragmento elegido está vacío")

        val out = pcm.copyOfRange((startFrame * bpf).toInt(), (endFrame * bpf).toInt())
        click?.let { if (it.gain > 0f) addClicks(out, sampleRate, channels, startFrame, frames, it) }
        return out
    }

    fun addClicks(out: ByteArray, sampleRate: Int, channels: Int, startFrame: Long, frames: Int, c: ClickSpec) {
        val len = (0.04 * sampleRate).toInt()
        val normal = FloatArray(len) { n ->
            val t = n / sampleRate.toFloat()
            (sin(2.0 * PI * 1000.0 * t) * exp(-t / 0.008)).toFloat()
        }
        val accent = FloatArray(len) { n ->
            val t = n / sampleRate.toFloat()
            (sin(2.0 * PI * 1500.0 * t) * exp(-t / 0.008)).toFloat()
        }
        val bpf = 2 * channels

        for (i in c.times.indices) {
            val pos = (c.times[i] * sampleRate).roundToLong() - startFrame
            if (pos >= frames || pos + len <= 0) continue
            val isAccent = c.accent[i]
            val table = if (isAccent) accent else normal
            val amp = (if (isAccent) 0.9f else 0.6f) * c.gain * 32767f
            for (n in 0 until len) {
                val idx = pos + n
                if (idx < 0 || idx >= frames) continue
                val add = (table[n] * amp).toInt()
                val base = idx.toInt() * bpf
                for (ch in 0 until channels) {
                    val o = base + ch * 2
                    val cur = (out[o].toInt() and 0xFF) or (out[o + 1].toInt() shl 8)
                    val v = (cur + add).coerceIn(-32768, 32767)
                    out[o] = v.toByte()
                    out[o + 1] = (v shr 8).toByte()
                }
            }
        }
    }
}
