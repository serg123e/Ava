package com.example.ava.esphome.voiceassistant

import com.example.ava.esphome.mediaplayer.MediaPlayer
import com.example.ava.esphome.tts.TextSpeaker

interface VoiceOutput : AutoCloseable {
    /**
     * Plays a TTS response.
     */
    fun playTTS(ttsUrl: String, onCompletion: () -> Unit = {})

    /**
     * Whether responses should be spoken on the device from their text,
     * using [speakText], instead of playing the TTS audio from Home Assistant.
     */
    suspend fun speaksText(): Boolean = false

    /**
     * Speaks a response on the device from its text.
     */
    suspend fun speakText(text: String, onCompletion: () -> Unit = {}) = onCompletion()

    /**
     * Plays an announcement, optionally with a preannounce sound.
     */
    fun playAnnouncement(
        preannounceUrl: String = "",
        mediaUrl: String,
        onCompletion: () -> Unit = {}
    )

    /**
     * Plays the wake sound.
     */
    suspend fun playWakeSound(onCompletion: () -> Unit = {})

    /**
     * Plays the timer finished sound. The [onCompletion] callback indicates whether the
     * the timer finished sound should be repeated.
     */
    suspend fun playTimerFinishedSound(onCompletion: (repeat: Boolean) -> Unit = {})

    /**
     * Plays the error sound.
     */
    suspend fun playErrorSound(onCompletion: () -> Unit = {})

    /**
     * Ducks the media player volume.
     */
    fun duck()

    /**
     * Un-ducks the media player volume.
     */
    fun unDuck()

    /**
     * Stops any currently playing response or sound.
     */
    fun stopTTS()
}

class VoiceOutputImpl(
    private val ttsPlayer: MediaPlayer,
    private val mediaPlayer: MediaPlayer,
    private val enableWakeSound: suspend () -> Boolean,
    private val wakeSound: suspend () -> String,
    private val timerFinishedSound: suspend () -> String,
    private val repeatTimerFinishedSound: suspend () -> Boolean,
    private val enableErrorSound: suspend () -> Boolean,
    private val errorSound: suspend () -> String,
    private val textSpeaker: TextSpeaker? = null,
    private val enableTextSpeaker: suspend () -> Boolean = { false },
    volume: Float = 1.0f,
    muted: Boolean = false,
    private val duckMultiplier: Float = 0.5f
) : VoiceOutput, MediaPlayer by mediaPlayer {
    private var _isDucked = false

    init {
        ttsPlayer.volume = volume
        ttsPlayer.muted = muted
        mediaPlayer.volume = volume
        mediaPlayer.muted = muted
    }

    override var volume
        get() = ttsPlayer.volume
        set(value) {
            ttsPlayer.volume = value
            mediaPlayer.volume = if (_isDucked) value * duckMultiplier else value
        }

    override var muted
        get() = ttsPlayer.muted
        set(value) {
            ttsPlayer.muted = value
            mediaPlayer.muted = value
        }

    override fun playTTS(ttsUrl: String, onCompletion: () -> Unit) {
        ttsPlayer.play(ttsUrl, onCompletion)
    }

    override suspend fun speaksText() = textSpeaker != null && enableTextSpeaker()

    override suspend fun speakText(text: String, onCompletion: () -> Unit) {
        val speaker = textSpeaker
        if (speaker == null || muted) {
            onCompletion()
            return
        }
        speaker.speak(text, volume, onCompletion)
    }

    override fun playAnnouncement(
        preannounceUrl: String,
        mediaUrl: String,
        onCompletion: () -> Unit
    ) {
        val urls = if (preannounceUrl.isNotEmpty()) {
            listOf(preannounceUrl, mediaUrl)
        } else {
            listOf(mediaUrl)
        }
        ttsPlayer.play(urls, onCompletion)
    }

    override suspend fun playWakeSound(onCompletion: () -> Unit) {
        if (enableWakeSound()) {
            ttsPlayer.play(wakeSound(), onCompletion)
        } else onCompletion()
    }

    override suspend fun playTimerFinishedSound(onCompletion: (repeat: Boolean) -> Unit) {
        val repeat = repeatTimerFinishedSound()
        ttsPlayer.play(timerFinishedSound()) {
            onCompletion(repeat)
        }
    }

    override suspend fun playErrorSound(onCompletion: () -> Unit) {
        if (enableErrorSound()) {
            ttsPlayer.play(errorSound(), onCompletion)
        } else onCompletion()
    }

    override fun duck() {
        _isDucked = true
        mediaPlayer.volume = ttsPlayer.volume * duckMultiplier
        // The player should gain audio focus when initialized,
        // ducking any external audio.
        ttsPlayer.requestFocus()
    }

    override fun unDuck() {
        _isDucked = false
        mediaPlayer.volume = ttsPlayer.volume
    }

    override fun stopTTS() {
        ttsPlayer.stop()
        textSpeaker?.stop()
    }

    override fun close() {
        textSpeaker?.close()
        (ttsPlayer as? AutoCloseable)?.close()
        (mediaPlayer as? AutoCloseable)?.close()
    }
}
