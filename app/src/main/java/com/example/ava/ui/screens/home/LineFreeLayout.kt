package com.example.ava.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ava.R
import com.example.ava.esphome.Connected
import com.example.ava.esphome.Disconnected
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.ServerError
import com.example.ava.esphome.Stopped
import com.example.ava.esphome.voiceassistant.Listening
import com.example.ava.esphome.voiceassistant.Processing
import com.example.ava.esphome.voiceassistant.Responding
import com.example.ava.esphome.voiceassistant.VoiceError
import com.example.ava.permissions.VOICE_SATELLITE_PERMISSIONS
import com.example.ava.tasker.conversingStates
import com.example.ava.ui.screens.settings.SettingsViewModel
import com.example.ava.ui.services.ServiceViewModel
import com.example.ava.ui.services.components.timerState
import com.example.ava.ui.services.rememberLaunchWithMultiplePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

// The display of the phone this build runs on has green lines across it, in landscape at
// rows ≈ 325, 678, 925, 988, 1300 and 1363 of its 3088×1440 physical pixels. retro_panel is
// the artwork stretched band by band so that its drawn lines lie on those rows, and the live
// elements below fill its empty panels. Positions are in physical pixels of that grid and
// are scaled to the screen, so they stay on the lines whatever the resolution or zoom.
private const val SCREEN_W = 3088f
private const val SCREEN_H = 1440f

private val phosphor = Color(0xFF66FF8C)
private val phosphorDim = Color(0xFF2E8F4A)
private val amber = Color(0xFFFFB000)

/**
 * A rectangle of the artwork, in physical pixels.
 */
