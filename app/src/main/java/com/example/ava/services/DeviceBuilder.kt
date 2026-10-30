package com.example.ava.services

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
import androidx.media3.common.C.USAGE_ASSISTANT
import androidx.media3.common.C.USAGE_MEDIA
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.android.logger.TimberLogger
import com.example.ava.esphome.android.mediaplayer.media3MediaPlayer
import com.example.ava.esphome.android.stt.AndroidTextListener
import com.example.ava.esphome.android.tts.AndroidTextSpeaker
import com.example.ava.esphome.android.microphone.audioRecordMicrophoneFlow
import com.example.ava.esphome.android.wakeword.MicroWakeWord
import com.example.ava.esphome.android.wakeword.WavWakeSamples
import com.example.ava.esphome.entities.MediaPlayerEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.esphome.voiceassistant.VoiceAssistant
import com.example.ava.esphome.voiceassistant.VoiceInputImpl
import com.example.ava.esphome.voiceassistant.VoiceOutputImpl
import com.example.ava.homeassistant.HomeAssistantConversationClient
import com.example.ava.server.ServerImpl
import com.example.ava.settings.AudioProcessingSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.availableStopWords
import com.example.ava.settings.availableWakeWords
import com.example.esphomeproto.api.VoiceAssistantFeature
import com.example.esphomeproto.api.deviceInfoResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class DeviceBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val satelliteSettingsStore: VoiceSatelliteSettingsStore,
    private val microphoneSettingsStore: MicrophoneSettingsStore,
    private val audioProcessingSettingsStore: AudioProcessingSettingsStore,
    private val playerSettingsStore: PlayerSettingsStore
) {
    suspend fun buildVoiceSatellite(coroutineContext: CoroutineContext): EspHomeDevice {
        val satelliteSettings = satelliteSettingsStore.get()
        // Need a reference to voiceOutput as it needs to be passed to
        // both the VoiceAssistant and MediaPlayerEntity
        val voiceOutput = playerSettingsStore.toVoiceOutput()
        return EspHomeDevice(
            coroutineContext = coroutineContext,
            port = satelliteSettings.serverPort,
            server = ServerImpl(),
            deviceInfo = deviceInfoResponse {
                name = satelliteSettings.name
                macAddress = satelliteSettings.macAddress
                voiceAssistantFeatureFlags = VoiceAssistantFeature.VOICE_ASSISTANT.flag or
                        VoiceAssistantFeature.API_AUDIO.flag or
                        VoiceAssistantFeature.TIMERS.flag or
                        VoiceAssistantFeature.ANNOUNCE.flag or
                        VoiceAssistantFeature.START_CONVERSATION.flag
            },
            voiceAssistant = VoiceAssistant(
                coroutineContext = coroutineContext,
                voiceInput = microphoneSettingsStore.toVoiceInput(),
                voiceOutput = voiceOutput,
                textListener = AndroidTextListener(
                    context = context,
                    language = { satelliteSettingsStore.deviceSttLanguage.get() }
                ),
                conversationClient = HomeAssistantConversationClient(
                    url = { satelliteSettingsStore.homeAssistantUrl.get() },
                    token = { satelliteSettingsStore.homeAssistantToken.get() },
                    agentId = { satelliteSettingsStore.conversationAgent.get() },
                    macAddress = { satelliteSettings.macAddress }
                ),
                // Needs Home Assistant's address and a token to send the recognised text
                enableDeviceStt = {
                    val settings = satelliteSettingsStore.get()
                    settings.enableDeviceStt && settings.homeAssistantUrl.isNotBlank() &&
                            settings.homeAssistantToken.isNotBlank()
                },
                // Collected with adb pull for training the wake word model, see
                // ha/wakeword in the anfisa repository
                wakeSamples = WavWakeSamples(File(context.getExternalFilesDir(null), "wake-samples"))
            ),
            logger = TimberLogger(),
            entities = listOf(
                MediaPlayerEntity(
                    key = 0,
                    name = "Media Player",
                    objectId = "media_player",
                    mediaPlayer = voiceOutput,
                    getVolumeState = playerSettingsStore.volume,
                    setVolume = { playerSettingsStore.volume.set(it) },
                    getMutedState = playerSettingsStore.muted,
                    setMuted = { playerSettingsStore.muted.set(it) }
                ),
                SwitchEntity(
                    key = 1,
                    name = "Mute Microphone",
                    objectId = "mute_microphone",
                    getState = microphoneSettingsStore.muted
                ) { microphoneSettingsStore.muted.set(it) },
                SwitchEntity(
                    key = 2,
                    name = "Enable Wake Sound",
                    objectId = "enable_wake_sound",
                    getState = playerSettingsStore.enableWakeSound
                ) { playerSettingsStore.enableWakeSound.set(it) },
                SwitchEntity(
                    key = 3,
                    name = "Repeat Timer Sound",
                    objectId = "repeat_timer_sound",
                    getState = playerSettingsStore.repeatTimerFinishedSound
                ) { playerSettingsStore.repeatTimerFinishedSound.set(it) }
            )
        )
    }

    private fun MicrophoneSettingsStore.toVoiceInput() = VoiceInputImpl(
        microphone = audioProcessingSettingsStore.toMicrophone(),
        wakeWord = MicroWakeWord(),
        availableWakeWords = { get().availableWakeWords(context) },
        availableStopWords = { get().availableStopWords(context) },
        activeWakeWords = activeWakeWords,
        activeStopWords = activeStopWords,
        muted = muted,
        secondChannelWakeWord = MicroWakeWord()
    )

    private fun AudioProcessingSettingsStore.toMicrophone() = audioRecordMicrophoneFlow(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        audioSource = audioSource,
        audioMode = audioMode,
        useSpeakerphone = speakerphone,
        twoMicrophones = twoMicrophones
    )

    private suspend fun PlayerSettingsStore.toVoiceOutput(): VoiceOutputImpl {
        val playerSettings = get()
        return VoiceOutputImpl(
            ttsPlayer = context.media3MediaPlayer(
                USAGE_ASSISTANT,
                AUDIO_CONTENT_TYPE_SPEECH,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

            ),
            mediaPlayer = context.media3MediaPlayer(
                USAGE_MEDIA,
                AUDIO_CONTENT_TYPE_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ),
            enableWakeSound = { enableWakeSound.get() },
            wakeSound = { wakeSound.get() },
            warmUpSound = { if (bluetoothOutputAsleep()) "asset:///sounds/silence.flac" else null },
            timerFinishedSound = { timerFinishedSound.get() },
            repeatTimerFinishedSound = { repeatTimerFinishedSound.get() },
            enableErrorSound = { enableErrorSound.get() },
            errorSound = { errorSound.get() },
            textSpeaker = AndroidTextSpeaker(
                context = context,
                engine = { deviceTtsEngine.get() },
                voice = { deviceTtsVoice.get() },
                rate = { deviceTtsRatePercent.get() / 100f }
            ),
            enableTextSpeaker = { enableDeviceTts.get() },
            volume = playerSettings.volume,
            muted = playerSettings.muted,
            // Music from the same phone, inches from the microphone, garbles recognition
            // ("turn off the radio" heard as "turn off the computer"), so duck it hard
            duckMultiplier = 0.1f,
        )
    }

    /**
     * Whether audio goes to a Bluetooth speaker that has gone quiet: Android suspends the
     * stream a few seconds after the last sound, and resuming it takes about a second.
     */
    private fun bluetoothOutputAsleep(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return !audioManager.isMusicActive && audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }
}