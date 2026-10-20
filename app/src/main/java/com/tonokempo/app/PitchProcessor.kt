package com.tonokempo.app

import io.github.numq.kempo.PcmConverter

enum class Engine { RUBBERBAND, KEMPO }

/** Aquí vive la elección de motor. Cada motor recibe PCM 16 bits y devuelve PCM 16 bits. */
object PitchProcessor {

    /**
     * Cambia el tono [semitones] sin cambiar la duración.
     * [moveFormants] = true mueve también el timbre (formantes) junto con el tono.
     */
    fun shift(
        audio: DecodedAudio,
        semitones: Float,
        moveFormants: Boolean,
        engine: Engine,
        report: (Float) -> Unit = {}
    ): ByteArray {
        if (semitones == 0f) return audio.pcm

        return when (engine) {
            Engine.RUBBERBAND -> RubberBandEngine.process(
                audio.pcm,
                audio.sampleRate,
                audio.channels,
                semitones,
                !moveFormants,
                object : ProgressListener {
                    override fun onProgress(fraction: Float) = report(fraction)
                }
            )

            Engine.KEMPO -> shiftWithKempo(audio, semitones, moveFormants)
        }
    }

    private fun shiftWithKempo(audio: DecodedAudio, semitones: Float, moveFormants: Boolean): ByteArray {
        val track = PcmConverter.fromPcm16(
            bytes = audio.pcm,
            channels = audio.channels,
            sampleRate = audio.sampleRate.toFloat(),
            isLittleEndian = true
        )

        val processed = track.stretch(
            timeRatio = 1.0f,
            pitchSemitones = semitones,
            formantSemitones = if (moveFormants) semitones else 0.0f
        )

        return PcmConverter.toPcm16(processed, isLittleEndian = true)
    }
}
