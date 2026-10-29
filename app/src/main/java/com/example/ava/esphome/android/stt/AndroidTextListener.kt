package com.example.ava.esphome.android.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.ava.esphome.stt.ListenResult
import com.example.ava.esphome.stt.TextListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Recognises speech with the Android speech recognizer, preferring the on-device
 * recognizer, which works offline, and falling back to the default one if the
 * on-device recognizer doesn't have the language.
 *
 * @param language The BCP 47 language tag, empty for the device language.
 */
class AndroidTextListener(
    private val context: Context,
    private val language: suspend () -> String
) : TextListener {
    private val handler = Handler(Looper.getMainLooper())

    override suspend fun listen(): ListenResult {
        val tag = language().ifBlank { Locale.getDefault().toLanguageTag() }
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (onDevice) {
            val result = listen(tag, onDevice = true)
            if (result !is ListenResult.Failed) return result
            Timber.w("On-device recognition failed (${result.reason}), using the default recognizer")
        }
        return listen(tag, onDevice = false)
    }

    // SpeechRecognizer must be created and used on the main thread
    private suspend fun listen(tag: String, onDevice: Boolean): ListenResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val recognizer =
                    if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    else SpeechRecognizer.createSpeechRecognizer(context)

                fun finish(result: ListenResult) {
                    if (cont.isActive) cont.resume(result)
                    recognizer.destroy()
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        Timber.d("Recognised: $text")
                        finish(if (text.isNullOrBlank()) ListenResult.NoSpeech else ListenResult.Text(text))
                    }

                    override fun onError(error: Int) {
                        Timber.d("Speech recognizer error: $error")
                        finish(
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenResult.NoSpeech

                                else -> ListenResult.Failed("error $error")
                            }
                        )
                    }
                })
                cont.invokeOnCancellation {
                    handler.post {
                        recognizer.cancel()
                        recognizer.destroy()
                    }
                }
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
                })
            }
        }
}
