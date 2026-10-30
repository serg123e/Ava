package com.example.ava.esphome.voiceassistant

import android.Manifest
import androidx.annotation.RequiresPermission
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.stt.ListenResult
import com.example.ava.esphome.stt.TextListener
import com.example.ava.esphome.voiceassistant.VoiceTimer.Companion.timerFromEvent
import com.example.ava.homeassistant.ConversationClient
import com.example.esphomeproto.api.VoiceAssistantAnnounceRequest
import com.example.esphomeproto.api.VoiceAssistantConfigurationRequest
import com.example.esphomeproto.api.VoiceAssistantEventResponse
import com.example.esphomeproto.api.VoiceAssistantSetConfiguration
import com.example.esphomeproto.api.VoiceAssistantTimerEvent
import com.example.esphomeproto.api.VoiceAssistantTimerEventResponse
import com.example.esphomeproto.api.voiceAssistantConfigurationResponse
import com.example.esphomeproto.api.voiceAssistantWakeWord
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Clock

data object Listening : EspHomeState
data object Responding : EspHomeState
data object Processing : EspHomeState

data class VoiceError(val message: String) : EspHomeState

/**
 * What was said in the latest conversation turn, for showing it on screen.
 * [at] is when it last changed, in milliseconds since the epoch.
 */
data class Transcript(val question: String, val answer: String, val at: Long)

