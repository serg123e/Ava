package com.example.ava.esphome.voiceassistant

/**
 * Keeps the audio that triggered the wake word, for training the wake word model.
 */
fun interface WakeSamples {
    /**
     * Saves 16 kHz 16-bit mono [audio]. [recognised] is whether speech was recognised
     * after the wake word.
     */
    fun save(audio: ByteArray, recognised: Boolean)
}
