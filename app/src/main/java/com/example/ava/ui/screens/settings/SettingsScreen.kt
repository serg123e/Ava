package com.example.ava.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.defaultTimerFinishedSound
import com.example.ava.settings.defaultWakeSound
import com.example.ava.ui.AudioProcessing
import com.example.ava.ui.screens.BackNavigationScreen
import com.example.ava.ui.screens.settings.components.DocumentSetting
import com.example.ava.ui.screens.settings.components.DocumentTreeSetting
import com.example.ava.ui.screens.settings.components.IntSetting
import com.example.ava.ui.screens.settings.components.SectionTitle
import com.example.ava.ui.screens.settings.components.SelectSetting
import com.example.ava.ui.screens.settings.components.SettingItem
import com.example.ava.ui.screens.settings.components.SettingsList
import com.example.ava.ui.screens.settings.components.SwitchSetting
import com.example.ava.ui.screens.settings.components.TextSetting

const val HIDDEN_TOKEN = "••••••"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) = BackNavigationScreen(
    navController = navController,
    title = stringResource(R.string.label_settings),
) { innerPadding ->
    val satelliteState by viewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val microphoneState by viewModel.microphoneSettingsState.collectAsStateWithLifecycle(null)
    val playerState by viewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val deviceTtsState by viewModel.deviceTtsState.collectAsStateWithLifecycle(null)
    val defaultLabel = stringResource(R.string.label_device_tts_default)
    val networkLabel = stringResource(R.string.label_device_tts_network)
    val disabledLabel = stringResource(R.string.label_disabled)

    SettingsList(innerPadding) {
        val enabled = satelliteState != null
        item {
            TextSetting(
                name = stringResource(R.string.label_voice_satellite_name),
                value = satelliteState?.name ?: "",
                enabled = enabled,
                validation = { viewModel.validateName(it) },
                onConfirmRequest = { viewModel.saveName(it) }
            )
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_voice_satellite_autostart),
                description = stringResource(R.string.description_voice_satellite_autostart),
                value = satelliteState?.autoStart ?: false,
                enabled = enabled,
                onCheckedChange = { viewModel.saveAutoStart(it) }
            )
        }
        item {
            SelectSetting(
                name = stringResource(R.string.label_voice_satellite_first_wake_word),
                selected = microphoneState?.wakeWord,
                items = microphoneState?.wakeWords,
                enabled = enabled,
                key = { it.id },
                value = { it?.wakeWord?.wake_word ?: "" },
                onConfirmRequest = {
                    if (it != null) {
                        viewModel.saveWakeWord(it.id, microphoneState?.wakeWords ?: emptyList())
                    }
                }
            )
        }
        item {
            SelectSetting(
                name = stringResource(R.string.label_voice_satellite_second_wake_word),
                selected = microphoneState?.secondWakeWord,
                items = microphoneState?.wakeWords,
                enabled = enabled,
                key = { it.id },
                value = { it?.wakeWord?.wake_word ?: disabledLabel },
                onClearRequest = { viewModel.saveSecondWakeWord(null, null) },
                onConfirmRequest = {
                    if (it != null) {
                        viewModel.saveSecondWakeWord(it.id, microphoneState?.wakeWords)
                    }
                }
            )
        }
        item {
            DocumentTreeSetting(
                name = stringResource(R.string.label_custom_wake_words),
                description = stringResource(R.string.description_custom_wake_word_location),
                value = microphoneState?.customWakeWordLocation,
                enabled = enabled,
                onResult = {
                    if (it != null) {
                        viewModel.saveCustomWakeWordDirectory(it)
                    }
                },
                onClearRequest = { viewModel.resetCustomWakeWordDirectory() }
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            SectionTitle(stringResource(R.string.label_sounds))
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_voice_satellite_wake_sound),
                description = stringResource(R.string.description_voice_satellite_wake_sound),
                value = playerState?.enableWakeSound ?: true,
                enabled = enabled,
                onCheckedChange = { viewModel.saveEnableWakeSound(it) }
            )
        }
        item {
            DocumentSetting(
                name = stringResource(R.string.label_custom_wake_sound),
                description = stringResource(R.string.description_custom_wake_sound_location),
                value = if (playerState?.wakeSound != defaultWakeSound) playerState?.wakeSound?.toUri() else null,
                enabled = enabled,
                mimeTypes = arrayOf("audio/*"),
                onResult = {
                    if (it != null) {
                        viewModel.saveWakeSound(it)
                    }
                },
                onClearRequest = {
                    viewModel.resetWakeSound()
                }
            )
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_timer_sound_repeat),
                description = stringResource(R.string.description_timer_sound_repeat),
                value = playerState?.repeatTimerFinishedSound ?: true,
                enabled = enabled,
                onCheckedChange = { viewModel.saveRepeatTimerFinishedSound(it) }
            )
        }
        item {
            DocumentSetting(
                name = stringResource(R.string.label_custom_timer_sound),
                description = stringResource(R.string.description_custom_timer_sound_location),
                value = if (playerState?.timerFinishedSound != defaultTimerFinishedSound) playerState?.timerFinishedSound?.toUri() else null,
                enabled = enabled,
                mimeTypes = arrayOf("audio/*"),
                onResult = {
                    if (it != null) {
                        viewModel.saveTimerFinishedSound(it)
                    }
                },
                onClearRequest = { viewModel.resetTimerFinishedSound() }
            )
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_voice_satellite_error_sound),
                description = stringResource(R.string.description_voice_satellite_error_sound),
                value = playerState?.enableErrorSound ?: false,
                enabled = enabled,
                onCheckedChange = { viewModel.saveEnableErrorSound(it) }
            )
        }
        item {
            DocumentSetting(
                name = stringResource(R.string.label_custom_error_sound),
                description = stringResource(R.string.description_custom_error_sound_location),
                value = playerState?.errorSound?.toUri(),
                enabled = enabled,
                mimeTypes = arrayOf("audio/*"),
                onResult = {
                    if (it != null) {
                        viewModel.saveErrorSound(it)
                    }
                },
                onClearRequest = { viewModel.resetErrorSound() }
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            SectionTitle(stringResource(R.string.label_device_stt))
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_device_stt_enable),
                description = stringResource(R.string.description_device_stt_enable),
                value = satelliteState?.enableDeviceStt ?: false,
                enabled = enabled,
                onCheckedChange = { viewModel.saveEnableDeviceStt(it) }
            )
        }
        val sttEnabled = enabled && satelliteState?.enableDeviceStt == true
        item {
            TextSetting(
                name = stringResource(R.string.label_device_stt_language),
                description = stringResource(R.string.description_device_stt_language),
                value = satelliteState?.deviceSttLanguage ?: "",
                enabled = sttEnabled,
                onConfirmRequest = { viewModel.saveDeviceSttLanguage(it) }
            )
        }
        item {
            TextSetting(
                name = stringResource(R.string.label_home_assistant_url),
                value = satelliteState?.homeAssistantUrl ?: "",
                enabled = sttEnabled,
                onConfirmRequest = { viewModel.saveHomeAssistantUrl(it) }
            )
        }
        item {
            TextSetting(
                name = stringResource(R.string.label_home_assistant_token),
                description = stringResource(R.string.description_home_assistant_token),
                // Only show whether a token is set, it's a secret
                value = if (satelliteState?.homeAssistantToken.isNullOrBlank()) "" else HIDDEN_TOKEN,
                enabled = sttEnabled,
                onConfirmRequest = { viewModel.saveHomeAssistantToken(it) }
            )
        }
        item {
            TextSetting(
                name = stringResource(R.string.label_conversation_agent),
                description = stringResource(R.string.description_conversation_agent),
                value = satelliteState?.conversationAgent ?: "",
                enabled = sttEnabled,
                onConfirmRequest = { viewModel.saveConversationAgent(it) }
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            SectionTitle(stringResource(R.string.label_device_tts))
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_device_tts_enable),
                description = stringResource(R.string.description_device_tts_enable),
                value = playerState?.enableDeviceTts ?: false,
                enabled = enabled,
                onCheckedChange = { viewModel.saveEnableDeviceTts(it) }
            )
        }
        val ttsEnabled = enabled && playerState?.enableDeviceTts == true
        item {
            SelectSetting(
                name = stringResource(R.string.label_device_tts_engine),
                selected = deviceTtsState?.engine,
                items = deviceTtsState?.engines,
                enabled = ttsEnabled,
                key = { it.name },
                value = { it?.label ?: defaultLabel },
                onConfirmRequest = { if (it != null) viewModel.saveDeviceTtsEngine(it.name) }
            )
        }
        item {
            SelectSetting(
                name = stringResource(R.string.label_device_tts_language),
                selected = deviceTtsState?.language,
                items = deviceTtsState?.languages,
                enabled = ttsEnabled,
                key = { it.code },
                value = { it?.name ?: defaultLabel },
                onConfirmRequest = { if (it != null) viewModel.saveDeviceTtsLanguage(it.code) }
            )
        }
        item {
            SelectSetting(
                name = stringResource(R.string.label_device_tts_voice),
                selected = deviceTtsState?.voice,
                items = deviceTtsState?.voices,
                enabled = ttsEnabled,
                key = { it.name },
                value = { it?.name ?: defaultLabel },
                itemDescription = { if (it.requiresNetwork) networkLabel else null },
                onConfirmRequest = { if (it != null) viewModel.saveDeviceTtsVoice(it.name) }
            )
        }
        item {
            IntSetting(
                name = stringResource(R.string.label_device_tts_rate),
                value = playerState?.deviceTtsRatePercent,
                enabled = ttsEnabled,
                validation = { viewModel.validateDeviceTtsRate(it) },
                onConfirmRequest = { viewModel.saveDeviceTtsRate(it) }
            )
        }
        item {
            SettingItem(
                modifier = Modifier.clickable(enabled = ttsEnabled) {
                    viewModel.speakDeviceTtsSample()
                },
                name = stringResource(R.string.label_device_tts_try),
                description = stringResource(R.string.description_device_tts_try),
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            SectionTitle(stringResource(R.string.label_advanced))
        }
        item {
            SettingItem(
                modifier = Modifier.clickable {
                    navController.navigate(AudioProcessing)
                },
                name = stringResource(R.string.label_audio_processing),
                description = stringResource(R.string.description_audio_processing),
            )
        }
        item {
            IntSetting(
                name = stringResource(R.string.label_voice_satellite_port),
                value = satelliteState?.serverPort,
                enabled = enabled,
                validation = { viewModel.validatePort(it) },
                onConfirmRequest = { viewModel.saveServerPort(it) }
            )
        }
        item {
            SwitchSetting(
                name = stringResource(R.string.label_trust_all_ssl_certs),
                description = stringResource(R.string.description_trust_all_ssl_certs),
                value = satelliteState?.trustAllSSLCerts ?: false,
                enabled = enabled,
                onCheckedChange = { viewModel.saveTrustAllSSLCerts(it) }
            )
        }
    }
}