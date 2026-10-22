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
    val click: ClickSpec?,
    val master: Float = 1f
)

object StemMixer {

    /** Cuántas pistas se transponen a la vez. Más = más rápido pero más memoria. */
    private const val PARALLEL = 2
    private const val SR = StemSeparator.SAMPLE_RATE

    /**
     * Transpone y/o cambia la velocidad de cada pista por separado con Rubber Band (en paralelo)
     * y deja los resultados en [workDir]. Devuelve los 4 archivos que se deben mezclar.
     * La batería original se conserva tal cual si no se transpone y la velocidad no cambia.
     */
    fun prepare(
        stems: SeparatedStems,
        workDir: File,
        semitones: Float,
        timeRatio: Double,
        moveFormants: Boolean,
        transposeDrums: Boolean,
        cancel: AtomicBoolean,
        report: (Float) -> Unit
    ): List<File> {
        workDir.listFiles { f -> f.name.startsWith("proc_") }?.forEach { it.delete() }
        val sources = stems.files

        // Semitonos de cada pista: la batería solo se transpone si se pidió
        val sem = FloatArray(4) { i -> if (i == 0 && !transposeDrums) 0f else semitones }
        val toProcess = (0 until 4).filter { sem[it] != 0f || timeRatio != 1.0 }
        if (toProcess.isEmpty()) return sources

        val outFiles = List(4) { File(workDir, "proc_${StemSeparator.STEM_NAMES[it]}.pcm") }
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
                        sem[i],
                        !moveFormants,
                        timeRatio,
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
        return List(4) { i -> if (i in toProcess) outFiles[i] else sources[i] }
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

        Finisher.applyGain(out, s.master)
        s.click?.let { if (it.gain > 0f) Finisher.addClicks(out, SR, 2, startFrame, frames, it) }
        return out
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
