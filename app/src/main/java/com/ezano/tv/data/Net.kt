package com.ezano.tv.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Net {
    const val DEFAULT_UA = "EzanoTV/1.0 (Linux; Android) ExoPlayer"

    lateinit var client: OkHttpClient
        private set

    fun init(cacheDir: File) {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cache(Cache(File(cacheDir, "http"), 50L * 1024 * 1024))
            .build()
    }

    fun request(url: String, userAgent: String = ""): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .header("Accept-Encoding", "gzip")
            .build()

    /** Opens a body stream, transparently un-gzipping if the server or file is gzipped. */
    suspend fun <T> open(url: String, userAgent: String = "", block: (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val resp: Response = client.newCall(request(url, userAgent)).execute()
            resp.use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${redact(url)}")
                val body = r.body ?: throw IOException("Empty response")
                var stream: InputStream = body.byteStream().buffered(64 * 1024)
                val gz = r.header("Content-Encoding")?.contains("gzip", true) == true ||
                    url.substringBefore('?').endsWith(".gz", true)
                if (gz) {
                    stream = GZIPInputStream(stream, 64 * 1024)
                } else {
                    // Sniff gzip magic bytes (some servers send .xml.gz without headers)
                    stream.mark(2)
                    val b1 = stream.read(); val b2 = stream.read()
                    stream.reset()
                    if (b1 == 0x1f && b2 == 0x8b) stream = GZIPInputStream(stream, 64 * 1024)
                }
                block(stream)
            }
        }

    suspend fun getText(url: String, userAgent: String = ""): String = open(url, userAgent) { it.readBytes().toString(Charsets.UTF_8) }

    fun redact(url: String): String = url.replace(Regex("(password=)[^&]*"), "$1***")
}

/**
 * Streaming reader for large JSON arrays of flat objects (Xtream stream lists can be 50MB+).
 * Nested values are skipped. Each object is delivered as a map of primitive strings.
 */
object FlatJson {
    fun readArray(input: InputStream, onItem: (Map<String, String>) -> Unit) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.isLenient = true
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) onItem(readObject(reader)) else reader.skipValue()
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                // Some panels return {"0":{...},"1":{...}}
                reader.beginObject()
                while (reader.hasNext()) {
                    reader.nextName()
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) onItem(readObject(reader)) else reader.skipValue()
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    private fun readObject(reader: JsonReader): Map<String, String> {
        val map = HashMap<String, String>(24)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> map[name] = reader.nextString()
                JsonToken.BOOLEAN -> map[name] = reader.nextBoolean().toString()
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BEGIN_ARRAY -> {
                    // Keep first string of arrays like backdrop_path
                    reader.beginArray()
                    var first: String? = null
                    while (reader.hasNext()) {
                        if (first == null && reader.peek() == JsonToken.STRING) first = reader.nextString() else reader.skipValue()
                    }
                    reader.endArray()
                    if (first != null) map[name] = first
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return map
    }
}

fun Map<String, String>.s(key: String): String = this[key]?.takeIf { it != "null" } ?: ""
fun Map<String, String>.i(key: String): Int = this[key]?.trim()?.toDoubleOrNull()?.toInt() ?: 0
fun Map<String, String>.l(key: String): Long = this[key]?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
fun Map<String, String>.d(key: String): Double = this[key]?.trim()?.toDoubleOrNull() ?: 0.0
