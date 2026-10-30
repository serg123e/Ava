package com.example.ava

import com.example.ava.esphome.microphone.Microphone
import com.example.ava.esphome.voiceassistant.AudioResult
import com.example.ava.esphome.voiceassistant.VoiceInputImpl
import com.example.ava.esphome.wakeword.WakeWord
import com.example.ava.settings.SettingState
import com.example.ava.stubs.StubMicrophone
import com.example.ava.stubs.StubWakeWord
import com.example.ava.stubs.stubSettingState
import com.example.ava.stubs.stubWakeWordWithId
import com.example.ava.wakewords.models.WakeWordWithId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceInputTest {
    fun createVoiceInput(
        microphone: Microphone = StubMicrophone(),
        wakeWord: WakeWord = StubWakeWord(),
        availableWakeWords: List<WakeWordWithId> = emptyList(),
        availableStopWords: List<WakeWordWithId> = emptyList(),
        activeWakeWords: List<String> = availableWakeWords.map { it.id },
        activeStopWords: List<String> = availableStopWords.map { it.id },
        muted: SettingState<Boolean> = stubSettingState(false),
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        secondChannelWakeWord: WakeWord? = null
    ) = VoiceInputImpl(
        microphone = flowOf(microphone),
        wakeWord = wakeWord,
        availableWakeWords = { availableWakeWords },
        availableStopWords = { availableStopWords },
        activeWakeWords = stubSettingState(activeWakeWords),
        activeStopWords = stubSettingState(activeStopWords),
        muted = muted,
        dispatcher = dispatcher,
        secondChannelWakeWord = secondChannelWakeWord
    )

    // Interleaved samples: left channel 1, 2; right channel 10, 20
    private fun stereoMicrophone() = object : StubMicrophone() {
        override val channels = 2
        override fun read(): ByteBuffer {
            super.read()
            return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(1).putShort(10).putShort(2).putShort(20).flip() as ByteBuffer
        }
    }

    private fun ByteBuffer.samples() = order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        .let { buffer -> List(buffer.remaining()) { buffer.get(it) } }

    @Test
    fun should_close_microphone_when_flow_cancelled() = runTest {
        val started = CompletableDeferred<Unit>()
        var microphoneStopped = false
        val microphone = object : StubMicrophone() {
            override fun start() {
                started.complete(Unit)
            }

            override fun stop() {
                microphoneStopped = true
            }
        }
        val voiceInput = createVoiceInput(
            microphone = microphone
        )

        val job = voiceInput.start().launchIn(this)
        started.await()
        job.cancelAndJoin()

        assertTrue(microphoneStopped)
    }

    @Test
    fun should_clear_wake_words_when_flow_cancelled() = runTest {
        val started = CompletableDeferred<Unit>()
        var wakeWordsCleared = false
        val voiceInput = createVoiceInput(
            wakeWord = object : StubWakeWord() {
                override suspend fun setWakeWords(wakeWords: List<WakeWordWithId>) {
                    started.complete(Unit)
                    super.setWakeWords(wakeWords)
                }

                override fun clearWakeWords() {
                    wakeWordsCleared = true
                }
            }
        )

        val job = voiceInput.start().launchIn(this)
        started.await()
        job.cancelAndJoin()

        assertTrue(wakeWordsCleared)
    }

    @Test
    fun should_stop_microphone_and_clear_wake_words_when_muted() = runTest {
        val started = CompletableDeferred<Unit>()
        val microphoneStopped = CompletableDeferred<Unit>()
        val wakeWordsCleared = CompletableDeferred<Unit>()
        val voiceInput = createVoiceInput(
            microphone = object : StubMicrophone() {
                override fun start() {
                    started.complete(Unit)
                }

                override fun stop() {
                    microphoneStopped.complete(Unit)
                }
            },
            wakeWord = object : StubWakeWord() {
                override fun clearWakeWords() {
                    wakeWordsCleared.complete(Unit)
                }
            }
        )

        voiceInput.start().launchIn(backgroundScope)
        started.await()
        voiceInput.muted.set(true)

        microphoneStopped.await()
        wakeWordsCleared.await()
    }

    @Test
    fun should_emit_wake_detected_for_wake_word() = runTest {
        val wakeWords = listOf(
            stubWakeWordWithId(id = "wake1", wakeWord = "wake1"),
            stubWakeWordWithId(id = "wake2", wakeWord = "wake2")
        )
        val stopWords = listOf(stubWakeWordWithId(id = "stop", wakeWord = "stop"))
        val voiceInput = createVoiceInput(
            wakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> =
                    listOf(wakeWords[0]).map { it.id }
            },
            availableWakeWords = wakeWords,
            availableStopWords = stopWords
        )

        val result = voiceInput.start().first()
        assertEquals(
            (result as AudioResult.WakeDetected).wakeWord,
            wakeWords[0].wakeWord.wake_word
        )
    }

    @Test
    fun should_emit_stop_detected_for_stop_word() = runTest {
        val wakeWords = listOf(stubWakeWordWithId(id = "wake", wakeWord = "wake"))
        val stopWords = listOf(stubWakeWordWithId(id = "stop", wakeWord = "stop"))
        val voiceInput = createVoiceInput(
            wakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> = stopWords.map { it.id }
            },
            availableWakeWords = wakeWords,
            availableStopWords = stopWords
        )

        val result = voiceInput.start().first()
        assert(result is AudioResult.StopDetected)
    }

    @Test
    fun should_listen_for_the_wake_word_on_each_channel_of_two_microphones() = runTest {
        val wakeWords = listOf(stubWakeWordWithId(id = "wake", wakeWord = "wake"))
        val heard = CopyOnWriteArrayList<List<Short>>()
        val voiceInput = createVoiceInput(
            microphone = stereoMicrophone(),
            wakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> {
                    heard += audio.samples()
                    return emptyList()
                }
            },
            secondChannelWakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> {
                    heard += audio.samples()
                    return wakeWords.map { it.id }
                }
            },
            availableWakeWords = wakeWords
        )

        val result = voiceInput.start().first()
        assertEquals("wake", (result as AudioResult.WakeDetected).wakeWord)
        assertEquals(listOf(listOf<Short>(1, 2), listOf<Short>(10, 20)), heard.take(2))
    }

    @Test
    fun should_attach_the_audio_of_the_channel_that_heard_the_wake_word() = runTest {
        val wakeWords = listOf(stubWakeWordWithId(id = "wake", wakeWord = "wake"))
        var reads = 0
        val voiceInput = createVoiceInput(
            microphone = stereoMicrophone(),
            wakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> = emptyList()
            },
            secondChannelWakeWord = object : StubWakeWord() {
                override fun detect(audio: ByteBuffer): List<String> =
                    if (++reads == 2) wakeWords.map { it.id } else emptyList()
            },
            availableWakeWords = wakeWords
        )

        val result = voiceInput.start().first() as AudioResult.WakeDetected
        val audio = ByteBuffer.wrap(result.audio!!).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(10, 20, 10, 20), audio.samples())
    }

    @Test
    fun should_report_a_word_heard_by_both_microphones_once_and_stream_the_channel_that_heard_it() = runTest {
        val wakeWords = listOf(stubWakeWordWithId(id = "wake", wakeWord = "wake"))
        val voiceInput = createVoiceInput(
            microphone = stereoMicrophone(),
            wakeWord = StubWakeWord(),
            secondChannelWakeWord = StubWakeWord(),
            availableWakeWords = wakeWords
        )
        voiceInput.isStreaming = true

        val results = voiceInput.start().take(4).toList()
        assertEquals(1, results.count { it is AudioResult.WakeDetected })
        val audio = results.filterIsInstance<AudioResult.Audio>().last().audio
        assertEquals(listOf<Short>(1, 2), audio.asReadOnlyByteBuffer().samples())
    }
}
