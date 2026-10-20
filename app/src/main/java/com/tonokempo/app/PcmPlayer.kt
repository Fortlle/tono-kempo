package com.tonokempo.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/** Reproductor mínimo de PCM 16 bits en memoria, con pausa y posición. */
class PcmPlayer(private val onFinished: () -> Unit) {

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile private var data = ByteArray(0)
    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    @Volatile private var pos = 0
    @Volatile private var baseFrames = 0L
    @Volatile private var seekTo = -1
    @Volatile private var paused = false
    @Volatile private var alive = false

    val isActive: Boolean get() = alive
    val isPaused: Boolean get() = alive && paused

    private fun frameSize() = channels * 2
    private fun totalFrames() = (data.size / frameSize()).toLong()

    val durationMs: Long get() = totalFrames() * 1000L / sampleRate

    fun positionMs(): Long {
        val t = track
        if (!alive || t == null) return 0L
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (baseFrames + head) * 1000L / sampleRate
    }

    fun start(pcm: ByteArray, sampleRate: Int, channels: Int, startMs: Long = 0L) {
        stop()
        data = pcm
        this.sampleRate = sampleRate
        this.channels = channels

        val fs = frameSize()
        val startFrame = (startMs * sampleRate / 1000L).coerceIn(0L, totalFrames())
        pos = (startFrame * fs).toInt()
        baseFrames = startFrame
        seekTo = -1
        paused = false
        alive = true

        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = max(minBuf, sampleRate * fs / 2)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        worker = Thread { loop(t) }.also { it.start() }
    }

    private fun loop(t: AudioTrack) {
        val fs = frameSize()
        val chunk = (16384 / fs) * fs
        var finished = false
        try {
            t.play()
            while (alive) {
                val s = seekTo
                if (s >= 0) {
                    seekTo = -1
                    pos = s
                    baseFrames = (s / fs).toLong()
                    t.pause()
                    t.flush()
                    if (!paused) t.play()
                }
                if (paused) {
                    Thread.sleep(25)
                    continue
                }
                val p = pos
                if (p >= data.size) {
                    finished = true
                    break
                }
                val n = min(chunk, data.size - p)
                val w = t.write(data, p, n)
                if (w < 0) break
                if (seekTo < 0) pos = p + w
            }
            if (finished && alive) {
                t.stop()
                val remaining = totalFrames() - baseFrames
                var waited = 0
                while (alive && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < remaining && waited < 3000) {
                    Thread.sleep(25)
                    waited += 25
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: IllegalStateException) {
        } finally {
            if (finished && alive) {
                alive = false
                onFinished()
            }
        }
    }

    fun pause() {
        if (!alive) return
        paused = true
        try { track?.pause() } catch (_: IllegalStateException) {}
    }

    fun resume() {
        if (!alive) return
        paused = false
        try { track?.play() } catch (_: IllegalStateException) {}
    }

    fun seekToMs(ms: Long) {
        if (!alive) return
        val frame = (ms * sampleRate / 1000L).coerceIn(0L, totalFrames())
        seekTo = (frame * frameSize()).toInt()
    }

    fun stop() {
        val t = track
        val w = worker
        alive = false
        paused = false
        try { t?.pause(); t?.flush() } catch (_: IllegalStateException) {}
        w?.interrupt()
        try { w?.join(500) } catch (_: InterruptedException) {}
        try { t?.release() } catch (_: Exception) {}
        track = null
        worker = null
        baseFrames = 0L
    }
}
