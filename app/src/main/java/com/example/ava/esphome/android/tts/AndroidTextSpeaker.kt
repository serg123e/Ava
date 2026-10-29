package com.example.ava.esphome.android.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.ava.esphome.tts.TextSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A voice of an Android text-to-speech engine.
 */
data class DeviceTtsVoice(
    val name: String,
    val locale: Locale,
    val requiresNetwork: Boolean
)

/**
 * An installed Android text-to-speech engine.
 */
data class DeviceTtsEngine(
    val name: String,
    val label: String
)

/**
 * Speaks text using the Android text-to-speech engine.
 * The engine, voice and rate are read on every call to [speak], so changes to the
 * settings apply to the next response.
 *
 * @param engine The package name of the engine, empty for the system default.
 * @param voice The name of the voice, empty for the engine's default voice.
 * @param rate The speech rate, 1.0 is normal speed.
 */
class AndroidTextSpeaker(
    private val context: Context,
    private val engine: suspend () -> String,
    private val voice: suspend () -> String,
    private val rate: suspend () -> Float
) : TextSpeaker {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private val pending = ConcurrentHashMap<String, () -> Unit>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = finished(utteranceId)
        override fun onError(utteranceId: String, errorCode: Int) {
            Timber.w("Text-to-speech error: $errorCode")
            finished(utteranceId)
        }
    }

    private fun finished(utteranceId: String) {
        pending.remove(utteranceId)?.let {
            audioManager.abandonAudioFocusRequest(focusRequest)
            it()
        }
    }

    override suspend fun speak(text: String, volume: Float, onCompletion: () -> Unit) {
        val tts = ready(engine())
        if (tts == null) {
            onCompletion()
            return
        }
        val voiceName = voice()
        tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
        tts.setSpeechRate(rate())
        val id = UUID.randomUUID().toString()
        pending[id] = onCompletion
        audioManager.requestAudioFocus(focusRequest)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            Timber.w("Failed to queue text-to-speech")
            finished(id)
        }
    }

    override fun stop() {
        // Stopped speech isn't completed, so drop the callbacks before the engine reports it
        pending.clear()
        tts?.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * Returns an initialised engine, recreating it if the selected engine changed.
     */
    private suspend fun ready(engine: String): TextToSpeech? = mutex.withLock {
        tts?.takeIf { ttsEngine == engine }?.let { return it }
        tts?.shutdown()
        tts = null
        val created = initTextToSpeech(context, engine) ?: return null
        created.setAudioAttributes(audioAttributes)
        created.setOnUtteranceProgressListener(listener)
        tts = created
        ttsEngine = engine
        created
    }

    override fun close() {
        pending.clear()
        tts?.shutdown()
        tts = null
    }

    companion object {
        /**
         * Lists the installed engines and the voices of [engine].
         */
        suspend fun catalog(
            context: Context,
            engine: String
        ): Pair<List<DeviceTtsEngine>, List<DeviceTtsVoice>> {
            val tts = initTextToSpeech(context, engine) ?: return emptyList<DeviceTtsEngine>() to emptyList()
            try {
                val engines = tts.engines.map { DeviceTtsEngine(it.name, it.label) }
                val voices = tts.voices.orEmpty()
                    .map { DeviceTtsVoice(it.name, it.locale, it.isNetworkConnectionRequired) }
                    .sortedBy { it.name }
                return engines to voices
            } finally {
                tts.shutdown()
            }
        }

        /**
         * Speaks a sample [text] once, for trying out the settings.
         */
        suspend fun speakSample(context: Context, engine: String, voice: String, rate: Float, text: String) {
            val speaker = AndroidTextSpeaker(context, { engine }, { voice }, { rate })
            speaker.speak(text, 1.0f) { speaker.close() }
        }

        private suspend fun initTextToSpeech(context: Context, engine: String): TextToSpeech? {
            val status = CompletableDeferred<Int>()
            val tts = TextToSpeech(context, { status.complete(it) }, engine.ifEmpty { null })
            if (status.await() != TextToSpeech.SUCCESS) {
                Timber.w("Failed to initialise text-to-speech engine: $engine")
                tts.shutdown()
                return null
            }
            return tts
        }
    }
}
