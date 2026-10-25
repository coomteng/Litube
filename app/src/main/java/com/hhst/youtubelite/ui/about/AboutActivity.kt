package com.hhst.youtubelite.ui.about

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import android.os.Build
import android.text.format.Formatter
import com.hhst.youtubelite.BuildConfig
import com.hhst.youtubelite.R
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.diagnostics.Documents
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.MemCache
import com.hhst.youtubelite.ui.theme.AppTheme
import com.hhst.youtubelite.update.UpdateInfo
import com.hhst.youtubelite.update.UpdateInstaller
import com.hhst.youtubelite.update.UpdateChecker
import com.hhst.youtubelite.update.UpdateSchedule
import com.tencent.mmkv.MMKV
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.java.KoinJavaComponent.get

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { AppTheme {
            val scope = rememberCoroutineScope()
            var status by rememberSaveable { mutableStateOf("") }
            var pendingArchive by rememberSaveable { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(pendingArchive != null) }
            val saveLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                val archive = pendingArchive?.let(::File)
                pendingArchive = null
                val operation = AppLog.operation(AppLog.Category.STORAGE, "diagnostics_save")
                if (uri == null) {
                    operation.finish(DiagnosticOutcome.CANCELLED, "file_picker_cancelled")
                    busy = false
                    status = getString(R.string.about_save_cancelled)
                } else {
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                checkNotNull(archive) { "Missing diagnostic archive" }
                                Documents.save(archive) { contentResolver.openOutputStream(uri, "w") }
                            }
                        }
                        operation.finish(if (result.isSuccess) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILURE,
                            if (result.isSuccess) "diagnostics_saved" else "file_write_failed", result.exceptionOrNull())
                        status = getString(if (result.isSuccess) R.string.about_save_done else R.string.about_save_failed)
                        busy = false
                    }
                }
            }
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            fun action(name: String, work: suspend () -> String) {
                if (busy) return
                busy = true; status = getString(R.string.about_working)
                scope.launch {
                    val operation = AppLog.operation(AppLog.Category.APP, name)
                    status = runCatching { work().also { operation.finish(DiagnosticOutcome.SUCCESS) } }.getOrElse {
                        operation.finish(if (it is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE, "operation_failed", it)
                        getString(R.string.about_operation_failed)
                    }
                    busy = false
                }
            }
            val checker = remember { UpdateChecker(get<OkHttpClient>(OkHttpClient::class.java)) }
            val installer = remember { UpdateInstaller(this@AboutActivity, get<OkHttpClient>(OkHttpClient::class.java)) }
            var update by remember { mutableStateOf<UpdateInfo?>(null) }
            var progress by remember { mutableStateOf(-1f) }
            var downloadJob by remember { mutableStateOf<Job?>(null) }
            fun install() {
                val info = update ?: return
                if (busy) return
                if (!installer.canInstall()) {
                    status = getString(R.string.about_update_permission)
                    runCatching { startActivity(installer.unknownSourcesIntent()) }
                    return
                }
                busy = true; progress = 0f
                status = getString(R.string.about_update_downloading, info.tag, 0)
                downloadJob = scope.launch {
                    val operation = AppLog.operation(AppLog.Category.APP, "install_update")
                    var lastPercent = -1
                    val result = runCatching {
                        installer.download(info) { read, total ->
                            val percent = if (total > 0) (read * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                scope.launch {
                                    progress = if (percent < 0) -1f else percent / 100f
                                    status = if (percent >= 100) getString(R.string.about_update_verifying)
                                    else getString(R.string.about_update_downloading, info.tag, percent.coerceAtLeast(0))
                                }
                            }
                        }
                    }
                    result.onSuccess { apk ->
                        operation.finish(DiagnosticOutcome.SUCCESS)
                        status = getString(R.string.about_update_installer)
                        runCatching { startActivity(installer.installIntent(apk)) }
                            .onFailure { status = getString(R.string.about_operation_failed) }
                    }.onFailure { failure ->
                        operation.finish(if (failure is CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE, "update_download_failed", failure)
                        status = when (failure) {
                            is CancellationException -> getString(R.string.about_save_cancelled)
                            is UpdateInstaller.ChecksumMismatchException -> getString(R.string.about_update_checksum_failed)
                            else -> getString(R.string.about_update_failed)
                        }
                    }
                    progress = -1f; busy = false; downloadJob = null
                }
            }
            // Auto-start when launched from the update dialog: check, then install.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { installer.cleanup() }
                if (intent.getBooleanExtra(EXTRA_START_UPDATE, false) && !busy) {
                    intent.removeExtra(EXTRA_START_UPDATE)
                    busy = true; status = getString(R.string.about_working)
                    val result = runCatching { withContext(Dispatchers.IO) { checker.check(version, Build.SUPPORTED_ABIS.toList()) } }
                    busy = false
                    result.onSuccess {
                        UpdateSchedule.noteChecked(it)
                        update = it.update
                        status = if (it.update != null) getString(R.string.about_update_available, it.latestTag) else getString(R.string.about_up_to_date, it.latestTag)
                        if (it.update != null) install()
                    }.onFailure { status = getString(R.string.about_operation_failed) }
                }
            }
            AboutScreen(applicationInfo.loadLabel(packageManager).toString(), version, { finish() }, busy, status,
                onSource = { openUrl(SOURCE) },
                update = update, progress = progress,
                onUpdate = { action("check_update") {
                    val result = withContext(Dispatchers.IO) { checker.check(version, Build.SUPPORTED_ABIS.toList()) }
                    UpdateSchedule.noteChecked(result)
                    update = result.update?.takeUnless { StableVersion.isDevelopment(version) }
                    when {
                        StableVersion.isDevelopment(version) -> getString(R.string.about_development_version, result.latestTag)
                        result.update != null -> getString(R.string.about_update_available, result.latestTag)
                        else -> getString(R.string.about_up_to_date, result.latestTag)
                    }
                } },
                onInstall = { install() },
                onCancelDownload = { downloadJob?.cancel() },
                onRelease = { openUrl("$SOURCE/releases/latest") },
                onClear = { action("clear_cache") {
                    // clearCache removes HTTP cache only; cookies and WebStorage keep the account.
                    WebView(this@AboutActivity).let { it.clearCache(true); it.destroy() }
                    withContext(Dispatchers.IO) {
                        get<MemCache>(MemCache::class.java).clear()
                        val kv = get<MMKV>(MMKV::class.java)
                        kv.allKeys()?.filter { it.startsWith("extractor:metadata:") || it.startsWith("extractor:stream:") || it.startsWith("extractor:segment:") }?.forEach(kv::removeValueForKey)
                        listOf("gallery", "thumbnails").forEach { File(cacheDir, it).deleteRecursively() }
                    }
                    getString(R.string.about_cache_cleared)
                } },
                onExport = { action("export_share") {
                    val archive = withContext(Dispatchers.IO) {
                        AppLog.export(this@AboutActivity)
                    }
                    val uri = FileProvider.getUriForFile(this@AboutActivity, "$packageName.download.fileprovider", archive)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.about_export_logs)))
                    getString(R.string.about_export_ready)
                } },
                onSave = {
                    if (!busy) {
                        busy = true
                        status = getString(R.string.about_working)
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) { AppLog.export(this@AboutActivity) }
                            }
                            result.onSuccess { archive ->
                                pendingArchive = archive.absolutePath
                                runCatching { saveLauncher.launch(archive.name) }.onFailure { failure ->
                                    AppLog.event(AppLog.Category.STORAGE, "diagnostics_picker_failed", failure = failure)
                                    pendingArchive = null
                                    busy = false
                                    status = getString(R.string.about_save_failed)
                                }
                            }.onFailure { failure ->
                                AppLog.event(AppLog.Category.STORAGE, "diagnostics_export_failed", failure = failure)
                                busy = false
                                status = getString(R.string.about_save_failed)
                            }
                        }
                    }
                })
        } }
    }
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.application_not_found, Toast.LENGTH_SHORT).show() }
    }
    companion object {
        private const val SOURCE = "https://github.com/${BuildConfig.FORK_REPO}"
        /** Boolean extra: check for the latest release and start downloading it immediately. */
        const val EXTRA_START_UPDATE = "start_update"
        fun sizeLabel(context: android.content.Context, bytes: Long): String =
            if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else "?"
    }
}
