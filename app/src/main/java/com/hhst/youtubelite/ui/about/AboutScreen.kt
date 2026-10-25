package com.hhst.youtubelite.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.hhst.youtubelite.ui.components.YoutubeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.YoutubeTextButton as TextButton
import com.hhst.youtubelite.ui.components.YoutubeTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.components.ConstrainedPage
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.ui.theme.settingsAppBarHeight
import com.hhst.youtubelite.update.UpdateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(label: String, version: String, onClose: () -> Unit, busy: Boolean, status: String,
    onSource: () -> Unit, onUpdate: () -> Unit, onRelease: () -> Unit, onClear: () -> Unit, onExport: () -> Unit,
    onSave: () -> Unit = {}, update: UpdateInfo? = null, progress: Float = -1f,
    onInstall: () -> Unit = {}, onCancelDownload: () -> Unit = {}) {
    val context = LocalContext.current
    val downloading = progress >= 0f
    BackHandler { onClose() }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge) },
            expandedHeight = settingsAppBarHeight(),
            navigationIcon = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back)) } })
    }) { padding ->
        ConstrainedPage(Modifier.padding(padding), maxWidth = 720.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SettingsTokens.PageInset),
                verticalArrangement = Arrangement.spacedBy(SettingsTokens.GroupGap)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), label, Modifier.size(72.dp), contentScale = ContentScale.None,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSource, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_source)) }
                HorizontalDivider()
                TextButton(enabled = !busy, onClick = onUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_check_update)) }
                if (update != null) {
                    if (downloading) {
                        TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_update_cancel)) }
                    } else {
                        TextButton(enabled = !busy, onClick = onInstall, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) {
                            Text(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                                text = stringResource(R.string.about_update_install, update.tag, AboutActivity.sizeLabel(context, update.apkSize)))
                        }
                    }
                }
                TextButton(onClick = onRelease, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_releases)) }
                TextButton(enabled = !busy, onClick = onClear, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_clear_cache)) }
                TextButton(enabled = !busy, onClick = onExport, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_export_logs)) }
                TextButton(enabled = !busy, onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsTokens.RowHeight), contentPadding = PaddingValues(vertical = SettingsTokens.RowPadding)) { Text(modifier = Modifier.fillMaxWidth(), text = stringResource(R.string.about_save_logs)) }
                if (downloading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
