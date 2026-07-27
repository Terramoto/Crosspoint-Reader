package org.crosspoint.companion.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.crosspoint.companion.data.AppDatabase
import org.crosspoint.companion.data.BookEntity
import org.crosspoint.companion.data.HighlightEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

object AnnotationExporter {
    fun schedule(context: Context, bookId: String) {
        val request = OneTimeWorkRequestBuilder<AnnotationExportWorker>()
            .setInputData(Data.Builder().putString("bookId", bookId).build())
            .setInitialDelay(1, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("annotation-export-$bookId", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun export(context: Context, bookId: String): Boolean = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).libraryDao()
        val book = dao.book(bookId) ?: return@withContext false
        val folderUri = book.exportFolderUri?.let(Uri::parse) ?: return@withContext false
        val directory = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext false
        if (!directory.canWrite()) return@withContext false
        val highlights = dao.allHighlights(bookId)
        val stem = "${book.sha256}.crosspoint-highlights"
        val markdown = markdown(book, highlights)
        val json = json(book, highlights).toString(2)
        if (!write(context, directory, "$stem.md", "text/markdown", markdown)) return@withContext false
        if (!write(context, directory, "$stem.json", "application/json", json)) return@withContext false
        dao.clearExportDirty(bookId)
        true
    }

    suspend fun restoreExisting(context: Context, bookId: String): Int = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).libraryDao()
        val book = dao.book(bookId) ?: return@withContext 0
        val folderUri = book.exportFolderUri?.let(Uri::parse) ?: return@withContext 0
        val directory = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext 0
        val exactName = "${book.sha256}.crosspoint-highlights.json"
        val legacyPrefix = sanitize(book.sourceFileName.ifBlank { "${book.title}.epub" }.substringBeforeLast('.')) + "."
        val backup = directory.findFile(exactName) ?: directory.listFiles().firstOrNull {
            it.name?.startsWith(legacyPrefix) == true && it.name?.endsWith(".highlights.json") == true
        } ?: return@withContext 0
        val root = runCatching {
            context.contentResolver.openInputStream(backup.uri)!!.bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull() ?: return@withContext 0
        val values = root.optJSONArray("highlights") ?: return@withContext 0
        var restored = 0
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val position = item.optJSONObject("position") ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val existing = dao.highlight(id)
            dao.upsertHighlight(
                HighlightEntity(
                    id = id,
                    bookId = book.id,
                    revision = item.optString("revision"),
                    spineHref = position.optString("spineHref"),
                    startBlock = position.optLong("startBlock"),
                    startOffset = position.optLong("startOffset"),
                    endBlock = position.optLong("endBlock"),
                    endOffset = position.optLong("endOffset"),
                    quote = item.optString("text"),
                    page = position.optLong("page"),
                    line = position.optLong("line"),
                    note = item.optString("note"),
                    deviceSequence = item.optLong("deviceSequence"),
                    deleted = item.optBoolean("deleted"),
                    createdAt = parseInstant(item.optString("createdAt")) ?: existing?.createdAt
                        ?: System.currentTimeMillis(),
                    updatedAt = parseInstant(item.optString("updatedAt")) ?: existing?.updatedAt
                        ?: System.currentTimeMillis(),
                    deletedAt = parseInstant(item.optString("deletedAt")),
                    pendingReaderDelete = existing?.pendingReaderDelete ?: false,
                    pendingReaderUpsert = !item.optBoolean("deleted"),
                )
            )
            restored++
        }
        if (restored > 0) dao.clearExportDirty(book.id)
        restored
    }

    private fun write(context: Context, directory: DocumentFile, name: String, mime: String, value: String): Boolean {
        val file = directory.findFile(name) ?: directory.createFile(mime, name) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        }.isSuccess
    }

    private fun markdown(book: BookEntity, values: List<HighlightEntity>): String = buildString {
        appendLine("# Highlights — ${book.title}")
        appendLine()
        appendLine("- Filename: ${book.sourceFileName.ifBlank { "Unknown" }}")
        appendLine("- Author: ${book.author.ifBlank { "Unknown" }}")
        appendLine("- SHA-256: `${book.sha256}`")
        appendLine("- Generated: ${instant(System.currentTimeMillis())}")
        values.filterNot { it.deleted }.forEachIndexed { index, item ->
            appendLine()
            appendLine("## Highlight ${index + 1}")
            appendLine()
            appendLine("- Date: ${instantOrUnknown(item.createdAt)}")
            val layout = if (item.page > 0) "page ${item.page}, line ${item.line.coerceAtLeast(1)}; " else ""
            appendLine("- Position: $layout`${item.spineHref}` blocks ${item.startBlock}:${item.startOffset}–${item.endBlock}:${item.endOffset}")
            appendLine()
            item.quote.lines().forEach { appendLine("> $it") }
            if (item.note.isNotBlank()) {
                appendLine()
                appendLine("**Note:**")
                appendLine()
                appendLine(item.note)
            }
        }
    }

    private fun json(book: BookEntity, values: List<HighlightEntity>) = JSONObject().apply {
        put("schemaVersion", 1)
        put("generatedAt", instant(System.currentTimeMillis()))
        put("book", JSONObject().apply {
            put("id", book.id); put("filename", book.sourceFileName); put("title", book.title)
            put("author", book.author); put("sha256", book.sha256)
        })
        put("highlights", JSONArray().apply { values.forEach { item -> put(JSONObject().apply {
            put("id", item.id)
            put("revision", item.revision)
            put("deviceSequence", item.deviceSequence)
            put("createdAt", item.createdAt.takeIf { it > 0 }?.let(::instant) ?: JSONObject.NULL)
            put("updatedAt", item.updatedAt.takeIf { it > 0 }?.let(::instant) ?: JSONObject.NULL)
            put("deletedAt", item.deletedAt?.let(::instant) ?: JSONObject.NULL)
            put("deleted", item.deleted)
            put("position", JSONObject().apply {
                put("spineHref", item.spineHref); put("startBlock", item.startBlock); put("startOffset", item.startOffset)
                put("endBlock", item.endBlock); put("endOffset", item.endOffset)
                put("page", item.page); put("line", item.line)
            })
            put("text", item.quote); put("note", item.note)
        }) } })
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim().take(80).ifBlank { "book" }
    private fun instant(value: Long): String = Instant.ofEpochMilli(value).toString()
    private fun instantOrUnknown(value: Long): String = if (value > 0) instant(value) else "Unknown"
    private fun parseInstant(value: String): Long? =
        value.takeUnless { it.isBlank() || it == "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

class AnnotationExportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString("bookId") ?: return Result.failure()
        val book = AppDatabase.get(applicationContext).libraryDao().book(bookId) ?: return Result.failure()
        if (book.exportFolderUri == null) return Result.success()
        return if (AnnotationExporter.export(applicationContext, bookId)) Result.success() else Result.retry()
    }
}
