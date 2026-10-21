package com.tonokempo.app

interface ProgressListener {
    fun onProgress(fraction: Float)
}

/** Puente JNI hacia Rubber Band (motor R3, modo offline). Código en app/src/main/cpp/native.cpp */
object RubberBandEngine {

    init {
        System.loadLibrary("rbjni")
    }

    /**
     * [pcm]: PCM 16 bits little-endian entrelazado.
     * [preserveFormants] = true mantiene el timbre natural; false lo mueve junto con el tono.
     * [timeRatio]: 1.0 = misma duración; mayor que 1 = más larga y lenta; menor que 1 = más corta y rápida.
     */
    external fun process(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        semitones: Float,
        preserveFormants: Boolean,
        timeRatio: Double,
        listener: ProgressListener?
    ): ByteArray
}
