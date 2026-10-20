package com.tonokempo.app

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    fun write(out: OutputStream, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)                       // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)                      // bits por muestra
            put("data".toByteArray())
            putInt(pcm.size)
        }
        out.write(header.array())
        out.write(pcm)
        out.flush()
    }
}
