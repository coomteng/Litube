package com.hhst.youtubelite.update

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.R
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.ui.about.AboutActivity
import com.hhst.youtubelite.ui.about.StableVersion
import com.hhst.youtubelite.ui.components.YoutubeAlertDialog
import com.hhst.youtubelite.ui.components.YoutubeTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/**
 * Once-a-day automatic update check on app start. Shows a dialog when a newer stable release
 * exists; "Update now" opens About and starts the verified download. Development builds and
 * disabled preference skip the network entirely.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val prefs: ExtensionManager = koinInject()
    val client: OkHttpClient = koinInject()
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        val version = BuildConfig.VERSION_NAME
        // Debug builds carry a different application id, so a release APK would install side by side.
        if (BuildConfig.DEBUG || !prefs.isEnabled(PreferenceKeys.AUTO_CHECK_UPDATES) || StableVersion.isDevelopment(version) || !UpdateSchedule.isDue()) return@LaunchedEffect
        val result = runCatching {
            withContext(Dispatchers.IO) { UpdateChecker(client).check(version, Build.SUPPORTED_ABIS.toList()) }
        }.onFailure { AppLog.event(AppLog.Category.APP, "auto_update_check_failed", failure = it) }.getOrNull() ?: return@LaunchedEffect
        UpdateSchedule.noteChecked(result)
        val update = result.update ?: return@LaunchedEffect
        if (UpdateSchedule.shouldPrompt(update)) pending = update
    }
    val update = pending ?: return
    YoutubeAlertDialog(
        onDismissRequest = { UpdateSchedule.notePrompted(); pending = null },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = { Text(stringResource(R.string.update_dialog_message, update.tag, BuildConfig.VERSION_NAME)) },
        // Three actions do not fit one row on phones and Material's slot layout wraps them
        // unevenly, so stack them right-aligned with the primary action first.
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                YoutubeTextButton(onClick = {
                    pending = null
                    context.startActivity(Intent(context, AboutActivity::class.java).putExtra(AboutActivity.EXTRA_START_UPDATE, true))
                }) { Text(stringResource(R.string.update_dialog_now)) }
                YoutubeTextButton(onClick = { UpdateSchedule.notePrompted(); pending = null }) { Text(stringResource(R.string.update_dialog_later)) }
                YoutubeTextButton(onClick = { UpdateSchedule.skip(update.tag); pending = null }) { Text(stringResource(R.string.update_dialog_skip)) }
            }
        },
    )
}
