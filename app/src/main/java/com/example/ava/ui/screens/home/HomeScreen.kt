package com.example.ava.ui.screens.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.ui.Settings
import com.example.ava.ui.screens.settings.SettingsViewModel
import com.example.ava.ui.services.VoiceSatelliteStatus
import com.example.ava.ui.services.ThinkingFrame
import com.example.ava.ui.services.VoiceSatelliteSwitch
import com.example.ava.ui.services.components.timerListSection
import com.example.ava.ui.services.components.timerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val timerState = timerState()
    val satelliteSettings by settingsViewModel.satelliteSettingsState.collectAsStateWithLifecycle(null)
    val satelliteName = satelliteSettings?.name

    if (isLandscape) {
        Box(modifier = Modifier.fillMaxSize()) {
            RetroHome(onSettings = { navController.navigate(Settings) })
            ThinkingFrame()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    colors = topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    title = {
                        // The satellite name tells devices apart, e.g. on several phones
                        Text(satelliteName ?: stringResource(R.string.app_name))
                    },
                    // On the left, away from the settings button
                    navigationIcon = {
                        VoiceSatelliteSwitch()
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Settings) }) {
                            Icon(
                                painter = painterResource(R.drawable.settings_24px),
                                contentDescription = stringResource(R.string.label_settings),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                item {
                    VoiceSatelliteStatus()
                }
                timerListSection(state = timerState)
            }
        }
        ThinkingFrame()
    }
}
