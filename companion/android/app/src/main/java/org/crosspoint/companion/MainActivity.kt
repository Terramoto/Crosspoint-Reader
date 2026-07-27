package org.crosspoint.companion

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.crosspoint.companion.ble.CrossPointBleService
import org.crosspoint.companion.data.AppDatabase
import org.crosspoint.companion.data.BookEntity
import org.crosspoint.companion.data.EpubImporter
import org.crosspoint.companion.data.HighlightEntity
import org.crosspoint.companion.export.AnnotationExporter
import org.crosspoint.companion.notifications.NotificationRepository
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private fun diagnosticTime(timestamp: Long): String =
        if (timestamp <= 0) "never"
        else DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))

    private fun requestedRuntimePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasConnectionPermissions() = requestedRuntimePermissions()
        .filterNot { it == Manifest.permission.POST_NOTIFICATIONS }
        .all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CompanionScreen(AppDatabase.get(this)) } }
    }

    @Composable
    private fun CompanionScreen(database: AppDatabase) {
        val scope = rememberCoroutineScope()
        val books by database.libraryDao().observeBooks().collectAsState(initial = emptyList())
        val connection by CrossPointBleService.state.collectAsState()
        val pendingDeletes by database.libraryDao().observePendingDeleteCount().collectAsState(initial = 0)
        val pendingDeviceActions by database.libraryDao().observePendingDeviceActionCount().collectAsState(initial = 0)
        var selectedBook by remember { mutableStateOf<BookEntity?>(null) }
        val currentSelectedBook = selectedBook?.let { selected ->
            books.firstOrNull { it.id == selected.id } ?: selected
        }
        var folderBookId by remember { mutableStateOf<String?>(null) }
        var folderMessage by remember { mutableStateOf<String?>(null) }
        var deleteCandidate by remember { mutableStateOf<BookEntity?>(null) }
        var permissionsGranted by remember { mutableStateOf(hasConnectionPermissions()) }
        var notificationsEnabled by remember {
            mutableStateOf(NotificationRepository.enabled(this@MainActivity))
        }
        var notificationInterval by remember {
            mutableIntStateOf(NotificationRepository.intervalMinutes(this@MainActivity))
        }
        var notificationAccess by remember {
            mutableStateOf(
                NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                    .contains(packageName)
            )
        }
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    notificationAccess =
                        NotificationManagerCompat.getEnabledListenerPackages(this@MainActivity)
                            .contains(packageName)
                    if (notificationsEnabled) {
                        CrossPointBleService.refreshNotificationSettings(this@MainActivity)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = hasConnectionPermissions()
            if (permissionsGranted) CrossPointBleService.start(this)
        }
        LaunchedEffect(Unit) {
            val missing = requestedRuntimePermissions().filter {
                ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
            else if (permissionsGranted) CrossPointBleService.start(this@MainActivity)
        }
        val bookFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val bookId = folderBookId
            folderBookId = null
            if (uri != null && bookId != null) scope.launch(Dispatchers.IO) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                database.libraryDao().setExportFolder(bookId, uri.toString())
                val restored = AnnotationExporter.restoreExisting(this@MainActivity, bookId)
                if (restored == 0) AnnotationExporter.schedule(this@MainActivity, bookId)
                withContext(Dispatchers.Main) {
                    folderMessage = if (restored > 0) {
                        "Restored $restored highlight(s) from this book's folder"
                    } else {
                        "This book's highlights will be saved in the selected folder"
                    }
                }
            }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                runCatching { EpubImporter.import(this@MainActivity, uri) }.onSuccess { book ->
                    val existing = database.libraryDao().bookBySha256(book.sha256)
                    val imported = if (existing == null) {
                        book
                    } else {
                        book.copy(
                            id = existing.id,
                            exportDirty = existing.exportDirty,
                            desiredOnDevice = existing.desiredOnDevice,
                            devicePath = existing.devicePath,
                            installedSha256 = existing.installedSha256,
                            exportFolderUri = existing.exportFolderUri,
                        )
                    }
                    database.libraryDao().upsertBook(imported)
                    withContext(Dispatchers.Main) {
                        folderBookId = imported.id
                        bookFolder.launch(null)
                    }
                }
            }
        }

        deleteCandidate?.let { book ->
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                title = { Text("Delete app record?") },
                text = {
                    Text(
                        if (book.devicePath != null) {
                            "This removes the phone record and its notes, but not the copy already on the reader."
                        } else {
                            "This removes the imported record and its highlights/notes from the app."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteCandidate = null
                        if (selectedBook?.id == book.id) selectedBook = null
                        scope.launch(Dispatchers.IO) { database.libraryDao().deleteBookRecord(book) }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
            )
        }

        Scaffold(topBar = { TopAppBar(title = { Text("CrossPoint Companion") }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                Text(connection.status)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sleep notifications")
                        Text(
                            if (notificationInterval == 1) {
                                "One-minute timer-only diagnostic"
                            } else if (notificationAccess) {
                                "Reader checks every $notificationInterval minutes"
                            } else {
                                "Android Notification Access is required"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { enabled ->
                            notificationsEnabled = enabled
                            NotificationRepository.setEnabled(this@MainActivity, enabled)
                            if (enabled && notificationInterval != 1 && !notificationAccess) {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            CrossPointBleService.refreshNotificationSettings(this@MainActivity)
                        },
                    )
                }
                if (notificationsEnabled) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(1, 5, 10, 15, 30).forEach { minutes ->
                            FilterChip(
                                selected = notificationInterval == minutes,
                                onClick = {
                                    notificationInterval = minutes
                                    NotificationRepository.setIntervalMinutes(this@MainActivity, minutes)
                                    if (minutes != 1 && !notificationAccess) {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }
                                    CrossPointBleService.refreshNotificationSettings(this@MainActivity)
                                },
                                label = { Text(if (minutes == 1) "1m test" else "${minutes}m") },
                            )
                        }
                    }
                    if (notificationInterval != 1 && !notificationAccess) {
                        TextButton(onClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Grant Notification Access") }
                    }
                    Text(
                        "Captured: ${connection.capturedNotifications} · " +
                            "reader seen: ${diagnosticTime(connection.lastReaderSeenAt)}\n" +
                            "connected: ${diagnosticTime(connection.lastConnectedAt)} · " +
                            "delivered: ${diagnosticTime(connection.lastNotificationDeliveryAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (connection.wifiSsid != null) {
                    Text(
                        "Reader Wi-Fi: ${connection.wifiSsid}\nPassword: ${connection.wifiPassword}\n" +
                            "Approve Android's connection prompt. If it is hidden, connect here manually.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        startActivity(
                            Intent(
                                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI
                                else Settings.ACTION_WIFI_SETTINGS
                            )
                        )
                    }) { Text("Open Wi-Fi settings") }
                }
                folderMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (pendingDeletes > 0) Text("$pendingDeletes change(s) pending reader sync")
                if (pendingDeviceActions > 0) Text("$pendingDeviceActions book action(s) queued")
                if (connection.progress > 0) {
                    LinearProgressIndicator(progress = { connection.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importer.launch(arrayOf("application/epub+zip", "application/zip")) }) { Text("Import EPUB") }
                    Button(onClick = {
                        if (!permissionsGranted) {
                            permissionLauncher.launch(requestedRuntimePermissions())
                        } else CrossPointBleService.start(this@MainActivity)
                    }) { Text(if (connection.connected) "Connected" else "Connect reader") }
                    if (connection.connected) OutlinedButton(onClick = { CrossPointBleService.stop(this@MainActivity) }) { Text("Disconnect") }
                }
                if (currentSelectedBook == null) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(books, key = { it.id }) { book ->
                            Card(Modifier.fillMaxWidth().clickable { selectedBook = book }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                                    if (book.author.isNotBlank()) Text(book.author)
                                    Text("${book.size / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        when {
                                            book.desiredOnDevice && book.installedSha256 == book.sha256 -> "On reader"
                                            book.desiredOnDevice -> "Queued for reader"
                                            book.devicePath != null -> "Queued for removal"
                                            else -> "Phone only"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            database.libraryDao().setDesiredOnDevice(book.id, !book.desiredOnDevice)
                                        }
                                    }) {
                                        Text(if (book.desiredOnDevice) "Remove from reader" else "Add to reader")
                                    }
                                    TextButton(onClick = { deleteCandidate = book }) { Text("Delete app record") }
                                }
                            }
                        }
                    }
                } else {
                    HighlightEditor(
                        database,
                        currentSelectedBook,
                        onBack = { selectedBook = null },
                        onChooseFolder = { book ->
                            folderBookId = book.id
                            bookFolder.launch(book.exportFolderUri?.let(Uri::parse))
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun HighlightEditor(
        database: AppDatabase,
        book: BookEntity,
        onBack: () -> Unit,
        onChooseFolder: (BookEntity) -> Unit,
    ) {
        val highlights by database.libraryDao().observeHighlights(book.id).collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        var editingHighlight by remember { mutableStateOf<HighlightEntity?>(null) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(book.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { AnnotationExporter.schedule(this@MainActivity, book.id) }) { Text("Export") }
            TextButton(onClick = { onChooseFolder(book) }) { Text("Folder") }
        }
        if (book.uri.isBlank()) {
            Text("Reader-only book · import the matching EPUB to attach it automatically",
                style = MaterialTheme.typography.bodySmall)
        } else if (book.exportFolderUri == null) {
            Text("Choose Folder once to save exports beside the EPUB", style = MaterialTheme.typography.bodySmall)
        }
        if (highlights.isEmpty()) {
            Text("Highlights from the reader will appear here.", modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(highlights, key = HighlightEntity::id) { highlight ->
                Column(
                    Modifier.fillMaxWidth().clickable { editingHighlight = highlight }
                        .padding(vertical = 14.dp, horizontal = 4.dp)
                ) {
                    Text(highlight.quote, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (highlight.page > 0) {
                            "Page ${highlight.page} · line ${highlight.line.coerceAtLeast(1)}"
                        } else {
                            "${highlight.spineHref} · ${highlight.startBlock}:${highlight.startOffset}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (highlight.note.isNotBlank()) {
                        Text(
                            highlight.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        editingHighlight?.let { highlight ->
            var note by remember(highlight.id, highlight.note) { mutableStateOf(highlight.note) }
            AlertDialog(
                onDismissRequest = { editingHighlight = null },
                title = { Text("Highlight note") },
                text = {
                    Column {
                        Text(highlight.quote, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            label = { Text("Note") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        editingHighlight = null
                        scope.launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            database.libraryDao().upsertHighlight(
                                highlight.copy(note = note, updatedAt = now, pendingReaderUpsert = true)
                            )
                            database.libraryDao().markExportDirty(highlight.bookId)
                            AnnotationExporter.schedule(this@MainActivity, highlight.bookId)
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            editingHighlight = null
                            scope.launch(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                database.libraryDao().upsertHighlight(
                                    highlight.copy(
                                        deleted = true,
                                        deletedAt = now,
                                        updatedAt = now,
                                        pendingReaderDelete = true,
                                    )
                                )
                                database.libraryDao().markExportDirty(highlight.bookId)
                                AnnotationExporter.schedule(this@MainActivity, highlight.bookId)
                                CrossPointBleService.deleteHighlight(
                                    this@MainActivity, highlight.bookId, highlight.id
                                )
                            }
                        }) { Text("Delete") }
                        TextButton(onClick = { editingHighlight = null }) { Text("Cancel") }
                    }
                },
            )
        }
    }
}
