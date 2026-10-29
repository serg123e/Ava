package com.example.ava.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ava.R
import com.example.ava.esphome.android.tts.AndroidTextSpeaker
import com.example.ava.esphome.android.tts.DeviceTtsEngine
import com.example.ava.esphome.android.tts.DeviceTtsVoice
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.availableWakeWords
import com.example.ava.settings.defaultErrorSound
import com.example.ava.settings.defaultTimerFinishedSound
import com.example.ava.settings.defaultWakeSound
import com.example.ava.wakewords.models.WakeWordWithId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

@Immutable
data class MicrophoneState(
    val wakeWord: WakeWordWithId,
    val secondWakeWord: WakeWordWithId?,
    val wakeWords: List<WakeWordWithId>,
    val customWakeWordLocation: Uri?
)

/**
 * A language offered by a device text-to-speech engine,
 * keyed by its ISO 639-2 code as engines differ in how they report locales.
 */
@Immutable
data class DeviceTtsLanguage(val code: String, val name: String)

@Immutable
data class DeviceTtsState(
    val engines: List<DeviceTtsEngine>,
    val engine: DeviceTtsEngine?,
    val languages: List<DeviceTtsLanguage>,
    val language: DeviceTtsLanguage?,
    val voices: List<DeviceTtsVoice>,
    val voice: DeviceTtsVoice?
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val satelliteSettingsStore: VoiceSatelliteSettingsStore,
    private val playerSettingsStore: PlayerSettingsStore,
    private val microphoneSettingsStore: MicrophoneSettingsStore
) : ViewModel() {
    val satelliteSettingsState = satelliteSettingsStore.getFlow()

    val microphoneSettingsState = microphoneSettingsStore.getFlow().map { settings ->
        val wakeWords = settings.availableWakeWords(context)
        MicrophoneState(
            wakeWord = wakeWords.firstOrNull { wakeWord ->
                wakeWord.id == settings.wakeWord
            } ?: wakeWords.first(),
            secondWakeWord = wakeWords.firstOrNull { wakeWord ->
                wakeWord.id == settings.secondWakeWord
            },
            wakeWords = wakeWords,
            customWakeWordLocation = settings.customWakeWordLocation?.toUri()
        )
    }

    val playerSettingsState = playerSettingsStore.getFlow()

    val deviceTtsState = playerSettingsStore.getFlow()
        .map { it.deviceTtsEngine }
        .distinctUntilChanged()
        .map { AndroidTextSpeaker.catalog(context, it) }
        .combine(playerSettingsStore.getFlow()) { (engines, allVoices), settings ->
            val languages = allVoices.map { it.locale.isO3Language }.distinct()
                .map { DeviceTtsLanguage(it, languageName(it)) }
                .sortedBy { it.name }
            val language = languages.firstOrNull { it.code == settings.deviceTtsLanguage }
                ?: languages.firstOrNull { it.code == Locale.getDefault().isO3Language }
            val voices = allVoices.filter { language == null || it.locale.isO3Language == language.code }
            DeviceTtsState(
                engines = engines,
                engine = engines.firstOrNull { it.name == settings.deviceTtsEngine },
                languages = languages,
                language = language,
                voices = voices,
                voice = voices.firstOrNull { it.name == settings.deviceTtsVoice }
            )
        }

    fun saveEnableDeviceTts(enable: Boolean) = viewModelScope.launch {
        playerSettingsStore.enableDeviceTts.set(enable)
    }

    fun saveDeviceTtsEngine(engine: String) = viewModelScope.launch {
        // Voices belong to an engine, so the old voice no longer applies
        playerSettingsStore.update {
            it.copy(deviceTtsEngine = engine, deviceTtsVoice = "")
        }
    }

    fun saveDeviceTtsLanguage(language: String) = viewModelScope.launch {
        playerSettingsStore.update {
            it.copy(deviceTtsLanguage = language, deviceTtsVoice = "")
        }
    }

    fun saveDeviceTtsVoice(voice: String) = viewModelScope.launch {
        playerSettingsStore.deviceTtsVoice.set(voice)
    }

    fun saveDeviceTtsRate(ratePercent: Int?) = viewModelScope.launch {
        if (validateDeviceTtsRate(ratePercent).isNullOrBlank()) {
            playerSettingsStore.deviceTtsRatePercent.set(ratePercent!!)
        }
    }

    fun speakDeviceTtsSample() = viewModelScope.launch {
        val settings = playerSettingsStore.get()
        val sample = if (settings.deviceTtsLanguage == "rus")
            "Здравствуйте! Сейчас плюс пятнадцать и пасмурно."
        else "Hello! It is fifteen degrees and cloudy."
        AndroidTextSpeaker.speakSample(
            context,
            settings.deviceTtsEngine,
            settings.deviceTtsVoice,
            settings.deviceTtsRatePercent / 100f,
            sample
        )
    }

    fun validateDeviceTtsRate(ratePercent: Int?): String? =
        if (ratePercent == null || ratePercent < 25 || ratePercent > 300)
            context.getString(R.string.validation_device_tts_rate)
        else null

    /**
     * The display name of an ISO 639-2 language code, which [Locale] can't look up directly.
     */
    private fun languageName(iso3: String): String =
        Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language }.getOrNull() == iso3 }
            ?.getDisplayLanguage(Locale.getDefault()) ?: iso3

    fun saveName(name: String) = viewModelScope.launch {
        if (validateName(name).isNullOrBlank()) {
            satelliteSettingsStore.name.set(name)
        } else {
            Timber.w("Cannot save invalid server name: $name")
        }
    }

    fun saveServerPort(port: Int?) = viewModelScope.launch {
        if (validatePort(port).isNullOrBlank()) {
            satelliteSettingsStore.serverPort.set(port!!)
        } else {
            Timber.w("Cannot save invalid server port: $port")
        }
    }

    fun saveAutoStart(autoStart: Boolean) = viewModelScope.launch {
        satelliteSettingsStore.autoStart.set(autoStart)
    }

    fun saveTrustAllSSLCerts(trustAllSSlCerts: Boolean) = viewModelScope.launch {
        satelliteSettingsStore.trustAllSSLCerts.set(trustAllSSlCerts)
    }

    fun saveWakeWord(wakeWordId: String, availableWakeWords: List<WakeWordWithId>) =
        viewModelScope.launch {
            if (availableWakeWords.any { it.id == wakeWordId }) {
                microphoneSettingsStore.wakeWord.set(wakeWordId)
            } else {
                Timber.w("Cannot save unknown wake word: $wakeWordId")
            }
        }

    fun saveSecondWakeWord(wakeWordId: String?, availableWakeWords: List<WakeWordWithId>?) =
        viewModelScope.launch {
            if (wakeWordId == null || availableWakeWords?.any { it.id == wakeWordId } ?: false) {
                microphoneSettingsStore.secondWakeWord.set(wakeWordId)
            } else {
                Timber.w("Cannot save unknown wake word: $wakeWordId")
            }
        }

    fun saveCustomWakeWordDirectory(uri: Uri?) = viewModelScope.launch {
        if (uri != null) {
            // Get persistable permission to read from the location
            // ToDo: This should potentially handled elsewhere
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            microphoneSettingsStore.customWakeWordLocation.set(uri.toString())
        }
    }

    fun resetCustomWakeWordDirectory() = viewModelScope.launch {
        microphoneSettingsStore.customWakeWordLocation.set(null)
    }

    fun saveEnableWakeSound(enableWakeSound: Boolean) = viewModelScope.launch {
        playerSettingsStore.enableWakeSound.set(enableWakeSound)
    }

    fun saveWakeSound(uri: Uri?) = viewModelScope.launch {
        if (uri != null) {
            // Get persistable permission to read from the location
            // ToDo: This should potentially handled elsewhere
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            playerSettingsStore.wakeSound.set(uri.toString())
        }
    }

    fun resetWakeSound() = viewModelScope.launch {
        playerSettingsStore.wakeSound.set(defaultWakeSound)
    }

    fun saveTimerFinishedSound(uri: Uri?) = viewModelScope.launch {
        if (uri != null) {
            // Get persistable permission to read from the location
            // ToDo: This should potentially handled elsewhere
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            playerSettingsStore.timerFinishedSound.set(uri.toString())
        }
    }

    fun resetTimerFinishedSound() = viewModelScope.launch {
        playerSettingsStore.timerFinishedSound.set(defaultTimerFinishedSound)
    }

    fun saveRepeatTimerFinishedSound(repeatTimerFinishedSound: Boolean) = viewModelScope.launch {
        playerSettingsStore.repeatTimerFinishedSound.set(repeatTimerFinishedSound)
    }

    fun saveEnableErrorSound(enableErrorSound: Boolean) = viewModelScope.launch {
        playerSettingsStore.enableErrorSound.set(enableErrorSound)
    }

    fun saveErrorSound(uri: Uri?) = viewModelScope.launch {
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            playerSettingsStore.errorSound.set(uri.toString())
        }
    }

    fun resetErrorSound() = viewModelScope.launch {
        playerSettingsStore.errorSound.set(defaultErrorSound)
    }

    fun validateName(name: String): String? =
        if (name.isBlank())
            context.getString(R.string.validation_voice_satellite_name_empty)
        else null


    fun validatePort(port: Int?): String? =
        if (port == null || port < 1 || port > 65535)
            context.getString(R.string.validation_voice_satellite_port_invalid)
        else null
}