package org.crosspoint.companion.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object EpubImporter {
    suspend fun import(context: Context, uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val metadata = queryMetadata(context, uri)
        val temporary = File.createTempFile("crosspoint-import-", ".epub", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val epub = readEpubMetadata(temporary)
            BookEntity(
                id = stableBookId(uri),
                uri = uri.toString(),
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                title = epub.first.ifBlank { metadata.first.substringBeforeLast('.') },
                author = epub.second,
                sourceFileName = metadata.first,
                size = temporary.length(),
                modifiedAt = metadata.second,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun stableBookId(uri: Uri): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return "b" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val name = if (nameColumn >= 0) cursor.getString(nameColumn) else "book.epub"
                return name to System.currentTimeMillis()
            }
        }
        return "book.epub" to System.currentTimeMillis()
    }

    private fun readEpubMetadata(file: File): Pair<String, String> = runCatching {
        ZipFile(file).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val container = zip.getInputStream(zip.getEntry("META-INF/container.xml"))
                .use { factory.newDocumentBuilder().parse(it) }
            val rootFile = container.getElementsByTagNameNS("*", "rootfile").item(0) as Element
            val opfPath = rootFile.getAttribute("full-path")
            val opf = zip.getInputStream(zip.getEntry(opfPath)).use { factory.newDocumentBuilder().parse(it) }
            fun text(tag: String): String = opf.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim().orEmpty()
            text("title") to text("creator")
        }
    }.getOrDefault("" to "")
}
