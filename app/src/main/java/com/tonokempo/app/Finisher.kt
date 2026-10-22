package com.tonokempo.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tanh

/** Recorta un fragmento y superpone el metrónomo sobre audio PCM 16 bits entrelazado. */
object Finisher {

    /**
     * [startMs]/[endMs] en el tiempo del audio recibido; endMs < 0 = hasta el final.
     * [masterGain]: volumen general (1.0 = original). El metrónomo se agrega después y no depende de él.
     */
    fun cropAndClick(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        startMs: Long,
        endMs: Long,
        masterGain: Float,
        click: ClickSpec?
    ): ByteArray {
        val bpf = 2 * channels
        val total = (pcm.size / bpf).toLong()
        val startFrame = (startMs * sampleRate / 1000L).coerceIn(0L, total)
        val endFrame = if (endMs < 0) total else (endMs * sampleRate / 1000L).coerceIn(startFrame, total)
        val frames = (endFrame - startFrame).toInt()
        if (frames <= 0) throw IllegalArgumentException("El fragmento elegido está vacío")

        val out = pcm.copyOfRange((startFrame * bpf).toInt(), (endFrame * bpf).toInt())
        applyGain(out, masterGain)
        click?.let { if (it.gain > 0f) addClicks(out, sampleRate, channels, startFrame, frames, it) }
        return out
    }

    /**
     * Cambia el volumen de PCM 16 bits (en el mismo arreglo). Por debajo del 80 % del máximo es lineal;
     * por encima, un limitador suave evita la distorsión dura cuando se sube más de 100 %.
     */
    fun applyGain(pcm: ByteArray, gain: Float) {
        if (gain == 1f) return
        val knee = 0.8f
        val room = 1f - knee
        var i = 0
        while (i + 1 < pcm.size) {
            val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            var x = v / 32768f * gain
            val a = abs(x)
            if (a > knee) {
                val y = knee + room * tanh((a - knee) / room)
                x = if (x < 0) -y else y
            }
            val o = (x * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm[i] = o.toByte()
            pcm[i + 1] = (o shr 8).toByte()
            i += 2
        }
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
            val amp = (if (isAccent) 0.54f else 0.36f) * c.gain * 32767f
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
