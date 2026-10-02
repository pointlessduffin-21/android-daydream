package com.daydream.standby.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daydream.standby.data.photos.LocalPhotoSource
import com.daydream.standby.data.settings.AppSettings
import com.daydream.standby.data.settings.ClockFormat
import com.daydream.standby.data.settings.ImmichMode
import com.daydream.standby.data.settings.NightModeSetting
import com.daydream.standby.data.settings.PhotoSourceType
import com.daydream.standby.data.settings.TemperatureUnit
import com.daydream.standby.ui.theme.StandByColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings = viewModel.settings.collectAsStateWithLifecycle().value
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val search by viewModel.locationSearch.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daydream Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (settings == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                photoSection(settings, viewModel)
                if (settings.photoSource == PhotoSourceType.IMMICH || settings.immichServerUrl.isNotBlank()) {
                    item { ImmichSection(settings, connection, viewModel) }
                    if (settings.immichMode == ImmichMode.ALBUMS) albumSection(settings, albums, viewModel)
                }
                item { HorizontalDivider() }
                clockSection(settings, viewModel)
                item { HorizontalDivider() }
                item { WeatherSection(settings, search, viewModel) }
                item { HorizontalDivider() }
                item { SystemSection(settings, viewModel) }
            }
        }
    }
}

private fun LazyListScope.photoSection(settings: AppSettings, viewModel: SettingsViewModel) {
    item { SectionTitle("Photos") }
    item {
        val context = LocalContext.current
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) viewModel.update { s -> s.copy(photoSource = PhotoSourceType.LOCAL) }
        }
        Choice(
            options = PhotoSourceType.entries,
            selected = settings.photoSource,
            label = { when (it) { PhotoSourceType.NONE -> "None"; PhotoSourceType.LOCAL -> "This device"; PhotoSourceType.IMMICH -> "Immich" } },
            onSelect = { type ->
                if (type == PhotoSourceType.LOCAL && !LocalPhotoSource.hasPermission(context)) {
                    permissionLauncher.launch(LocalPhotoSource.requiredPermissions)
                } else {
                    viewModel.update { it.copy(photoSource = type) }
                }
            },
        )
    }
    item {
        var interval by remember(settings.slideIntervalSeconds) { mutableFloatStateOf(settings.slideIntervalSeconds.toFloat()) }
        LabeledRow("Change photo every", "${interval.toInt()} s")
        Slider(
            value = interval,
            onValueChange = { interval = it },
            onValueChangeFinished = { viewModel.update { it.copy(slideIntervalSeconds = interval.toInt()) } },
            valueRange = AppSettings.MIN_INTERVAL_SECONDS.toFloat()..AppSettings.MAX_INTERVAL_SECONDS.toFloat(),
        )
    }
    item { SwitchRow("Show clock over photos", settings.photoClockOverlay) { v -> viewModel.update { it.copy(photoClockOverlay = v) } } }
}

@Composable
private fun ImmichSection(settings: AppSettings, connection: ConnectionState, viewModel: SettingsViewModel) {
    var url by rememberSaveable(settings.immichServerUrl) { mutableStateOf(settings.immichServerUrl) }
    // Plain remember: rememberSaveable would copy the key into the system's saved-state Bundle.
    var key by remember(settings.immichApiKey) { mutableStateOf(settings.immichApiKey) }
    var showKey by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Immich")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://photos.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { viewModel.connectImmich(url, key) }),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showKey) "Hide key" else "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.connectImmich(url, key) }, enabled = connection != ConnectionState.Testing) { Text("Connect") }
            if (settings.immichServerUrl.isNotBlank()) OutlinedButton(onClick = viewModel::disconnectImmich) { Text("Disconnect") }
            when (connection) {
                ConnectionState.Testing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is ConnectionState.Connected -> Text("Connected · Immich ${connection.version}", color = StandByColors.Green)
                else -> Unit
            }
        }
        when (connection) {
            is ConnectionState.Failed -> Text(connection.message, color = MaterialTheme.colorScheme.error)
            is ConnectionState.ConfirmInsecure -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${connection.host} uses plain HTTP over the internet. Your API key would be sent unencrypted — anyone on the network path could read it. Use HTTPS if you can.",
                    color = StandByColors.Orange,
                )
                OutlinedButton(onClick = { viewModel.connectImmich(url, key, allowInsecureHost = connection.host) }) { Text("Send over HTTP anyway") }
            }
            is ConnectionState.Connected -> if (connection.insecure) {
                Text("Warning: this server uses plain HTTP over the internet — your API key is sent unencrypted. Use HTTPS.", color = StandByColors.Orange)
            }
            else -> Unit
        }
        Text("Create a key in Immich › Account Settings › API Keys with asset.read, asset.view and album.read.", color = StandByColors.Secondary, style = MaterialTheme.typography.bodySmall)
        Choice(
            options = ImmichMode.entries,
            selected = settings.immichMode,
            label = { when (it) { ImmichMode.RANDOM -> "Random"; ImmichMode.FAVORITES -> "Favorites"; ImmichMode.ALBUMS -> "Albums" } },
            onSelect = { mode -> viewModel.update { it.copy(immichMode = mode) } },
        )
    }
}

