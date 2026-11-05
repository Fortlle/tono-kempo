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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Resultado de la detección.
 * [bpm]: tempo de la rejilla ajustada a las probabilidades del modelo en toda la canción.
 * [beatT0]: tiempo (s) de un golpe de esa rejilla. [gridT0]: tiempo (s) del golpe que parece ser el 1 del compás.
 * [beats]/[beatInBar]: golpes sueltos que marca el modelo (solo informativos).
 * [beatProb]/[downProb]: probabilidades del modelo por frame de 20 ms.
 */
class BeatAnalysis(
    val beats: DoubleArray,
    val beatInBar: IntArray,
    val bpm: Double,
    val meter: Int,
    val gridT0: Double,
    val beatT0: Double,
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
        useDbn: Boolean,
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
        return decode(beatProb, downProb, meters, useDbn)
    }

    /**
     * Calcula golpes y compases a partir de las probabilidades. Se puede repetir sin volver a correr el modelo.
     * [useDbn] = false: picos simples del modelo (fiel a lo que oye). true: seguimiento con tempo restringido.
     */
    fun decode(beatProb: FloatArray, downProb: FloatArray, meters: IntArray, useDbn: Boolean): BeatAnalysis {
        if (useDbn) {
            val r = DbnTracker.decode(beatProb, downProb, meters)
            if (r != null && r.frames.size >= 2) {
                val beats = DoubleArray(r.frames.size) { r.frames[it] / FPS }
                return assemble(beats, r.numbers, meters, beatProb, downProb)
            }
        }

        val bPeaks = pickPeaks(beatProb, 0.5f)
        val dPeaks = pickPeaks(downProb, 0.5f)
        val beats = DoubleArray(bPeaks.size) { bPeaks[it] / FPS }
        val isDown = BooleanArray(beats.size)
        for (d in dPeaks) {
            val t = d / FPS
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

        val meter = if (meters.size == 1) meters[0] else {
            val est = estimateBeatsPerBar(isDown)
            meters.minByOrNull { abs(it - est) } ?: 4
        }
        return assemble(beats, numberBeats(beats.size, isDown, meter), meters, beatProb, downProb)
    }

    private fun assemble(
        beats: DoubleArray,
        numbers: IntArray,
        meters: IntArray,
        beatProb: FloatArray,
        downProb: FloatArray
    ): BeatAnalysis {
        val fit = fitGrid(beats)
        val period0 = fit?.first ?: (if (beats.size >= 3) medianInterval(beats) else 0.0)
        val defaultMeter = meters.firstOrNull() ?: 4
        if (period0 <= 0.0) {
            return BeatAnalysis(beats, numbers, 0.0, defaultMeter, 0.0, 0.0, beatProb, downProb)
        }
        val t0Guess = fit?.second ?: beats.firstOrNull() ?: 0.0

        // Se afina la rejilla para que sus puntos caigan sobre los picos de actividad del modelo
        val (bpm, beatT0) = refineGrid(beatProb, period0, t0Guess)
        val (meter, downT0) = chooseMeterAndDownbeat(downProb, beatT0, 60.0 / bpm, meters)
        return BeatAnalysis(beats, numbers, bpm, meter, downT0, beatT0, beatProb, downProb)
    }

    /** Vuelve a elegir el 1 del compás con otros tiempos por compás permitidos, sin repetir el modelo. */
    fun realign(an: BeatAnalysis, meters: IntArray): BeatAnalysis {
        if (an.bpm <= 0.0) return an
        val (meter, downT0) = chooseMeterAndDownbeat(an.downProb, an.beatT0, 60.0 / an.bpm, meters)
        return BeatAnalysis(an.beats, an.beatInBar, an.bpm, meter, downT0, an.beatT0, an.beatProb, an.downProb)
    }

    /**
     * Propone cambios de compás (por ejemplo de 3/4 a 4/4) mirando cada cuántos golpes de la rejilla aparece
     * un primer tiempo. Devuelve (instante en s, tiempos por compás) para cada cambio. Es solo una sugerencia.
     */
    fun suggestMeterChanges(an: BeatAnalysis, bpm: Double, anchor: Double): List<Pair<Double, Int>> {
        if (bpm <= 0.0) return emptyList()
        val period = 60.0 / bpm
        val duration = an.downProb.size / FPS

        val times = ArrayList<Double>()
        var k = -Math.floor(anchor / period).toInt()
        while (true) {
            val t = anchor + k * period
            if (t >= duration) break
            if (t >= 0.0) times.add(t)
            k++
        }
        val n = times.size
        if (n < 12) return emptyList()

        val score = DoubleArray(n) { maxAround(an.downProb, times[it], 2) }
        val cands = ArrayList<Int>()
        for (i in 0 until n) {
            val s = score[i]
            if (s < 0.3) continue
            val l = if (i > 0) score[i - 1] else 0.0
            val r = if (i < n - 1) score[i + 1] else 0.0
            if (s >= l && s >= r) cands.add(i)
        }
        if (cands.size < 4) return emptyList()

        val lens = IntArray(cands.size - 1) { (cands[it + 1] - cands[it]).coerceIn(1, 12) }
        val sm = IntArray(lens.size) { i ->
            val a = lens[maxOf(0, i - 1)]
            val b = lens[i]
            val c = lens[minOf(lens.size - 1, i + 1)]
            maxOf(minOf(a, b), minOf(maxOf(a, b), c))   // mediana de tres
        }

        val out = ArrayList<Pair<Double, Int>>()
        var cur = sm[0]
        for (i in 1 until sm.size) {
            if (sm[i] != cur) {
                val persists = i + 1 >= sm.size || sm[i + 1] == sm[i]
                if (persists && sm[i] in 2..9) {
                    out.add(times[cands[i]] to sm[i])
                    cur = sm[i]
                }
            }
        }
        return out
    }

    private fun probAt(p: FloatArray, t: Double): Double {
        val x = t * FPS
        val i = Math.floor(x).toInt()
        if (i < 0 || i >= p.size - 1) return 0.0
        val f = x - i
        return p[i] * (1.0 - f) + p[i + 1] * f
    }

    private fun maxAround(p: FloatArray, t: Double, w: Int): Double {
        val c = Math.round(t * FPS).toInt()
        var m = 0f
        for (i in (c - w)..(c + w)) if (i in p.indices && p[i] > m) m = p[i]
        return m.toDouble()
    }

    private fun gridScore(p: FloatArray, t0: Double, step: Double, duration: Double): Double {
        var s = 0.0
        var t = t0
        while (t < duration) {
            s += probAt(p, t)
            t += step
        }
        return s
    }

    /**
     * Busca los BPM (cerca de [period0]) y la fase que hacen que una rejilla perfectamente regular
     * pase por los picos de probabilidad de golpe en toda la canción. Es más robusto que fiarse de golpes sueltos.
     */
    private fun refineGrid(beatProb: FloatArray, period0: Double, t0Guess: Double): Pair<Double, Double> {
        val duration = beatProb.size / FPS
        val bpm0 = 60.0 / period0
        var bestScore = -1.0
        var bestBpm = bpm0
        var bestT0 = ((t0Guess % period0) + period0) % period0

        var bpm = bpm0 - 0.8
        while (bpm <= bpm0 + 0.8) {
            if (bpm > 20.0) {
                val step = 60.0 / bpm
                var d = 0.0
                while (d < step) {
                    val sc = gridScore(beatProb, d, step, duration)
                    if (sc > bestScore) {
                        bestScore = sc
                        bestBpm = bpm
                        bestT0 = d
                    }
                    d += 0.01
                }
            }
            bpm += 0.02
        }

        // Afinado de la fase a 2 ms
        val step = 60.0 / bestBpm
        var d = bestT0 - 0.012
        var bestD = bestT0
        var bestSc = -1.0
        while (d <= bestT0 + 0.012) {
            val sc = gridScore(beatProb, ((d % step) + step) % step, step, duration)
            if (sc > bestSc) {
                bestSc = sc
                bestD = d
            }
            d += 0.002
        }
        return bestBpm to (((bestD % step) + step) % step)
    }

    /**
     * Entre los compases permitidos y las posiciones posibles del 1, elige el que concentra más probabilidad
     * de primer tiempo a lo largo de toda la canción. Así hay un solo acento por compás, siempre en la misma posición.
     */
    private fun chooseMeterAndDownbeat(
        down: FloatArray,
        beatT0: Double,
        period: Double,
        meters: IntArray
    ): Pair<Int, Double> {
        val duration = down.size / FPS
        var bestM = meters.firstOrNull() ?: 4
        var bestT0 = beatT0
        var bestAvg = -1.0
        for (m in meters) {
            for (j in 0 until m) {
                var s = 0.0
                var n = 0
                var t = beatT0 + j * period
                while (t < duration) {
                    s += maxAround(down, t, 2)
                    n++
                    t += m * period
                }
                val avg = if (n > 0) s / n else 0.0
                if (avg > bestAvg) {
                    bestAvg = avg
                    bestM = m
                    bestT0 = beatT0 + j * period
                }
            }
        }
        return bestM to bestT0
    }

    private fun medianInterval(beats: DoubleArray): Double {
        val iv = DoubleArray(beats.size - 1) { abs(beats[it + 1] - beats[it]) }.sortedArray()
        return iv[iv.size / 2]
    }

    /**
     * Ajusta una rejilla regular (periodo, tiempo del golpe 0) a los golpes detectados:
     * fase inicial por media circular y regresión lineal sobre los golpes que encajan, descartando los demás.
     */
    private fun fitGrid(beats: DoubleArray): Pair<Double, Double>? {
        if (beats.size < 8) return null
        var period = medianInterval(beats)
        if (period <= 0.0) return null

        var sx = 0.0
        var sy = 0.0
        for (b in beats) {
            val ang = 2.0 * PI * b / period
            sx += cos(ang)
            sy += sin(ang)
        }
        var t0 = atan2(sy, sx) / (2.0 * PI) * period

        repeat(6) {
            var n = 0
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumXY = 0.0
            for (b in beats) {
                val idx = Math.round((b - t0) / period).toDouble()
                val res = b - (t0 + idx * period)
                if (abs(res) < 0.2 * period) {
                    n++
                    sumX += idx
                    sumY += b
                    sumXX += idx * idx
                    sumXY += idx * b
                }
            }
            if (n < 8) return@repeat
            val denom = n * sumXX - sumX * sumX
            if (abs(denom) < 1e-9) return@repeat
            val slope = (n * sumXY - sumX * sumY) / denom
            val intercept = (sumY - slope * sumX) / n
            if (slope > 0.0) {
                period = slope
                t0 = intercept
            }
        }
        return period to t0
    }

    /** Numera los golpes dentro del compás a partir de las marcas de primer tiempo. */
    private fun numberBeats(n: Int, isDown: BooleanArray, meter: Int): IntArray {
        val out = IntArray(n)
        val first = isDown.indexOfFirst { it }
        if (first < 0) {
            for (i in 0 until n) out[i] = i % meter + 1
            return out
        }
        out[first] = 1
        for (i in first + 1 until n) out[i] = if (isDown[i]) 1 else out[i - 1] % meter + 1
        for (i in first - 1 downTo 0) out[i] = if (out[i + 1] == 1) meter else out[i + 1] - 1
        return out
    }

    private fun estimateBeatsPerBar(isDown: BooleanArray): Int {
        val idx = isDown.indices.filter { isDown[it] }
        if (idx.size < 2) return 4
        val diffs = IntArray(idx.size - 1) { idx[it + 1] - idx[it] }.sortedArray()
        return diffs[diffs.size / 2].coerceIn(2, 12)
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
