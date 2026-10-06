package com.ezano.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.ezano.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Self-updater: checks the GitHub "latest" release this APK was built from. */
object Updater {
    data class Update(val versionName: String, val versionCode: Int, val apkUrl: String, val notes: String)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return@withContext null
        runCatching {
            val text = Net.getText("https://api.github.com/repos/$repo/releases/latest")
            val o = Json.parseToJsonElement(text) as JsonObject
            val tag = o.str("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching null
            val assets = o["assets"] as? JsonArray
            val apk = assets?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("name").endsWith(".apk") }?.str("browser_download_url")
                ?: return@runCatching null
            if (code > BuildConfig.VERSION_CODE) Update(tag.removePrefix("v"), code, apk, o.str("body")) else null
        }.getOrNull()
    }

    suspend fun downloadAndInstall(context: Context, update: Update, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val useExternal = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val dir = File(if (useExternal) context.externalCacheDir ?: context.cacheDir else context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "EzanoTV.apk")
        val resp = Net.client.newCall(Net.request(update.apkUrl).newBuilder().removeHeader("Accept-Encoding").build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
            val body = r.body ?: error("Empty download")
            val total = body.contentLength().toFloat()
            var read = 0L
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); read += n
                        if (total > 0) onProgress(read / total)
                    }
                }
            }
        }
        val uri: Uri = if (useExternal) Uri.fromFile(file)
        else FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) { context.startActivity(intent) }
    }
}