private data class Area(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

private val switchArea = Area(116f, 147f, 726f, 290f)
private val switchLabelArea = Area(347f, 150f, 718f, 285f)
private val settingsArea = Area(2779f, 131f, 3003f, 309f)
private val statusArea = Area(1330f, 375f, 2280f, 640f)
private val stagesArea = Area(2300f, 375f, 2880f, 640f)
private val micArea = Area(1366f, 999f, 1718f, 1292f)
private val systemArea = Area(130f, 1030f, 900f, 1270f)
private val clockArea = Area(2500f, 1030f, 2980f, 1270f)
private val footerArea = Area(900f, 1312f, 2200f, 1352f)
// What was said, on both sides of the microphone, over the system panel and the clock
private val questionArea = Area(93f, 1005f, 1350f, 1285f)
private val answerArea = Area(1735f, 1005f, 2900f, 1285f)
private const val TRANSCRIPT_SHOWN_MS = 20_000L

/**
 * A tile of the button row: what it says and what it asks Anfisa.
 * A null question starts a voice conversation instead.
 */
private data class Tile(val area: Area, val title: String, val subtitle: String, val question: String?)

private val tiles = listOf(
    Tile(Area(80f, 710f, 795f, 899f), "ЧАТ", "ЗАДАТЬ ВОПРОС", null),
    Tile(Area(831f, 710f, 1524f, 899f), "ИДЕИ", "ЧЕМ ЗАНЯТЬСЯ", "Предложи, чем мне заняться сегодня"),
    Tile(Area(1563f, 710f, 2254f, 899f), "ЗНАНИЯ", "ИНТЕРЕСНОЕ", "Расскажи что-нибудь интересное"),
    Tile(Area(2294f, 710f, 3008f, 899f), "ЗАДАЧИ", "НАПОМИНАНИЯ", "Какие у меня напоминания?"),
)

/**
 * The home screen in landscape: a Soviet retro-futurist control panel laid out around
 * the lines of a damaged display.
 */
@Composable
fun RetroHome(
    onSettings: () -> Unit,
    serviceViewModel: ServiceViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val service by serviceViewModel.satellite.collectAsStateWithLifecycle(null)
    val stateFlow = remember(service) { service?.voiceSatelliteState ?: flowOf(Stopped) }
    val state by stateFlow.collectAsStateWithLifecycle(Stopped)
    val satellite by settingsViewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val player by settingsViewModel.playerSettingsState.collectAsStateWithLifecycle(null)
    val size = LocalWindowInfo.current.containerSize
    val sx = size.width / SCREEN_W
    val sy = size.height / SCREEN_H
    val density = LocalDensity.current
    // Text sized in physical pixels of the artwork, like everything else
    fun px(rows: Float): TextUnit = with(density) { (rows * sy).toSp() }
    fun mono(rows: Float, color: Color = phosphor, bold: Boolean = false) = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = px(rows),
        lineHeight = px(rows * 1.25f),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(R.drawable.retro_panel),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        // The switch is drawn on; dim it while the satellite is stopped
        val on = state !is Stopped
        val launch = rememberLaunchWithMultiplePermissions(onPermissionGranted = { service?.startVoiceSatellite() })
        Box(
            Modifier
                .at(switchArea, sx, sy)
                .semantics { contentDescription = "Voice satellite on/off" }
                .clickable(role = Role.Switch) {
                    if (on) service?.stopVoiceSatellite() else launch.launch(VOICE_SATELLITE_PERMISSIONS)
                }
                .background(if (on) Color.Transparent else Color.Black.copy(alpha = 0.6f))
        )
        Text(
            if (on) "РЕЖИМ\nПРОСЛУШИВАНИЯ" else "ВЫКЛЮЧЕНА",
            style = mono(34f, if (on) phosphor else amber),
            modifier = Modifier.at(switchLabelArea, sx, sy).padding(start = with(density) { (40 * sx).toDp() })
                .wrapCenterVertically()
        )
        Box(
            Modifier
                .at(settingsArea, sx, sy)
                .semantics { contentDescription = "Settings" }
                .clickable(role = Role.Button, onClick = onSettings)
        )

        // Status display and the stages of a conversation
        Column(Modifier.at(statusArea, sx, sy), verticalArrangement = Arrangement.Center) {
            Text(statusTitle(state), style = mono(110f, if (state.isProblem()) amber else phosphor, bold = true))
            statusDetail(state)?.let { Text(it, style = mono(40f, phosphorDim)) }
        }
        Column(Modifier.at(stagesArea, sx, sy), verticalArrangement = Arrangement.Center) {
            val stage = when (state) {
                Listening -> 0
                Processing -> 1
                Responding -> 2
                else -> -1
            }
            listOf("РАСПОЗНАВАНИЕ РЕЧИ", "ОБДУМЫВАНИЕ", "ОТВЕТ ГОЛОСОМ").forEachIndexed { i, text ->
                Text(
                    (if (i == stage) "▶ " else "├ ") + text + if (i == stage) " …" else "",
                    style = mono(40f, if (i == stage) phosphor else phosphorDim, bold = i == stage)
                )
            }
        }

        // Button row
        val canTalk = state is Connected || state is VoiceError || state in conversingStates
        tiles.forEach { tile ->
            Box(
                Modifier
                    .at(tile.area, sx, sy)
                    .semantics { contentDescription = tile.title }
                    .clickable(enabled = canTalk, role = Role.Button) {
                        val currentService = service ?: return@clickable
                        if (tile.question == null) currentService.wakeAssistant()
                        else currentService.askText(tile.question)
                    }
            ) {
                Column(
                    Modifier.fillMaxSize().padding(start = with(density) { (260 * sx).toDp() }),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(tile.title, style = mono(56f, bold = true))
                    Text(tile.subtitle, style = mono(38f, phosphorDim))
                }
            }
        }

        // The microphone is drawn on; light it up during a conversation
        val conversing = state in conversingStates
        Box(
            Modifier
                .at(micArea, sx, sy)
                .semantics { contentDescription = if (conversing) "Stop the conversation" else "Talk" }
                .clickable(enabled = canTalk, role = Role.Button) {
                    if (conversing) service?.stopAssistant() else service?.wakeAssistant()
                }
                .background(if (conversing) phosphor.copy(alpha = 0.25f) else Color.Transparent)
        )

        // System panel: what is actually set up and working
        Column(Modifier.at(systemArea, sx, sy), verticalArrangement = Arrangement.Center) {
            Text("СИСТЕМА", style = mono(42f, bold = true))
            systemLine("СВЯЗЬ С HA", if (state.isProblem()) "НЕТ" else "ЕСТЬ", state.isProblem(), ::mono)
            systemLine("МИКРОФОН", if (state is Stopped) "ВЫКЛ" else "АКТИВЕН", state is Stopped, ::mono)
            systemLine("РАСПОЗНАВАНИЕ", if (satellite?.enableDeviceStt == true) "ТЕЛЕФОН" else "ЯНДЕКС", false, ::mono)
            systemLine("ГОЛОС", if (player?.enableDeviceTts == true) "ТЕЛЕФОН" else "ЯНДЕКС", false, ::mono)
        }

        // Clock, or the nearest timer while one is running
        val timers = timerState()
        val now = rememberNow()
        Column(
            Modifier.at(clockArea, sx, sy),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val timer = timers.timers.firstOrNull()
            if (timer != null) {
                Text("ТАЙМЕР", style = mono(44f, phosphorDim))
                Text(formatDuration(timer.remainingDuration(timers.now)), style = mono(90f, bold = true))
                if (timer.name.isNotBlank()) Text(timer.name.uppercase(), style = mono(36f, phosphorDim))
            } else {
                Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = mono(120f, bold = true))
                Text(now.format(DateTimeFormatter.ofPattern("d MMMM", RU)).uppercase(), style = mono(40f))
                Text(now.format(DateTimeFormatter.ofPattern("EEEE", RU)).uppercase(), style = mono(36f, phosphorDim))
            }
        }

        // What was heard and answered, during a conversation and a while after it
        val transcriptFlow = remember(service) { service?.transcript ?: flowOf(null) }
        val transcript by transcriptFlow.collectAsStateWithLifecycle(null)
        var expired by remember { mutableStateOf(false) }
        LaunchedEffect(transcript?.at, conversing) {
            expired = false
            val at = transcript?.at ?: return@LaunchedEffect
            if (!conversing) {
                delay(TRANSCRIPT_SHOWN_MS - (System.currentTimeMillis() - at))
                expired = true
            }
        }
        val shown = transcript?.takeIf { !expired && (it.question.isNotBlank() || conversing) }
        if (shown != null) {
            TranscriptPanel(
                "ВЫ:", shown.question.ifBlank { if (state == Listening) "…" else "" },
                Modifier.at(questionArea, sx, sy), ::mono, sx
            )
            if (shown.answer.isNotBlank() || state == Processing) {
                TranscriptPanel(
                    "АНФИСА:", shown.answer.ifBlank { "…" },
                    Modifier.at(answerArea, sx, sy), ::mono, sx
                )
            }
        }

        Row(Modifier.at(footerArea, sx, sy), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                (satellite?.name?.uppercase() ?: "АНФИСА") + "  ★  ВСЕГДА РЯДОМ",
                style = mono(28f, phosphorDim)
            )
        }
    }
}

