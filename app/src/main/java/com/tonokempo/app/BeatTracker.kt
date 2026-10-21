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
import kotlin.math.min

/** Golpes (beats) en segundos, cuáles son primer tiempo de compás, tempo y tiempos por compás. */
class BeatAnalysis(
    val beats: DoubleArray,
    val isDown: BooleanArray,
    val bpm: Double,
    val beatsPerBar: Int
)

/**
 * Detección de tempo y golpes con Beat This! (CPJKU, ISMIR 2024, licencia MIT) en formato ONNX.
 * El modelo recibe un espectrograma log-mel y devuelve probabilidades (logits) de golpe y de
 * primer tiempo para cada frame de 20 ms. Aquí se hace el troceado y la selección de picos
 * ("minimal postprocessing" del proyecto original).
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

        val beatFrames = pickPeaks(beatLogits)
        val downFrames = pickPeaks(downLogits)
        val beats = DoubleArray(beatFrames.size) { beatFrames[it] / FPS }
        val downTimes = DoubleArray(downFrames.size) { downFrames[it] / FPS }

        // Los primeros tiempos de compás se ajustan al golpe más cercano
        val isDown = BooleanArray(beats.size)
        for (t in downTimes) {
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in beats.indices) {
                val dist = abs(beats[i] - t)
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
            if (best >= 0) isDown[best] = true
        }

        return BeatAnalysis(beats, isDown, estimateBpm(beats), estimateBeatsPerBar(isDown))
    }

    /** Picos: logit positivo y máximo en una ventana de 7 frames; picos pegados se funden en su media. */
    private fun pickPeaks(logits: FloatArray): DoubleArray {
        val peaks = ArrayList<Int>()
        for (i in logits.indices) {
            val v = logits[i]
            if (v <= 0f) continue
            var isMax = true
            for (d in -3..3) {
                val j = i + d
                if (j in logits.indices && logits[j] > v) {
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
        val intervals = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }.sortedArray()
        val median = intervals[intervals.size / 2]
        return if (median > 0) 60.0 / median else 0.0
    }

    private fun estimateBeatsPerBar(isDown: BooleanArray): Int {
        val idx = isDown.indices.filter { isDown[it] }
        if (idx.size < 2) return 4
        val diffs = IntArray(idx.size - 1) { idx[it + 1] - idx[it] }.sortedArray()
        return diffs[diffs.size / 2].coerceIn(2, 12)
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
