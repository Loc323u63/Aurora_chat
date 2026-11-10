package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.DEFAULT_LYRICS_SOURCES
import com.aurora.music.data.LyricsSync
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.launch

private fun lyricsSourceLabel(id: String) = when (id) {
    "server" -> appString(R.string.lyrics_source_server)
    "spotify" -> "Spotify"
    "lrclib" -> "LRCLIB"
    "genius" -> "Genius"
    else -> id
}

private fun lyricsSourceDetail(id: String) = when (id) {
    "server" -> appString(R.string.lyrics_source_server_detail)
    "spotify" -> appString(R.string.lyrics_source_spotify_detail)
    "lrclib" -> appString(R.string.lyrics_source_lrclib_detail)
    "genius" -> "Search Genius and read lyrics from its public song pages"
    else -> ""
}

@Composable
fun LyricsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val store = container.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val order by store.lyricsSources.collectAsStateWithLifecycle(initialValue = DEFAULT_LYRICS_SOURCES)
    val token by store.geniusToken.collectAsStateWithLifecycle(initialValue = "")
    val progress by container.geniusLyricsImporter.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tokenEdit by remember { mutableStateOf<String?>(null) }
    val line = prefs.lyricsSync == LyricsSync.LINE

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.lyrics.label, onBack)
        SettingsList(contentPadding) {
            item {
                SegmentedRow(appString(R.string.lyrics_sync_label), listOf(appString(R.string.lyrics_sync_line), appString(R.string.lyrics_sync_word)),
                    if (line) 0 else 1) { i ->
                    scope.launch { store.setLyricsSync(if (i == 0) LyricsSync.LINE else LyricsSync.WORD) }
                }
            }
            item {
                Text(appString(if (line) R.string.lyrics_sync_line_desc else R.string.lyrics_sync_word_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item { SettingsSectionTitle(appString(R.string.lyrics_sources_title_desktop)) }
            item {
                Text(appString(R.string.lyrics_sources_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            itemsIndexed(order) { index, id ->
                PriorityRow(
                    label = lyricsSourceLabel(id),
                    subtitle = lyricsSourceDetail(id),
                    enabled = true,
                    canUp = index > 0,
                    canDown = index < order.lastIndex,
                    onUp = { scope.launch { store.setLyricsSources(order.swapped(index, index - 1)) } },
                    onDown = { scope.launch { store.setLyricsSources(order.swapped(index, index + 1)) } },
                )
            }
            item {
                SettingsSectionTitle("Genius local import")
                Text(
                    "A Genius API token is used to search for songs. Lyrics are stored only on this device. Requests are sequential with a delay; HTTP 403 or 429 stops the import. Automated extraction may be restricted by Genius terms.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                OutlinedTextField(
                    value = tokenEdit ?: token,
                    onValueChange = { tokenEdit = it },
                    label = { Text("Genius API access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "Stored lyrics: ${progress.found} · Not found: ${progress.notFound} · Errors: ${progress.failed} · Size: ${progress.bytesStored / 1024} KB",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                if (progress.currentTrack.isNotBlank()) Text(progress.currentTrack,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                if (progress.message.isNotBlank()) Text(progress.message,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Button(
                    onClick = {
                        scope.launch {
                            store.setGeniusToken(tokenEdit ?: token)
                            tokenEdit = null
                            container.geniusLyricsImporter.start()
                        }
                    },
                    enabled = !progress.running && (tokenEdit ?: token).isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Import lyrics for the whole library") }
                Button(
                    onClick = { container.geniusLyricsImporter.stop() },
                    enabled = progress.running,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) { Text("Stop import") }
                Button(
                    onClick = {
                        scope.launch {
                            store.setGeniusToken("")
                            tokenEdit = ""
                        }
                    },
                    enabled = !progress.running && (tokenEdit ?: token).isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) { Text("Clear Genius token") }
            }
        }
    }
}
