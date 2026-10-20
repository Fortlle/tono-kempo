package com.tonokempo.app

import io.github.numq.kempo.PcmConverter

/** Aquí vive todo el uso de Kempo. Si algún día cambias de motor, solo tocas este archivo. */
object PitchProcessor {

    /**
     * Cambia el tono [semitones] sin cambiar la duración.
     * [moveFormants] = true mueve también el timbre (formantes) junto con el tono.
     */
    fun shift(audio: DecodedAudio, semitones: Float, moveFormants: Boolean): ByteArray {
        if (semitones == 0f) return audio.pcm

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
