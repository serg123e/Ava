package com.example.ava.esphome.android.wakeword

import com.example.ava.esphome.voiceassistant.WakeSamples
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val SAMPLE_RATE = 16000
private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

/**
 * Saves wake word audio to WAV files in [directory]: true_<time>.wav when speech was
 * recognised after the wake word, false_<time>.wav when it probably was false.
 */
class WavWakeSamples(private val directory: File) : WakeSamples {
    override fun save(audio: ByteArray, recognised: Boolean) {
        val name = "${if (recognised) "true" else "false"}_${LocalDateTime.now().format(NAME_FORMAT)}.wav"
        runCatching {
            directory.mkdirs()
            File(directory, name).outputStream().use {
                it.write(wavHeader(audio.size))
                it.write(audio)
            }
        }.onSuccess { Timber.d("Saved wake word sample $name") }
            .onFailure { Timber.e(it, "Error saving wake word sample $name") }
    }

    private fun wavHeader(dataSize: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + dataSize)
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16)
            .putShort(1)  // PCM
            .putShort(1)  // mono
            .putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2)  // bytes per second
            .putShort(2)  // bytes per frame
            .putShort(16)  // bits per sample
            .put("data".toByteArray()).putInt(dataSize)
            .array()
}
