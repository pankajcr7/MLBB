package com.pankaj.mlbbdraft.engine.meta

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP GET for the meta feed.
 *
 * `HttpURLConnection` rather than Retrofit or Ktor on purpose: this is two GETs against
 * one static JSON file, so a networking dependency would cost more APK than it saves in
 * code — and keeping it here means the fetcher lives in the pure-JVM module and is
 * testable without an emulator.
 */
class MetaFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    sealed interface Result {
        /** Fresh body. Store [etag] and send it next time. */
        data class Body(val json: String, val etag: String?) : Result

        /** Server says our cache is still current. */
        data object NotModified : Result

        /**
         * Nothing published at that URL yet. Kept separate from [Failure] because it is
         * the normal state before you set up the publishing job, not something broken.
         */
        data object NotPublished : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * @param etag value from the previous successful fetch, for `If-None-Match`.
     */
    fun fetch(url: String, etag: String? = null): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", USER_AGENT)
                etag?.let { setRequestProperty("If-None-Match", it) }
            }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Result.NotModified

                HttpURLConnection.HTTP_OK -> {
                    val gzipped = connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
                    val stream = if (gzipped) GZIPInputStream(connection.inputStream) else connection.inputStream
                    val body = stream.use { it.readAtMost(maxBytes) }
                    if (body == null) {
                        Result.Failure("Feed is larger than ${maxBytes / 1024} KB — refusing it.")
                    } else {
                        Result.Body(body, connection.getHeaderField("ETag"))
                    }
                }

                HttpURLConnection.HTTP_NOT_FOUND -> Result.NotPublished

                else -> Result.Failure("HTTP $code from the meta feed.")
            }
        } catch (e: IOException) {
            // Offline is the normal case, not an error worth shouting about: the app
            // keeps working on the last good cache.
            Result.Failure(e.message ?: "Network unavailable")
        } finally {
            connection?.disconnect()
        }
    }

    /** Null if the stream exceeds [limit] — guards against a feed URL that returns a video. */
    private fun java.io.InputStream.readAtMost(limit: Int): String? {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val USER_AGENT = "MlbbDraftHelper/0.2 (+https://github.com/pankajcr7/MLBB)"
    }
}
