package com.hhst.youtubelite.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hhst.youtubelite.diagnostics.AppLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a release APK into the app cache, verifies it against the published SHA-256
 * and hands it to the system package installer. Nothing is ever installed unverified:
 * a checksum mismatch deletes the file and fails.
 */
class UpdateInstaller(private val context: Context, private val client: OkHttpClient) {

    class ChecksumMismatchException(expected: String, actual: String) :
        IllegalStateException("SHA-256 mismatch: expected $expected, got $actual")

    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    /** Removes earlier downloads (e.g. from a release that has since been installed). */
    fun cleanup(keep: File? = null) {
        directory.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    /**
     * Streams [info] to the cache, reporting [onProgress] as bytes read / total (total may be -1),
     * then verifies the checksum when the release published one. Returns the verified file.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = File(directory, info.apkName)
        cleanup()
        val partial = File(directory, info.apkName + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(info.apkUrl).header("Accept", "application/octet-stream").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty download body")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            var read = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            if (info.apkSize > 0 && read != info.apkSize) {
                partial.delete()
                error("Truncated download: $read of ${info.apkSize} bytes")
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
            partial.delete()
            AppLog.event(AppLog.Category.APP, "update_checksum_mismatch", mapOf("tag" to info.tag, "asset" to info.apkName))
            throw ChecksumMismatchException(info.sha256, actual)
        }
        check(partial.renameTo(target)) { "Could not finalize ${target.name}" }
        AppLog.event(AppLog.Category.APP, "update_downloaded", mapOf("tag" to info.tag, "asset" to info.apkName, "verified" to (info.sha256 != null)))
        target
    }

    /** True when the system lets this app start a package install (always on API < 26). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Settings page where the user grants "install unknown apps" for this package (API 26+). */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Launches the system installer for a file produced by [download]. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.download.fileprovider", apk)
        @Suppress("DEPRECATION")
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Intent.ACTION_VIEW else Intent.ACTION_INSTALL_PACKAGE
        return Intent(action).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
    }

    companion object {
        const val DIRECTORY = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