class VoiceAssistant(
    coroutineContext: CoroutineContext,
    val voiceInput: VoiceInput,
    val voiceOutput: VoiceOutput,
    private val textListener: TextListener? = null,
    private val conversationClient: ConversationClient? = null,
    private val enableDeviceStt: suspend () -> Boolean = { false },
    private val wakeSamples: WakeSamples? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(
        coroutineContext + Job(coroutineContext.job) + CoroutineName("${this.javaClass.simpleName} Scope")
    )
    private val isConnected = MutableStateFlow(false)
    private val subscription = MutableSharedFlow<MessageLite>()
    private val _state = MutableStateFlow<EspHomeState>(Disconnected)
    val state = _state.asStateFlow()

    private val _transcript = MutableStateFlow<Transcript?>(null)
    val transcript = _transcript.asStateFlow()

    private var pipeline: VoicePipeline? = null
    private var wakeSample: ByteArray? = null
    private var announcement: Announcement? = null
    private var deviceConversation: Job? = null
    private var deviceConversationId: String? = null
    private val _pendingTimers = MutableStateFlow<Map<String, VoiceTimer>>(emptyMap())
    private val _ringingTimer = MutableStateFlow<VoiceTimer?>(null)

    val allTimers = combine(_pendingTimers, _ringingTimer) { pending, ringing ->
        listOfNotNull(ringing) + pending.values.sorted()
    }

    private val isRinging: Boolean
        get() = _ringingTimer.value != null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        startVoiceInput()
    }

    fun subscribe() = subscription.asSharedFlow()

    fun wakeAssistant() {
        scope.launch { doWakeAssistant() }
    }

    /**
     * Stops the conversation at the user's request, including while listening.
     */
    fun stopAssistant() {
        scope.launch { doStopAssistant(byUser = true) }
    }

    fun stopTimer() {
        doStopTimer()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startVoiceInput() = isConnected
        .flatMapLatest { isConnected ->
            if (isConnected) voiceInput.start() else emptyFlow()
        }
        .onEach {
            handleAudioResult(audioResult = it)
        }
        .launchIn(scope)

    suspend fun onConnected() {
        isConnected.value = true
        resetState()
    }

    suspend fun onDisconnected() {
        isConnected.value = false
        resetState(Disconnected)
    }

    suspend fun handleMessage(message: MessageLite) {
        when (message) {
            is VoiceAssistantConfigurationRequest -> subscription.emit(
                voiceAssistantConfigurationResponse {
                    availableWakeWords += voiceInput.getAvailableWakeWords().map {
                        voiceAssistantWakeWord {
                            id = it.id
                            wakeWord = it.wakeWord.wake_word
                            trainedLanguages += it.wakeWord.trained_languages.toList()
                        }
                    }
                    activeWakeWords += voiceInput.activeWakeWords.get()
                    maxActiveWakeWords = 2
                })

            is VoiceAssistantSetConfiguration -> {
                val availableWakeWords = voiceInput.getAvailableWakeWords()
                val activeWakeWords =
                    message.activeWakeWordsList.filter { availableWakeWords.any { wakeWord -> wakeWord.id == it } }
                Timber.d("Setting active wake words: $activeWakeWords")
                if (activeWakeWords.isNotEmpty()) {
                    voiceInput.activeWakeWords.set(activeWakeWords)
                }
                val ignoredWakeWords =
                    message.activeWakeWordsList.filter { !activeWakeWords.contains(it) }
                if (ignoredWakeWords.isNotEmpty())
                    Timber.w("Ignoring wake words: $ignoredWakeWords")
            }

            is VoiceAssistantAnnounceRequest -> handleAnnouncement(
                startConversation = message.startConversation,
                mediaId = message.mediaId,
                preannounceId = message.preannounceMediaId,
                text = message.text
            )

            is VoiceAssistantEventResponse ->
                pipeline?.handleEvent(message) ?: Timber.w("No pipeline to handle event: $message")

            is VoiceAssistantTimerEventResponse -> handleTimerMessage(message)
        }
    }

    private suspend fun handleTimerMessage(event: VoiceAssistantTimerEventResponse) {
        Timber.d("Timer event: ${event.eventType}")
        val timer = timerFromEvent(event, Clock.System)
        when (event.eventType) {
            VoiceAssistantTimerEvent.VOICE_ASSISTANT_TIMER_STARTED, VoiceAssistantTimerEvent.VOICE_ASSISTANT_TIMER_UPDATED -> {
                _pendingTimers.update { it + (timer.id to timer) }
            }

            VoiceAssistantTimerEvent.VOICE_ASSISTANT_TIMER_CANCELLED -> {
                _pendingTimers.update { it - timer.id }
            }

            VoiceAssistantTimerEvent.VOICE_ASSISTANT_TIMER_FINISHED -> {
                // Remove the timer now and stash it into _ringingTimer to avoid
                // race conditions if several timers finish at the same time.
                val wasNotRinging = !isRinging
                _pendingTimers.update { it - timer.id }
                _ringingTimer.update { timer }

                if (wasNotRinging) {
                    voiceOutput.duck()
                    voiceOutput.playTimerFinishedSound { repeat ->
                        scope.launch { onTimerSoundFinished(repeat) }
                    }
                }
            }

            VoiceAssistantTimerEvent.UNRECOGNIZED -> {}
        }
    }

    private suspend fun handleAnnouncement(
        startConversation: Boolean,
        mediaId: String,
        preannounceId: String,
        text: String
    ) {
        resetState()
        announcement = Announcement(
            scope = scope,
            voiceOutput = voiceOutput,
            sendMessage = { subscription.emit(it) },
            stateChanged = { _state.value = it },
            ended = { onTtsFinished(it) }
        ).apply {
            voiceOutput.duck()
            announce(mediaId, preannounceId, startConversation, text)
        }
    }

    private suspend fun handleAudioResult(audioResult: AudioResult) {
        when (audioResult) {
            is AudioResult.Audio -> pipeline?.processMicAudio(audioResult.audio)

            is AudioResult.WakeDetected ->
                onWakeDetected(audioResult.wakeWord, audioResult.audio)

            is AudioResult.StopDetected ->
                onStopDetected()
        }
    }

    private suspend fun onWakeDetected(wakeWordPhrase: String, audio: ByteArray?) {
        // Allow using the wake word to stop the timer.
        // TODO: Should the assistant also wake?
        if (isRinging) {
            doStopTimer()
        } else if (_state.value == Responding && deviceConversation != null) {
            // The device voice plays right next to the microphone and its own speech can
            // trigger the wake word, cutting the response short
            Timber.d("Ignoring wake word while speaking the response")
        } else {
            doWakeAssistant(wakeWordPhrase, wakeAudio = audio)
        }
    }

    private suspend fun onStopDetected() {
        if (isRinging) {
            doStopTimer()
        } else {
            doStopAssistant()
        }
    }

    private suspend fun doWakeAssistant(
        wakeWordPhrase: String = "",
        isContinueConversation: Boolean = false,
        wakeAudio: ByteArray? = null
    ) {
        // Multiple wake detections from the same wake word can be triggered
        // so ensure the assistant is only woken once. Currently this is
        // achieved by creating a pipeline in the Listening state
        // on the first wake detection and checking for that here.
        if (pipeline?.state == Listening || deviceConversation?.isActive == true) return

        Timber.d("Wake assistant")
        resetState()
        wakeSample = wakeAudio
        if (!isContinueConversation) showText(question = "", answer = "")
        if (textListener != null && conversationClient != null && enableDeviceStt()) {
            startDeviceConversation(textListener, conversationClient, isContinueConversation)
            return
        }
        pipeline = createPipeline()
        if (!isContinueConversation) {
            voiceOutput.duck()
            // Listen while the wake sound plays: on a Bluetooth speaker the sound starts up to
            // a second late, and waiting for it to finish delayed listening by 1.5 s. Speech
            // recognition ignores the sound at the start of the recording.
            pipeline?.start(wakeWordPhrase)
            voiceOutput.playWakeSound()
        } else {
            pipeline?.start()
        }
    }

    /**
     * Runs a conversation turn on the device: the command is recognised by the device
     * speech recognizer and sent to Home Assistant as text, and the response is spoken
     * by the device voice. Falls back to the Home Assistant pipeline if recognition
     * doesn't work.
     */
    private fun startDeviceConversation(
        textListener: TextListener,
        conversationClient: ConversationClient,
        isContinueConversation: Boolean
    ) {
        deviceConversation = scope.launch {
            voiceOutput.duck()
            if (!isContinueConversation) {
                deviceConversationId = null
                suspendCancellableCoroutine { cont ->
                    scope.launch { voiceOutput.playWakeSound { cont.resume(Unit) } }
                }
            }
            _state.value = Listening
            // The recognizer needs the microphone that the wake word detection holds
            voiceInput.setPaused(true)
            val heard = try {
                textListener.listen()
            } finally {
                voiceInput.setPaused(false)
            }
            when (heard) {
                is ListenResult.NoSpeech -> endDeviceConversation()

                is ListenResult.Failed -> {
                    Timber.w("Device recognition failed (${heard.reason}), using the Home Assistant pipeline")
                    _state.value = Connected
                    pipeline = createPipeline()
                    pipeline?.start()
                }

                is ListenResult.Text -> respondTo(heard.text, conversationClient)
            }
        }
    }

    /**
     * Sends a text command to the agent as if it had been spoken, e.g. from a button,
     * and speaks the response with the device voice.
     */
    fun askText(text: String) {
        val conversationClient = conversationClient ?: return
        scope.launch {
            resetState()
            deviceConversationId = null
            deviceConversation = scope.launch {
                voiceOutput.duck()
                respondTo(text, conversationClient)
            }
        }
    }

    private suspend fun respondTo(text: String, conversationClient: ConversationClient) {
        showText(question = text, answer = "")
        _state.value = Processing
        val result = conversationClient.process(text, deviceConversationId)
        if (result == null) {
            _state.value = VoiceError("Home Assistant is unavailable")
            voiceOutput.playErrorSound { scope.launch { endDeviceConversation() } }
            return
        }
        deviceConversationId = result.conversationId
        showText(answer = result.speech)
        _state.value = Responding
        voiceOutput.speakText(result.speech) {
            scope.launch {
                if (result.continueConversation) {
                    Timber.d("Continuing conversation")
                    deviceConversation = null
                    doWakeAssistant(isContinueConversation = true)
                } else endDeviceConversation()
            }
        }
    }

    private fun endDeviceConversation() {
        saveWakeSample(recognised = false)
        _state.value = Connected
        voiceOutput.unDuck()
    }

    private fun showText(question: String? = null, answer: String? = null) {
        if (!question.isNullOrBlank()) saveWakeSample(recognised = true)
        _transcript.update {
            Transcript(
                question = question ?: it?.question ?: "",
                answer = answer ?: it?.answer ?: "",
                at = System.currentTimeMillis()
            )
        }
    }

    private fun createPipeline() = VoicePipeline(
        scope = scope,
        voiceOutput = voiceOutput,
        textChanged = { question, answer -> showText(question, answer) },
        sendMessage = { subscription.emit(it) },
        listeningChanged = {
            if (it) voiceOutput.duck()
            voiceInput.isStreaming = it
        },
        stateChanged = { _state.value = it },
        ended = { onTtsFinished(it) }
    )

    private suspend fun doStopAssistant(byUser: Boolean = false) {
        // Ignore the stop request if the assistant is idle or currently streaming
        // microphone audio as there's either nothing to stop or the stop word was
        // used incidentally as part of the voice command. A stop button means it.
        val state = _state.value
        if (state is Connected || (state is Listening && !byUser)) return
        Timber.d("Stop assistant")
        resetState()
        voiceOutput.unDuck()
    }

    private fun doStopTimer() {
        Timber.d("Stop timer")
        if (isRinging) {
            _ringingTimer.update { null }
            voiceOutput.stopTTS()
            voiceOutput.unDuck()
        }
    }

    /**
     * Saves the audio of the wake word that started the conversation, once it is known
     * whether any speech was recognised after it. Without speech the wake word was
     * probably false.
     */
    private fun saveWakeSample(recognised: Boolean) {
        val audio = wakeSample ?: return
        wakeSample = null
        wakeSamples?.save(audio, recognised)
    }

    private suspend fun onTtsFinished(continueConversation: Boolean) {
        Timber.d("TTS finished")
        saveWakeSample(recognised = false)
        if (continueConversation) {
            Timber.d("Continuing conversation")
            doWakeAssistant(isContinueConversation = true)
        } else {
            voiceOutput.unDuck()
        }
    }

    private suspend fun onTimerSoundFinished(repeat: Boolean) {
        delay(1000)
        if (isRinging) {
            if (repeat) {
                voiceOutput.playTimerFinishedSound {
                    scope.launch { onTimerSoundFinished(it) }
                }
            } else {
                doStopTimer()
            }
        } else {
            voiceOutput.unDuck()
        }
    }

    private suspend fun resetState(newState: EspHomeState = Connected) {
        saveWakeSample(recognised = false)
        deviceConversation?.cancel()
        deviceConversation = null
        voiceInput.setPaused(false)
        pipeline?.stop()
        pipeline = null
        announcement?.stop()
        announcement = null
        _ringingTimer.update { null }
        voiceInput.isStreaming = false
        voiceOutput.stopTTS()
        _state.value = newState
    }

    override fun close() {
        scope.cancel()
        voiceOutput.close()
    }
}