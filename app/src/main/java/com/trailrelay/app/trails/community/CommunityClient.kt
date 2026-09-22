package com.trailrelay.app.trails.community

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Blocking I/O, called only by the Community worker. */
class CommunityClient(context: Context) {
    private val cache = AtomicFile(File(context.filesDir, "community-catalog.json"))

    fun cached(): List<CatalogEntry>? = runCatching {
        cache.openRead().bufferedReader().use { CommunityCatalog.parse(it.readText()) }
    }.getOrNull()

    fun refresh(): List<CatalogEntry> {
        val bytes = java.io.ByteArrayOutputStream().also { download(CATALOG_URL, it, 4L * 1024 * 1024) }.toByteArray()
        val entries = CommunityCatalog.parse(bytes.toString(Charsets.UTF_8))
        val output = cache.startWrite()
        try {
            output.write(bytes)
            cache.finishWrite(output)
        } catch (error: Exception) {
            cache.failWrite(output)
            throw error
        }
        return entries
    }

    companion object {
        fun download(url: String, output: OutputStream, limit: Long = 32L * 1024 * 1024) {
            var address = CommunityCatalog.resolveGpxUrl(url)
            repeat(6) {
                val connection = URL(address).openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val target = connection.getHeaderField("Location") ?: throw IOException("Missing redirect location.")
                        address = CommunityCatalog.resolveGpxUrl(target, address)
                    } else {
                        if (status != 200) throw IOException("Server returned HTTP $status.")
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > limit) throw IOException("Download exceeds the ${limit / 1024 / 1024} MB limit.")
                                output.write(buffer, 0, count)
                            }
                        }
                        return
                    }
                } finally { connection.disconnect() }
            }
            throw IOException("Too many HTTPS redirects.")
        }
    }
}
