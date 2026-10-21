package com.tonokempo.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CancelledException : RuntimeException("Cancelado")

/** Cuatro pistas en PCM 16 bits estéreo a 44.1 kHz, una por archivo, en el orden de STEM_NAMES. */
class SeparatedStems(val files: List<File>)

/**
 * Separación de pistas con HT-Demucs (modelo ONNX, licencia MIT) usando ONNX Runtime.
 * El modelo recibe segmentos de 7.8 s (343980 muestras, estéreo, 44.1 kHz) y devuelve
 * 4 pistas: [drums, bass, other, vocals]. Aquí se hace el troceado con solapamiento.
 */
object StemSeparator {
    const val SAMPLE_RATE = 44100
    private const val SEGMENT = 343980
    private const val OVERLAP = 0.25
    private val STRIDE = (SEGMENT * (1.0 - OVERLAP)).toInt()
    val STEM_NAMES = listOf("drums", "bass", "other", "vocals")

    private const val MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs.onnx"
    private const val MIN_MODEL_BYTES = 200_000_000L

    fun modelFile(context: Context) = File(context.filesDir, "htdemucs.onnx")

    fun hasModel(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MIN_MODEL_BYTES
    }

    // ---------- Modelo ----------

    fun downloadModel(context: Context, onProgress: (Float) -> Unit) {
        val target = modelFile(context)
        val part = File(target.parentFile, target.name + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TonoKempo/1.1")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        finishModel(part, target)
    }

    fun importModel(context: Context, uri: Uri) {
        val target = modelFile(context)
        val part = File(target.parentFile, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(part).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw IOException("No se pudo abrir el archivo")
        finishModel(part, target)
    }

    private fun finishModel(part: File, target: File) {
        if (part.length() < MIN_MODEL_BYTES) {
            part.delete()
            throw IOException("El modelo está incompleto o no es el correcto")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("No se pudo guardar el modelo")
    }

    // ---------- Separación ----------

    fun separate(
        context: Context,
        audio: DecodedAudio,
        outDir: File,
        cancel: AtomicBoolean,
        onProgress: (Float) -> Unit
    ): SeparatedStems {
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.delete() }

        val (left, right) = prepareInput(audio)
        val length = left.size
        if (length < 1) throw IllegalArgumentException("Audio vacío")

        // Normalización igual que la línea de comandos de Demucs (media y desviación del mono)
        var sum = 0.0
        for (i in 0 until length) sum += (left[i] + right[i]) * 0.5
        val mean = sum / length
        var sq = 0.0
        for (i in 0 until length) {
            val d = (left[i] + right[i]) * 0.5 - mean
            sq += d * d
        }
        var std = sqrt(sq / length)
        if (std < 1e-8) std = 1.0
        val m = mean.toFloat()
        val inv = (1.0 / std).toFloat()
        val stdF = std.toFloat()

        // Peso triangular para unir los segmentos solapados
        val half = SEGMENT / 2
        val weight = FloatArray(SEGMENT) { k ->
            if (k < half) (k + 1).toFloat() / half else (SEGMENT - k).toFloat() / half
        }

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6))
        val session = env.createSession(modelFile(context).absolutePath, opts)

        val files = STEM_NAMES.map { File(outDir, "$it.pcm") }
        val streams = files.map { BufferedOutputStream(FileOutputStream(it), 1 shl 16) }
        val acc = Array(8) { FloatArray(SEGMENT) }
        val wsum = FloatArray(SEGMENT)
        val inBuf = ByteBuffer.allocateDirect(2 * SEGMENT * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val shape = longArrayOf(1, 2, SEGMENT.toLong())
        val frameBuf = ByteArray(SEGMENT * 4)
        val totalChunks = ceil(length.toDouble() / STRIDE).toInt()

        try {
            for (ci in 0 until totalChunks) {
                if (cancel.get()) throw CancelledException()

                val offset = ci * STRIDE
                val n = min(SEGMENT, length - offset)

                inBuf.clear()
                for (k in 0 until SEGMENT) inBuf.put(if (k < n) (left[offset + k] - m) * inv else 0f)
                for (k in 0 until SEGMENT) inBuf.put(if (k < n) (right[offset + k] - m) * inv else 0f)
                inBuf.rewind()

                OnnxTensor.createTensor(env, inBuf, shape).use { tensor ->
                    session.run(mapOf("mix" to tensor)).use { result ->
                        val fb = (result.get(0) as OnnxTensor).floatBuffer
                        for (idx in 0 until 8) {
                            val a = acc[idx]
                            val base = idx * SEGMENT
                            for (k in 0 until n) a[k] += fb.get(base + k) * weight[k]
                        }
                    }
                }
                for (k in 0 until n) wsum[k] += weight[k]

                val last = ci == totalChunks - 1
                val flush = if (last) n else STRIDE
                writeFrames(acc, wsum, flush, stdF, frameBuf, streams)

                if (!last) {
                    for (a in acc) {
                        System.arraycopy(a, STRIDE, a, 0, SEGMENT - STRIDE)
                        java.util.Arrays.fill(a, SEGMENT - STRIDE, SEGMENT, 0f)
                    }
                    System.arraycopy(wsum, STRIDE, wsum, 0, SEGMENT - STRIDE)
                    java.util.Arrays.fill(wsum, SEGMENT - STRIDE, SEGMENT, 0f)
                }
                onProgress((ci + 1f) / totalChunks)
            }
        } catch (e: Throwable) {
            streams.forEach { try { it.close() } catch (_: Exception) {} }
            files.forEach { it.delete() }
            throw e
        } finally {
            try { session.close() } catch (_: Exception) {}
            try { opts.close() } catch (_: Exception) {}
        }
        streams.forEach { it.close() }
        return SeparatedStems(files)
    }

    private fun writeFrames(
        acc: Array<FloatArray>,
        wsum: FloatArray,
        frames: Int,
        scale: Float,
        buf: ByteArray,
        streams: List<BufferedOutputStream>
    ) {
        for (s in 0 until 4) {
            val l = acc[s * 2]
            val r = acc[s * 2 + 1]
            var p = 0
            for (k in 0 until frames) {
                val g = scale / wsum[k]
                val lv = (l[k] * g * 32767f).roundToInt().coerceIn(-32768, 32767)
                val rv = (r[k] * g * 32767f).roundToInt().coerceIn(-32768, 32767)
                buf[p++] = lv.toByte()
                buf[p++] = (lv shr 8).toByte()
                buf[p++] = rv.toByte()
                buf[p++] = (rv shr 8).toByte()
            }
            streams[s].write(buf, 0, frames * 4)
        }
    }

    /** PCM 16 bits del archivo -> dos canales float a 44.1 kHz (mono se duplica). */
    private fun prepareInput(audio: DecodedAudio): Pair<FloatArray, FloatArray> {
        val ch = audio.channels
        val frames = audio.pcm.size / (2 * ch)
        val shorts = ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var l = FloatArray(frames)
        var r = FloatArray(frames)
        for (i in 0 until frames) {
            if (ch == 1) {
                val v = shorts.get(i) / 32768f
                l[i] = v
                r[i] = v
            } else {
                l[i] = shorts.get(i * 2) / 32768f
                r[i] = shorts.get(i * 2 + 1) / 32768f
            }
        }
        if (audio.sampleRate != SAMPLE_RATE) {
            val srcRate = audio.sampleRate
            val inL = l
            val inR = r
            var outR: FloatArray = inR
            val t = Thread { outR = Resampler.resample(inR, srcRate, SAMPLE_RATE) }
            t.start()
            l = Resampler.resample(inL, srcRate, SAMPLE_RATE)
            t.join()
            r = outR
        }
        return l to r
    }
}
