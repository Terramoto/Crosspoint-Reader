package org.crosspoint.companion.wifi

import android.content.Context
import android.net.Network
import android.net.Uri
import org.crosspoint.companion.data.AppDatabase
import org.crosspoint.companion.data.BookEntity
import org.json.JSONArray
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CompanionWifiSync(
    private val context: Context,
    private val network: Network,
    private val host: String,
    private val token: String,
    private val onStatus: (String, Int) -> Unit,
) {
    private val dao = AppDatabase.get(context).libraryDao()

    suspend fun run(): Result<Unit> = runCatching {
        retryReady()
        ensureBooksDirectory()
        reconcileDeviceInventory()
        val actions = dao.pendingDeviceActions()
        actions.forEachIndexed { index, book ->
            val baseProgress = if (actions.isEmpty()) 0 else index * 100 / actions.size
            if (book.desiredOnDevice) {
                upload(book, baseProgress, actions.size)
            } else {
                remove(book)
            }
        }
        request("/api/companion/complete", "POST").consume { requireSuccess(it) }
        onStatus("Device library synchronized", 100)
    }.onFailure {
        // Completion is also the safe abort signal. Desired-state rows remain
        // pending, so failed operations are retried in the next session.
        runCatching { request("/api/companion/complete", "POST").consume { } }
    }

    private fun connection(path: String, method: String): HttpURLConnection {
        val url = URL("http://$host$path")
        return (network.openConnection(url) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("X-CrossPoint-Token", token)
            useCaches = false
        }
    }

    private fun request(path: String, method: String, body: String? = null): HttpURLConnection {
        val connection = connection(path, method)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.bufferedWriter().use { it.write(body) }
        }
        connection.responseCode
        return connection
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        if (connection.responseCode !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("Reader returned ${connection.responseCode}: $message")
        }
    }

    private inline fun <T> HttpURLConnection.consume(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private fun retryReady() {
        var failure: Throwable? = null
        repeat(20) {
            try {
                request("/api/companion/ready", "POST").consume {
                    requireSuccess(it)
                    return
                }
            } catch (error: Throwable) {
                failure = error
                Thread.sleep(250)
            }
        }
        throw failure ?: IllegalStateException("Reader transfer server did not start")
    }

    private fun ensureBooksDirectory() {
        val body = "name=Books&path=%2F"
        request("/mkdir", "POST", body).consume {
            // 409 means the normal /Books directory already exists.
            if (it.responseCode !in 200..299 && it.responseCode != 409) requireSuccess(it)
        }
    }

    private suspend fun reconcileDeviceInventory() {
        val response = request("/api/files?path=%2FBooks", "GET").consume {
            requireSuccess(it)
            it.inputStream.bufferedReader().use { reader -> reader.readText() }
        }
        val entries = JSONArray(response)
        val paths = buildSet {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                if (!entry.optBoolean("isDirectory")) add("/Books/${entry.getString("name")}")
            }
        }
        dao.allBooks().forEach { book ->
            val path = book.devicePath ?: return@forEach
            if (path !in paths) {
                if (book.desiredOnDevice) dao.markDeviceMissing(book.id) else dao.markRemoved(book.id)
            }
        }
    }

    private suspend fun remove(book: BookEntity) {
        val path = book.devicePath ?: return
        onStatus("Removing ${book.title}", 0)
        val json = JSONArray().put(path).toString()
        request("/delete", "POST", "paths=${encode(json)}").consume { requireSuccess(it) }
        dao.markRemoved(book.id)
    }

    private fun destination(book: BookEntity): String {
        book.devicePath?.let { return it }
        val source = book.sourceFileName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(72).ifBlank { "book" }
        return "/Books/$source-${book.id.takeLast(8)}.epub"
    }

    private suspend fun upload(book: BookEntity, baseProgress: Int, actionCount: Int) {
        val destination = destination(book)
        val filename = destination.substringAfterLast('/')
        val boundary = "CrossPoint${System.currentTimeMillis()}"
        val prefix = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/epub+zip\r\n\r\n"
        val suffix = "\r\n--$boundary--\r\n"
        val connection = connection("/upload?path=%2FBooks&sha256=${encode(book.sha256)}", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.toByteArray().size.toLong() + book.size + suffix.toByteArray().size)
        }
        BufferedOutputStream(connection.outputStream, 32 * 1024).use { output ->
            output.write(prefix.toByteArray())
            context.contentResolver.openInputStream(Uri.parse(book.uri))!!.use { input ->
                val buffer = ByteArray(32 * 1024)
                var sent = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    val within = if (book.size == 0L) 100 else (sent * 100 / book.size).toInt()
                    val overall = if (actionCount == 0) within else baseProgress + within / actionCount
                    onStatus("Installing ${book.title}", overall.coerceIn(0, 99))
                }
            }
            output.write(suffix.toByteArray())
        }
        connection.consume { requireSuccess(it) }
        dao.markInstalled(book.id, destination, book.sha256)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
