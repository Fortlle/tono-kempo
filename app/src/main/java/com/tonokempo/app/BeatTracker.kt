package com.tonokempo.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/**
 * Resultado de la detección: tiempos de cada golpe (segundos), número de cada golpe dentro del compás
 * (1 = primer tiempo), tempo, tiempos por compás y las probabilidades del modelo (para recalcular).
 */
class BeatAnalysis(
    val beats: DoubleArray,
    val beatInBar: IntArray,
    val bpm: Double,
    val meter: Int,
    val beatProb: FloatArray,
    val downProb: FloatArray
)

/**
 * Detección de tempo y golpes con Beat This! (CPJKU, ISMIR 2024, licencia MIT) en formato ONNX.
 * El modelo recibe un espectrograma log-mel y devuelve logits de golpe y de primer tiempo para cada
 * frame de 20 ms. Después se usa un seguimiento con tempo restringido (DbnTracker) para obtener
 * golpes regulares y compases coherentes.
 */
object BeatTracker {
    private const val CHUNK = 1500
    private const val BORDER = 6
    private const val STRIDE = CHUNK - 2 * BORDER
    private const val FPS = 50.0
    private const val MIN_MODEL_BYTES = 50_000_000L

    private val MODEL_URLS = listOf(
        "https://huggingface.co/aaatmy/beat-this-onnx/resolve/main/beat-this-final0.onnx",
        "https://huggingface.co/ashudesai/songbird-models/resolve/main/final0.onnx",
        "https://huggingface.co/musetric/beat-this-onnx/resolve/main/beat_this.onnx"
    )

    fun modelFile(context: Context) = File(context.filesDir, "beat_this.onnx")

    fun hasModel(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MIN_MODEL_BYTES
    }

    fun downloadModel(context: Context, onProgress: (Float) -> Unit) =
        ModelStore.download(MODEL_URLS, modelFile(context), MIN_MODEL_BYTES, onProgress)

    fun importModel(context: Context, uri: Uri) =
        ModelStore.importFile(context, uri, modelFile(context), MIN_MODEL_BYTES)

    fun analyze(
        context: Context,
        audio: DecodedAudio,
        meters: IntArray,
        cancel: AtomicBoolean,
        onProgress: (Float) -> Unit
    ): BeatAnalysis {
        val mono = toMono22k(audio)
        onProgress(0.05f)
        val (mel, frames) = LogMel.compute(mono)
        onProgress(0.15f)

        val beatLogits = FloatArray(frames) { -10f }
        val downLogits = FloatArray(frames) { -10f }

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6))
        val session = env.createSession(modelFile(context).absolutePath, opts)

        try {
            val inName = session.inputNames.first()
            val outNames = session.outputNames.toList()
            val beatIdx = outNames.indexOfFirst { it.contains("beat", true) && !it.contains("down", true) }
                .let { if (it < 0) 0 else it }
            val downIdx = outNames.indexOfFirst { it.contains("down", true) }
                .let { if (it < 0) min(1, outNames.size - 1) else it }

            val inBuf = ByteBuffer.allocateDirect(CHUNK * LogMel.N_MELS * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val shape = longArrayOf(1, CHUNK.toLong(), LogMel.N_MELS.toLong())
            val zeros = FloatArray(LogMel.N_MELS)
            val chunks = ceil(frames.toDouble() / STRIDE).toInt()

            for (ci in 0 until chunks) {
                if (cancel.get()) throw CancelledException()
                val start = ci * STRIDE - BORDER

                inBuf.clear()
                for (j in 0 until CHUNK) {
                    val g = start + j
                    if (g in 0 until frames) inBuf.put(mel, g * LogMel.N_MELS, LogMel.N_MELS)
                    else inBuf.put(zeros)
                }
                inBuf.rewind()

                OnnxTensor.createTensor(env, inBuf, shape).use { tensor ->
                    session.run(mapOf(inName to tensor)).use { result ->
                        val b = (result.get(beatIdx) as OnnxTensor).floatBuffer
                        val d = (result.get(downIdx) as OnnxTensor).floatBuffer
                        for (j in BORDER until CHUNK - BORDER) {
                            val g = ci * STRIDE + (j - BORDER)
                            if (g >= frames) break
                            beatLogits[g] = b.get(j)
                            downLogits[g] = d.get(j)
                        }
                    }
                }
                onProgress(0.15f + 0.85f * (ci + 1f) / chunks)
            }
        } finally {
            try { session.close() } catch (_: Exception) {}
            try { opts.close() } catch (_: Exception) {}
        }

        val beatProb = FloatArray(frames) { sigmoid(beatLogits[it]) }
        val downProb = FloatArray(frames) { sigmoid(downLogits[it]) }
        return decode(beatProb, downProb, meters)
    }

    /** Calcula golpes y compases a partir de las probabilidades. Se puede repetir sin volver a correr el modelo. */
    fun decode(beatProb: FloatArray, downProb: FloatArray, meters: IntArray): BeatAnalysis {
        val r = DbnTracker.decode(beatProb, downProb, meters)
        if (r != null && r.frames.size >= 2) {
            val beats = DoubleArray(r.frames.size) { r.frames[it] / FPS }
            return BeatAnalysis(beats, r.numbers, estimateBpm(beats), r.meter, beatProb, downProb)
        }

        // Respaldo: picos simples (probabilidad > 0.5) si el seguimiento no encuentra pulso
        val peaks = pickPeaks(beatProb, 0.5f)
        val beats = DoubleArray(peaks.size) { peaks[it] / FPS }
        val numbers = IntArray(beats.size) { it % 4 + 1 }
        return BeatAnalysis(beats, numbers, estimateBpm(beats), 4, beatProb, downProb)
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private fun pickPeaks(prob: FloatArray, threshold: Float): DoubleArray {
        val peaks = ArrayList<Int>()
        for (i in prob.indices) {
            val v = prob[i]
            if (v <= threshold) continue
            var isMax = true
            for (d in -3..3) {
                val j = i + d
                if (j in prob.indices && prob[j] > v) {
                    isMax = false
                    break
                }
            }
            if (isMax) peaks.add(i)
        }
        val merged = ArrayList<Double>()
        var k = 0
        while (k < peaks.size) {
            var j = k
            var sum = peaks[k].toDouble()
            var cnt = 1
            while (j + 1 < peaks.size && peaks[j + 1] - peaks[j] <= 1) {
                j++
                sum += peaks[j]
                cnt++
            }
            merged.add(sum / cnt)
            k = j + 1
        }
        return merged.toDoubleArray()
    }

    private fun estimateBpm(beats: DoubleArray): Double {
        if (beats.size < 3) return 0.0
        val intervals = DoubleArray(beats.size - 1) { abs(beats[it + 1] - beats[it]) }.sortedArray()
        val median = intervals[intervals.size / 2]
        return if (median > 0) 60.0 / median else 0.0
    }

    private fun toMono22k(audio: DecodedAudio): FloatArray {
        val ch = audio.channels
        val frames = audio.pcm.size / (2 * ch)
        val shorts = ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            mono[i] = if (ch == 1) shorts.get(i) / 32768f
            else (shorts.get(i * 2) + shorts.get(i * 2 + 1)) / 65536f
        }
        return Resampler.resample(mono, audio.sampleRate, LogMel.SAMPLE_RATE)
    }
}