private val RU = Locale.forLanguageTag("ru")

/**
 * A dark plate with a label and what was said, sized down for longer text.
 */
@Composable
private fun TranscriptPanel(
    label: String,
    text: String,
    modifier: Modifier,
    mono: (Float, Color, Boolean) -> TextStyle,
    sx: Float
) {
    val rows = when {
        text.length < 80 -> 44f
        text.length < 160 -> 36f
        text.length < 260 -> 30f
        else -> 26f
    }
    Column(
        modifier
            .background(Color.Black)
            .border(BorderStroke(2.dp, phosphorDim))
            .padding(with(LocalDensity.current) { (30 * sx).toDp() }),
        verticalArrangement = Arrangement.spacedBy(with(LocalDensity.current) { (8 * sx).toDp() })
    ) {
        Text(label, style = mono(32f, phosphorDim, true))
        Text(text, style = mono(rows, phosphor, false), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun systemLine(
    name: String,
    value: String,
    warn: Boolean,
    mono: (Float, Color, Boolean) -> TextStyle
) {
    Text(
        "> " + name.padEnd(14) + "[ $value ]",
        style = mono(34f, if (warn) amber else phosphor, false)
    )
}

private fun EspHomeState.isProblem() = this is Stopped || this is Disconnected || this is ServerError

private fun statusTitle(state: EspHomeState) = when (state) {
    is Stopped -> "ВЫКЛЮЧЕНА"
    is Disconnected -> "НЕТ СВЯЗИ"
    is ServerError -> "СБОЙ"
    is Connected -> "ЖДУ КОМАНДЫ"
    is Listening -> "СЛУШАЮ…"
    is Processing -> "ДУМАЮ…"
    is Responding -> "ОТВЕЧАЮ…"
    is VoiceError -> "ОШИБКА"
    else -> state.toString().uppercase()
}

private fun statusDetail(state: EspHomeState) = when (state) {
    is Disconnected -> "ЖДУ HOME ASSISTANT"
    is ServerError -> state.message
    is VoiceError -> state.message
    is Connected -> "СКАЖИТЕ СЛОВО ИЛИ НАЖМИТЕ МИКРОФОН"
    else -> null
}

private fun formatDuration(d: Duration): String {
    val total = d.inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = LocalDateTime.now()
        }
    }
    return now
}

/**
 * Sizes and places the element at [area] of the artwork, in screen pixels.
 * The window covers the whole screen, so the root box starts at the screen's corner.
 */
private fun Modifier.at(area: Area, sx: Float, sy: Float) = layout { measurable, _ ->
    val w = ((area.x1 - area.x0) * sx).roundToInt()
    val h = ((area.y1 - area.y0) * sy).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.place((area.x0 * sx).roundToInt(), (area.y0 * sy).roundToInt()) }
}

/**
 * Centres a text vertically in the area [at] gave it.
 */
private fun Modifier.wrapCenterVertically() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(0, (constraints.maxHeight - placeable.height) / 2)
    }
}
