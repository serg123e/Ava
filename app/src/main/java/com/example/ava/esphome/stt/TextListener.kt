package com.example.ava.esphome.stt

/**
 * The result of listening for a voice command on the device.
 */
sealed class ListenResult {
    /**
     * The command was recognised as [text].
     */
    data class Text(val text: String) : ListenResult()

    /**
     * Nothing was said, or nothing could be recognised.
     */
    data object NoSpeech : ListenResult()

    /**
     * Recognition isn't working, e.g. the language isn't available.
     */
    data class Failed(val reason: String) : ListenResult()
}

/**
 * Listens for a voice command and recognises it on the device, as an alternative
 * to streaming the microphone audio to Home Assistant.
 */
interface TextListener {
    suspend fun listen(): ListenResult
}
