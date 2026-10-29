package com.example.ava.ui.services

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.ava.esphome.voiceassistant.VoiceError
import com.example.ava.tasker.conversingStates
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.esphome.voiceassistant.Processing
import com.example.ava.permissions.VOICE_SATELLITE_PERMISSIONS
import com.example.ava.utils.translate
import kotlinx.coroutines.flow.flowOf

/**
 * The satellite status and a large button to talk to the assistant without the wake word.
 */
@Composable
fun VoiceSatelliteStatus() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SatelliteStatusText()
        SatelliteTalkButton()
    }
}

/**
 * The state of the voice satellite, e.g. Idle or Listening.
 */
@Composable
fun SatelliteStatusText(modifier: Modifier = Modifier, viewModel: ServiceViewModel = hiltViewModel()) {
    val service by viewModel.satellite.collectAsStateWithLifecycle(null)
    val currentService = service
    if (currentService == null) {
        StatusText(
            text = "Service disconnected",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier
        )
        return
    }
    val serviceState by currentService.voiceSatelliteState.collectAsStateWithLifecycle(Stopped)
    val resources = LocalResources.current
    StatusText(
        text = remember(serviceState) { serviceState.translate(resources) },
        color = stateColor(serviceState),
        modifier = modifier
    )
}

/**
 * What a talk button shows and does: start a conversation without the wake word,
 * or stop the current one.
 */
@Immutable
data class TalkState(val conversing: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
fun talkState(viewModel: ServiceViewModel = hiltViewModel()): TalkState? {
    val service by viewModel.satellite.collectAsStateWithLifecycle(null)
    val currentService = service ?: return null
    val serviceState by currentService.voiceSatelliteState.collectAsStateWithLifecycle(Stopped)
    val conversing = serviceState in conversingStates
    return TalkState(
        conversing = conversing,
        enabled = conversing || serviceState is Connected || serviceState is VoiceError,
        onClick = {
            if (conversing) currentService.stopAssistant()
            else currentService.wakeAssistant()
        }
    )
}

/**
 * Starts a conversation without the wake word, or stops the current one.
 */
@Composable
fun SatelliteTalkButton(modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val state = talkState() ?: return
    TalkButton(
        conversing = state.conversing,
        enabled = state.enabled,
        size = size,
        modifier = modifier,
        onClick = state.onClick
    )
}

@Composable
private fun TalkButton(
    conversing: Boolean,
    enabled: Boolean,
    size: Dp,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (conversing) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            painter = painterResource(if (conversing) R.drawable.close_24px else R.drawable.mic_24px),
            contentDescription = stringResource(
                if (conversing) R.string.label_stop_talking else R.string.label_talk
            ),
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

/**
 * Turns the voice satellite service on and off, for the corner of the app bar.
 */
@Composable
fun VoiceSatelliteSwitch(viewModel: ServiceViewModel = hiltViewModel()) {
    val service by viewModel.satellite.collectAsStateWithLifecycle(null)
    val currentService = service ?: return
    val serviceState by currentService.voiceSatelliteState.collectAsStateWithLifecycle(Stopped)
    val registerPermissionsResult = rememberLaunchWithMultiplePermissions(
        onPermissionGranted = { currentService.startVoiceSatellite() }
    )
    val description = stringResource(R.string.label_service_switch)

    Switch(
        checked = serviceState !is Stopped,
        onCheckedChange = { on ->
            if (on) registerPermissionsResult.launch(VOICE_SATELLITE_PERMISSIONS)
            else currentService.stopVoiceSatellite()
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = description }
    )
}

@Composable
private fun StatusText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun stateColor(state: EspHomeState) = when (state) {
    is Stopped, is Disconnected, is ServerError -> MaterialTheme.colorScheme.error
    is Connected -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun rememberLaunchWithMultiplePermissions(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: (deniedPermissions: Array<String>) -> Unit = { }
): ManagedActivityResultLauncher<Array<String>, Map<String, @JvmSuppressWildcards Boolean>> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val deniedPermissions = granted.filter { !it.value }.keys.toTypedArray()
        if (deniedPermissions.isEmpty()) {
            onPermissionGranted()
        } else {
            onPermissionDenied(deniedPermissions)
        }
    }
}
/**
 * Like the LED ring on the Voice PE: a light runs around the edge of the screen
 * while the assistant is thinking about a response.
 */
@Composable
fun ThinkingFrame(viewModel: ServiceViewModel = hiltViewModel()) {
    val service by viewModel.satellite.collectAsStateWithLifecycle(null)
    val stateFlow = remember(service) { service?.voiceSatelliteState ?: flowOf(Stopped) }
    val state by stateFlow.collectAsStateWithLifecycle(Stopped)
    if (state != Processing) return

    val color = MaterialTheme.colorScheme.primary
    val angle by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "angle"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = 8.dp.toPx()
        val shader = SweepGradientShader(
            center = center,
            colors = listOf(Color.Transparent, color.copy(alpha = 0.3f), color, Color.Transparent),
            colorStops = listOf(0f, 0.15f, 0.3f, 0.31f)
        )
        shader.setLocalMatrix(Matrix().apply { setRotate(angle, center.x, center.y) })
        drawRoundRect(
            brush = ShaderBrush(shader),
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(24.dp.toPx()),
            style = Stroke(width)
        )
    }
}