private fun LazyListScope.albumSection(settings: AppSettings, albums: AlbumsState, viewModel: SettingsViewModel) {
    when (albums) {
        AlbumsState.Idle -> item { Text("Connect to choose albums.", color = StandByColors.Secondary) }
        AlbumsState.Loading -> item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        is AlbumsState.Failed -> item { Text(albums.message, color = MaterialTheme.colorScheme.error) }
        is AlbumsState.Loaded -> {
            if (albums.albums.isEmpty()) item { Text("No albums on this server.", color = StandByColors.Secondary) }
            items(albums.albums, key = { it.id }) { album ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.toggleAlbum(album.id) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = album.id in settings.immichAlbumIds, onCheckedChange = { viewModel.toggleAlbum(album.id) })
                    Text(album.albumName, Modifier.weight(1f))
                    Text("${album.assetCount}", color = StandByColors.Secondary)
                }
            }
        }
    }
}

private fun LazyListScope.clockSection(settings: AppSettings, viewModel: SettingsViewModel) {
    item { SectionTitle("Clock") }
    item {
        Choice(
            options = ClockFormat.entries,
            selected = settings.clockFormat,
            label = { when (it) { ClockFormat.SYSTEM -> "System"; ClockFormat.H12 -> "12-hour"; ClockFormat.H24 -> "24-hour" } },
            onSelect = { f -> viewModel.update { it.copy(clockFormat = f) } },
        )
    }
    item { SwitchRow("Show seconds", settings.showSeconds) { v -> viewModel.update { it.copy(showSeconds = v) } } }
    item { LabeledRow("Night mode", "Red, dimmed display in the dark") }
    item {
        Choice(
            options = NightModeSetting.entries,
            selected = settings.nightMode,
            label = { when (it) { NightModeSetting.AUTO -> "Auto"; NightModeSetting.ON -> "Always"; NightModeSetting.OFF -> "Off" } },
            onSelect = { m -> viewModel.update { it.copy(nightMode = m) } },
        )
    }
}

@Composable
private fun WeatherSection(settings: AppSettings, search: LocationSearch, viewModel: SettingsViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Weather")
        settings.weatherLocation?.let { loc ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(loc.name, Modifier.weight(1f))
                TextButton(onClick = { viewModel.chooseLocation(null) }) { Text("Remove") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search city") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchLocation(query) }),
            trailingIcon = { TextButton(onClick = { viewModel.searchLocation(query) }) { Text("Find") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (search.searching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        search.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        search.results.forEach { loc ->
            Text(
                loc.name,
                Modifier.fillMaxWidth().clickable { query = ""; viewModel.chooseLocation(loc) }.padding(vertical = 10.dp),
            )
        }
        Choice(
            options = TemperatureUnit.entries,
            selected = settings.temperatureUnit,
            label = { if (it == TemperatureUnit.CELSIUS) "°C" else "°F" },
            onSelect = { u -> viewModel.update { it.copy(temperatureUnit = u) } },
        )
        Text("Weather data by Open-Meteo.com", color = StandByColors.Secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SystemSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Launching")
        SwitchRow("Show over lock screen", settings.showWhenLocked, "Photos and widgets become visible without unlocking") { v ->
            viewModel.update { it.copy(showWhenLocked = v) }
        }
        Text(
            "Open StandBy any time from the app icon or the Quick Settings tile. To start it automatically while charging, set Daydream as your screen saver.",
            color = StandByColors.Secondary,
        )
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        }) { Text("Screen saver settings") }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StandByColors.Orange)
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, color = StandByColors.Secondary)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            description?.let { Text(it, color = StandByColors.Secondary, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}
