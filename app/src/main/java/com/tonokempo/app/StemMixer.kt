package com.tonokempo.app

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** Metrónomo a superponer: tiempos en segundos de la canción original, cuáles se acentúan y volumen 0..1. */
class ClickSpec(val times: DoubleArray, val accent: BooleanArray, val gain: Float)

/**
 * Ajustes de la mezcla final.
 * [gains]: volumen de cada pista en el orden de StemSeparator.STEM_NAMES (batería, bajo, otros, voz); 1.0 = original.
 * [startMs]/[endMs]: fragmento a exportar; endMs < 0 significa hasta el final.
 */
class MixSettings(
    val gains: FloatArray,
    val startMs: Long,
    val endMs: Long,
    val click: ClickSpec?
)

object StemMixer {

    /** Cuántas pistas se transponen a la vez. Más = más rápido pero más memoria. */
    private const val PARALLEL = 2
    private const val SR = StemSeparator.SAMPLE_RATE

    /**
     * Transpone cada pista por separado con Rubber Band (en paralelo) y deja los resultados en [workDir].
     * Devuelve los 4 archivos que se deben mezclar (la batería original si no se transpone).
     */
    fun prepare(
        stems: SeparatedStems,
        workDir: File,
        semitones: Float,
        moveFormants: Boolean,
        transposeDrums: Boolean,
        cancel: AtomicBoolean,
        report: (Float) -> Unit
    ): List<File> {
        workDir.listFiles { f -> f.name.startsWith("proc_") }?.forEach { it.delete() }
        val sources = stems.files
        if (semitones == 0f) return sources

        val outFiles = List(4) { File(workDir, "proc_${StemSeparator.STEM_NAMES[it]}.pcm") }
        val toProcess = (0 until 4).filter { it != 0 || transposeDrums }
        val progress = AtomicIntegerArray(4)
        val pool = Executors.newFixedThreadPool(PARALLEL)
        try {
            val futures = toProcess.map { i ->
                pool.submit(Callable<Unit> {
                    if (cancel.get()) throw CancelledException()
                    val pcm = sources[i].readBytes()
                    val out = RubberBandEngine.process(
                        pcm,
                        SR,
                        2,
                        semitones,
                        !moveFormants,
                        object : ProgressListener {
                            override fun onProgress(fraction: Float) {
                                progress.set(i, (fraction * 100).toInt())
                                var total = 0
                                for (k in 0 until 4) total += progress.get(k)
                                report(total / (toProcess.size * 100f))
                            }
                        }
                    )
                    outFiles[i].writeBytes(out)
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return List(4) { i -> if (i == 0 && !transposeDrums) sources[0] else outFiles[i] }
    }

    /** Suma las 4 pistas con su volumen, recorta al fragmento y agrega el metrónomo si se pidió. */
    fun mix(files: List<File>, s: MixSettings): ByteArray {
        val totalFrames = files.minOf { it.length() } / 4
        val startFrame = (s.startMs * SR / 1000L).coerceIn(0L, totalFrames)
        val endFrame = if (s.endMs < 0) totalFrames
        else (s.endMs * SR / 1000L).coerceIn(startFrame, totalFrames)
        val frames = (endFrame - startFrame).toInt()
        if (frames <= 0) throw IllegalArgumentException("El fragmento elegido está vacío")

        // Pasada 1: pico máximo con los volúmenes elegidos
        var peak = 0f
        forEachChunk(files, startFrame, frames, s.gains) { sums, count ->
            for (k in 0 until count) {
                val a = abs(sums[k])
                if (a > peak) peak = a
            }
        }
        val scale = if (peak > 32767f) 32767f / peak else 1f

        // Pasada 2: mezcla final
        val out = ByteArray(frames * 4)
        var p = 0
        forEachChunk(files, startFrame, frames, s.gains) { sums, count ->
            for (k in 0 until count) {
                val v = (sums[k] * scale).roundToInt().coerceIn(-32768, 32767)
                out[p++] = v.toByte()
                out[p++] = (v shr 8).toByte()
            }
        }

        s.click?.let { if (it.gain > 0f) addClicks(out, startFrame, frames, it) }
        return out
    }

    private fun addClicks(out: ByteArray, startFrame: Long, frames: Int, c: ClickSpec) {
        val len = (0.04 * SR).toInt()
        val normal = FloatArray(len) { n ->
            val t = n / SR.toFloat()
            (sin(2.0 * PI * 1000.0 * t) * exp(-t / 0.008)).toFloat()
        }
        val accent = FloatArray(len) { n ->
            val t = n / SR.toFloat()
            (sin(2.0 * PI * 1500.0 * t) * exp(-t / 0.008)).toFloat()
        }

        for (i in c.times.indices) {
            val pos = (c.times[i] * SR).roundToLong() - startFrame
            if (pos >= frames || pos + len <= 0) continue
            val isAccent = c.accent[i]
            val table = if (isAccent) accent else normal
            val amp = (if (isAccent) 0.9f else 0.6f) * c.gain * 32767f
            for (n in 0 until len) {
                val idx = pos + n
                if (idx < 0 || idx >= frames) continue
                val add = (table[n] * amp).toInt()
                val base = idx.toInt() * 4
                for (ch in 0..1) {
                    val o = base + ch * 2
                    val cur = (out[o].toInt() and 0xFF) or (out[o + 1].toInt() shl 8)
                    val v = (cur + add).coerceIn(-32768, 32767)
                    out[o] = v.toByte()
                    out[o + 1] = (v shr 8).toByte()
                }
            }
        }
    }

    private fun forEachChunk(
        files: List<File>,
        startFrame: Long,
        frames: Int,
        gains: FloatArray,
        block: (FloatArray, Int) -> Unit
    ) {
        val streams = files.map {
            val fis = FileInputStream(it)
            fis.channel.position(startFrame * 4L)
            BufferedInputStream(fis, 1 shl 16)
        }
        try {
            val chunkFrames = 32768
            val bytes = ByteArray(chunkFrames * 4)
            val sums = FloatArray(chunkFrames * 2)
            var done = 0
            while (done < frames) {
                val n = minOf(chunkFrames, frames - done)
                java.util.Arrays.fill(sums, 0, n * 2, 0f)
                for ((si, st) in streams.withIndex()) {
                    val g = gains[si]
                    readFully(st, bytes, n * 4)
                    for (k in 0 until n * 2) {
                        val lo = bytes[2 * k].toInt() and 0xFF
                        val hi = bytes[2 * k + 1].toInt()
                        sums[k] += g * ((hi shl 8) or lo)
                    }
                }
                block(sums, n * 2)
                done += n
            }
        } finally {
            streams.forEach { it.close() }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, count: Int) {
        var off = 0
        while (off < count) {
            val r = input.read(buf, off, count - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }
}
