package com.hhst.youtubelite.player.surface

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.hhst.youtubelite.ui.components.YoutubeAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.hhst.youtubelite.ui.components.YoutubeTextButton as TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.hhst.youtubelite.cast.CastDevice
import com.hhst.youtubelite.cast.CastDeviceKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.theme.SettingsTokens
import com.hhst.youtubelite.player.PlayerUiState
import kotlinx.coroutines.delay

/** Live Google Cast intro page. */
private const val CAST_LEARN_URL = "https://support.google.com/googlecast/answer/6102923"

@Composable
internal fun CastDialog(
    state: PlayerUiState,
    onDevice: (String) -> Unit,
    onStop: () -> Unit,
    onShareLink: () -> Unit,
    onCloseLink: () -> Unit,
    onRefresh: () -> Unit,
    onDiscovery: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPairCode: (String) -> Unit = {},
    onWvc: () -> Unit = {},
) {
    // Discovery scanning is battery-hungry: run it only while this dialog is
    // open, and keep the 1.5 s route re-poll for OEM ROMs that drop
    // discovery callbacks (the first scan can take several seconds).
    DisposableEffect(Unit) {
        onDiscovery(true)
        onDispose { onDiscovery(false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(1_500)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.cast),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.casting) {
                    Text(
                        text = stringResource(
                            R.string.casting_to,
                            state.castingDeviceName ?: stringResource(R.string.cast),
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = SettingsTokens.BodySize.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.cast_stop), color = PlayerUi.YtRed)
                    }
                }
                Text(
                    text = stringResource(R.string.cast_section_devices),
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                if (state.castDevices.isEmpty()) {
                    Text(
                        text = if (state.castAvailable) {
                            stringResource(R.string.cast_no_devices_found)
                        } else {
                            stringResource(R.string.cast_unavailable)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = SettingsTokens.DetailSize.sp,
                    )
                    Text(
                        text = if (state.castAvailable) {
                            stringResource(R.string.cast_no_devices_hint)
                        } else {
                            stringResource(R.string.cast_unavailable_hint)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LearnAboutCastingLink(
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                } else {
                    val groups = state.castDevices.groupBy { it.kind }
                    listOf(
                        CastDeviceKind.CHROMECAST to R.string.cast_kind_chromecast,
                        CastDeviceKind.DLNA to R.string.cast_kind_dlna,
                        CastDeviceKind.YOUTUBE_TV to R.string.cast_kind_youtube_tv,
                    ).forEach { (kind, label) ->
                        val devices = groups[kind] ?: return@forEach
                        Text(
                            text = stringResource(label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                        devices.forEach { device -> DeviceRow(device, onDevice) }
                    }
                }
                // YouTube TV `Link with TV code` + Web Video Caster hand-off
                // are available whether or not the LAN scan found anything.
                if (!state.casting) {
                    TvCodeRow(pairing = state.castPairing, onPairCode = onPairCode)
                    if (state.castWvcAvailable) {
                        Text(
                            text = stringResource(R.string.cast_section_other_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable { onWvc(); onDismiss() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cast_link),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp).size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.cast_wvc_row), color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(R.string.cast_wvc_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                // Link-cast is a separate mode from a Chromecast session: the
                // proxy serves the receiver's manifest during one, so the link
                // section (and with it the reconfigure path) is hidden here.
                if (!state.casting) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.cast_section_link),
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    Text(
                        text = stringResource(R.string.cast_section_link_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    state.castLinkUrl?.let { url ->
                        Text(
                            text = url,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.castLinkConnected) {
                            Text(
                                text = stringResource(R.string.cast_link_connected),
                                color = PlayerUi.YtRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = onShareLink) {
                            Text(stringResource(R.string.share))
                        }
                        if (state.castLinkUrl != null) {
                            TextButton(onClick = onCloseLink) {
                                Text(
                                    stringResource(R.string.cast_link_close),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun DeviceRow(device: CastDevice, onDevice: (String) -> Unit) {
    val icon = when {
        device.selected -> R.drawable.ic_cast_connected
        device.kind == CastDeviceKind.CHROMECAST -> R.drawable.ic_cast
        device.kind == CastDeviceKind.DLNA -> R.drawable.ic_cast_speaker
        else -> R.drawable.ic_cast_tv
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable { onDevice(device.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (device.selected) PlayerUi.YtRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(22.dp),
        )
        Text(
            text = device.name,
            color = if (device.selected) PlayerUi.YtRed else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TvCodeRow(pairing: Boolean, onPairCode: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Text(
        text = stringResource(R.string.cast_tv_code_title),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    Text(
        text = stringResource(R.string.cast_tv_code_hint),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        fontSize = 11.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() || c == ' ' }.take(15) },
            singleLine = true,
            enabled = !pairing,
            label = { Text(stringResource(R.string.cast_tv_code_field), fontSize = 12.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        if (pairing) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = { onPairCode(code) }, enabled = code.count { it.isDigit() } >= 6) {
                Text(stringResource(R.string.cast_tv_code_connect))
            }
        }
    }
}

@Composable
private fun LearnAboutCastingLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, CAST_LEARN_URL.toUri()),
                )
            }
        },
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.cast_learn_casting),
        )
    }
}
