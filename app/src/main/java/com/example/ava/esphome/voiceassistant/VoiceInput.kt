package com.example.ava.esphome.voiceassistant

import com.example.ava.esphome.microphone.Microphone
import com.example.ava.esphome.wakeword.WakeWord
import com.example.ava.settings.SettingState
import com.example.ava.wakewords.models.WakeWordWithId
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

// Both channels hear the same word, usually a few milliseconds apart; report it once
private const val REPEAT_DETECTION_BYTES = 16000 * 2 * 3 / 2  // 1.5 s of 16 kHz 16-bit audio

// The audio kept before a wake word detection, long enough to hold the whole word
private const val WAKE_SAMPLE_BYTES = 16000 * 2 * 5 / 2  // 2.5 s

sealed class AudioResult {
    data class Audio(val audio: ByteString) : AudioResult()
    /**
     * [audio] is the 16 kHz 16-bit mono audio that the wake word was heard in.
     */
    class WakeDetected(val wakeWord: String, val audio: ByteArray? = null) : AudioResult()
    class StopDetected() : AudioResult()
}

interface VoiceInput {
    /**
     * The list of wake words available for selection.
     */
    suspend fun getAvailableWakeWords(): List<WakeWordWithId>

    /**
     * The list of stop words available for selection.
     */
    suspend fun getAvailableStopWords(): List<WakeWordWithId>

    /**
     * The list of currently active wake words.
     */
    val activeWakeWords: SettingState<List<String>>

    /**
     * The list of currently active stop words.
     */
    val activeStopWords: SettingState<List<String>>

    /**
     * Whether the microphone is muted.
     */
    val muted: SettingState<Boolean>

    /**
     * Whether the microphone is currently streaming audio.
     */
    var isStreaming: Boolean

    /**
     * Starts listening to audio from the microphone for wake word detection and streaming.
     * If an active wake word or stop word is detected, emits [AudioResult.WakeDetected] or
     * [AudioResult.StopDetected] respectively. If [isStreaming] is true, [AudioResult.Audio] is
     * also emitted with the raw audio data.
     */
    fun start(): Flow<AudioResult>

    /**
     * Releases the microphone while [paused] is true, e.g. so that the device
     * speech recognizer can use it.
     */
    fun setPaused(paused: Boolean) {}
}

class VoiceInputImpl(
    private val microphone: Flow<Microphone>,
    private val wakeWord: WakeWord,
    private val availableWakeWords: suspend () -> List<WakeWordWithId>,
    private val availableStopWords: suspend () -> List<WakeWordWithId>,
    override val activeWakeWords: SettingState<List<String>>,
    override val activeStopWords: SettingState<List<String>>,
    override val muted: SettingState<Boolean>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Listens on the second channel of a two-channel microphone. The audio sent to
     * Home Assistant is then taken from the channel that last heard the wake word.
     */
    private val secondChannelWakeWord: WakeWord? = null
) : VoiceInput {
    private data class InputConfig(
        val microphone: Microphone,
        val activeWakeWords: Map<String, WakeWordWithId>,
        val activeStopWords: Map<String, WakeWordWithId>
    )

    private val _isStreaming = AtomicBoolean(false)
    override var isStreaming: Boolean
        get() = _isStreaming.get()
        set(value) = _isStreaming.set(value)

    private val paused = MutableStateFlow(false)

    override fun setPaused(paused: Boolean) {
        this.paused.value = paused
    }

    override suspend fun getAvailableWakeWords() = availableWakeWords()
    override suspend fun getAvailableStopWords() = availableStopWords()

    private val currentInput = combine(
        microphone,
        activeWakeWords,
        activeStopWords
    ) { microphone, activeWakeWords, activeStopWords ->
        InputConfig(
            microphone = microphone,
            activeWakeWords = getAvailableWakeWords().filter { it.id in activeWakeWords }
                .associateBy { it.id },
            activeStopWords = getAvailableStopWords().filter { it.id in activeStopWords }
                .associateBy { it.id }
        )
    }

    override fun start() = combine(muted, paused) { muted, paused -> muted || paused }.flatMapLatest { off ->
        if (off) emptyFlow()
        else currentInput.flatMapLatest { (microphone, wakeWords, stopWords) ->
            val detectors = listOfNotNull(
                wakeWord,
                secondChannelWakeWord?.takeIf { microphone.channels == 2 }
            )
            channelFlow {
                val recentAudio = detectors.map { RecentAudio(WAKE_SAMPLE_BYTES) }
                var channel = 0
                var sinceDetection = REPEAT_DETECTION_BYTES
                while (isActive) {
                    val audio = microphone.read()
                    val channels = if (microphone.channels == 2) audio.splitChannels() else listOf(audio)
                    if (isStreaming) {
                        val streamed = channels[channel.coerceAtMost(channels.lastIndex)]
                        send(AudioResult.Audio(ByteString.copyFrom(streamed)))
                        streamed.rewind()
                    }
                    sinceDetection += channels[0].remaining()
                    channels.zip(recentAudio).forEach { (audio, recent) -> recent.add(audio) }
                    // Always run audio through the models, even if not currently streaming, to keep
                    // their internal state up to date
                    detectors.forEachIndexed { i, detector ->
                        detector.detect(channels[i]).forEach {
                            if (detectors.size > 1) {
                                if (sinceDetection < REPEAT_DETECTION_BYTES) return@forEach
                                sinceDetection = 0
                                channel = i
                            }
                            wakeWords[it]?.let { detected ->
                                send(AudioResult.WakeDetected(detected.wakeWord.wake_word, recentAudio[i].get()))
                            } ?: stopWords[it]?.let {
                                send(AudioResult.StopDetected())
                            }
                        }
                    }
                }
            }.onStart {
                microphone.start()
                detectors.forEach { it.setWakeWords(wakeWords.values.toList() + stopWords.values.toList()) }
            }.onCompletion {
                microphone.stop()
                detectors.forEach { it.clearWakeWords() }
            }.flowOn(dispatcher)
        }
    }
}
/**
 * The last [size] bytes of audio.
 */
private class RecentAudio(size: Int) {
    private val buffer = ByteArray(size)
    private var end = 0
    private var full = false

    fun add(audio: ByteBuffer) {
        val source = audio.duplicate()
        while (source.hasRemaining()) {
            val count = minOf(source.remaining(), buffer.size - end)
            source.get(buffer, end, count)
            end += count
            if (end == buffer.size) {
                end = 0
                full = true
            }
        }
    }

    fun get(): ByteArray =
        if (full) buffer.copyOfRange(end, buffer.size) + buffer.copyOfRange(0, end)
        else buffer.copyOf(end)
}

/**
 * Splits interleaved two-channel 16-bit audio into a buffer per channel.
 */
private fun ByteBuffer.splitChannels(): List<ByteBuffer> {
    val frames = remaining() / 4
    val left = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    val right = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) {
        left.put(get()).put(get())
        right.put(get()).put(get())
    }
    left.flip()
    right.flip()
    return listOf(left, right)
}
