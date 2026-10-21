package com.tonokempo.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.abs
import kotlin.math.roundToInt

/** Transpone cada pista por separado con Rubber Band (en paralelo) y las vuelve a sumar. */
object StemMixer {

    /** Cuántas pistas se procesan a la vez. Más = más rápido pero más memoria. */
    private const val PARALLEL = 2

    fun render(
        stems: SeparatedStems,
        workDir: File,
        semitones: Float,
        moveFormants: Boolean,
        transposeDrums: Boolean,
        cancel: AtomicBoolean,
        report: (Float) -> Unit
    ): ByteArray {
        val sources = stems.files
        val processed = ArrayList<File>()

        try {
            val finalFiles: List<File> = if (semitones == 0f) {
                sources
            } else {
                val outFiles = List(4) { File(workDir, "proc_${StemSeparator.STEM_NAMES[it]}.pcm") }
                processed.addAll(outFiles)
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
                                StemSeparator.SAMPLE_RATE,
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
                List(4) { i -> if (i == 0 && !transposeDrums) sources[0] else outFiles[i] }
            }
            return mix(finalFiles)
        } finally {
            processed.forEach { it.delete() }
        }
    }

    /** Suma las 4 pistas; si el pico supera el máximo, baja el volumen general lo justo. */
    private fun mix(files: List<File>): ByteArray {
        val frames = (files.minOf { it.length() } / 4).toInt()

        var peak = 0
        forEachChunk(files, frames) { sums, count ->
            for (k in 0 until count) {
                val a = abs(sums[k])
                if (a > peak) peak = a
            }
        }
        val scale = if (peak > 32767) 32767f / peak else 1f

        val out = ByteArray(frames * 4)
        var p = 0
        forEachChunk(files, frames) { sums, count ->
            for (k in 0 until count) {
                val v = (sums[k] * scale).roundToInt().coerceIn(-32768, 32767)
                out[p++] = v.toByte()
                out[p++] = (v shr 8).toByte()
            }
        }
        return out
    }

    private fun forEachChunk(files: List<File>, frames: Int, block: (IntArray, Int) -> Unit) {
        val streams = files.map { BufferedInputStream(FileInputStream(it), 1 shl 16) }
        try {
            val chunkFrames = 32768
            val bytes = ByteArray(chunkFrames * 4)
            val sums = IntArray(chunkFrames * 2)
            var done = 0
            while (done < frames) {
                val n = minOf(chunkFrames, frames - done)
                java.util.Arrays.fill(sums, 0, n * 2, 0)
                for (st in streams) {
                    readFully(st, bytes, n * 4)
                    for (k in 0 until n * 2) {
                        val lo = bytes[2 * k].toInt() and 0xFF
                        val hi = bytes[2 * k + 1].toInt()
                        sums[k] += (hi shl 8) or lo
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
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }
}
