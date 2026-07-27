package org.crosspoint.companion.ble

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.crosspoint.companion.data.AppDatabase
import org.crosspoint.companion.data.BookEntity
import org.crosspoint.companion.data.HighlightEntity
import org.crosspoint.companion.export.AnnotationExporter
import org.crosspoint.companion.notifications.NotificationRepository
import org.crosspoint.companion.notifications.PhoneNotification
import org.crosspoint.companion.wifi.CompanionWifiSync
import org.json.JSONObject
import java.util.ArrayDeque

class CrossPointBleService : LifecycleService() {
    data class UiState(
        val status: String = "Stopped",
        val connected: Boolean = false,
        val progress: Int = 0,
        val wifiSsid: String? = null,
        val wifiPassword: String? = null,
        val capturedNotifications: Int = 0,
        val lastReaderSeenAt: Long = 0,
        val lastConnectedAt: Long = 0,
        val lastNotificationDeliveryAt: Long = 0,
    )

    companion object {
        private const val ACTION_DELETE = "org.crosspoint.companion.DELETE_HIGHLIGHT"
        private const val ACTION_REFRESH_NOTIFICATION_SETTINGS =
            "org.crosspoint.companion.REFRESH_NOTIFICATION_SETTINGS"
        private const val ACTION_BACKGROUND_SCAN =
            "org.crosspoint.companion.BACKGROUND_SCAN"
        private const val CONNECTION_PREFS = "crosspoint_reader_connection"
        private const val READER_ADDRESS = "reader_address"
        private val mutableState = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = mutableState
        fun start(context: Context) = context.startForegroundService(Intent(context, CrossPointBleService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, CrossPointBleService::class.java))
        fun refreshNotificationSettings(context: Context) {
            context.startForegroundService(
                Intent(context, CrossPointBleService::class.java)
                    .setAction(ACTION_REFRESH_NOTIFICATION_SETTINGS)
            )
        }
        fun deleteHighlight(context: Context, bookId: String, id: String) {
            context.startForegroundService(
                Intent(context, CrossPointBleService::class.java).setAction(ACTION_DELETE)
                    .putExtra("bookId", bookId).putExtra("id", id)
            )
        }
    }

    private lateinit var database: AppDatabase
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var scanner: BluetoothLeScanner? = null
    private var callbackScanActive = false
    private var pendingIntentScanActive = false
    private var pendingBondDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var autoConnectGatt = false
    private var gattConnected = false
    private var control: BluetoothGattCharacteristic? = null
    private var event: BluetoothGattCharacteristic? = null
    private val controlQueue = ArrayDeque<ByteArray>()
    private val pendingSyncDeletes = ArrayDeque<HighlightEntity>()
    private data class PendingHighlightPut(val highlight: HighlightEntity, val bookPath: String)
    private val pendingSyncUpserts = ArrayDeque<PendingHighlightPut>()
    private var controlWritePending = false
    private var controlInFlight: ByteArray? = null
    private var phoneChangeSyncActive = false
    private var wifiHandoffActive = false
    private var pendingBookActions = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingNotifications: List<PhoneNotification> = emptyList()

    private data class PendingAnnotation(
        val metadata: JSONObject,
        val quote: java.io.ByteArrayOutputStream,
        val note: java.io.ByteArrayOutputStream,
    )
    private var pendingAnnotation: PendingAnnotation? = null

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        createNotificationChannel()
        startForeground(41, notification("Looking for CrossPoint Reader"))
        if (!connectKnownReaderAutomatically()) startScan()
    }

    override fun onDestroy() {
        stopScan()
        runCatching { unregisterReceiver(bondReceiver) }
        unregisterNetwork()
        closeGatt()
        mutableState.value = UiState()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_REFRESH_NOTIFICATION_SETTINGS -> {
                if (control != null && gattConnected) sendHello()
                else ensureReaderConnection()
            }
            ACTION_BACKGROUND_SCAN -> handleBackgroundScan(intent)
        }
        return START_STICKY
    }

    private fun hasConnectPermission() =
        Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() =
        Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

    private fun knownReaderAddress(): String? =
        getSharedPreferences(CONNECTION_PREFS, Context.MODE_PRIVATE)
            .getString(READER_ADDRESS, null)

    private fun rememberReader(device: BluetoothDevice) {
        if (!hasConnectPermission() || device.bondState != BluetoothDevice.BOND_BONDED) return
        getSharedPreferences(CONNECTION_PREFS, Context.MODE_PRIVATE).edit()
            .putString(READER_ADDRESS, device.address).apply()
    }

    private fun forgetKnownReader() {
        getSharedPreferences(CONNECTION_PREFS, Context.MODE_PRIVATE).edit()
            .remove(READER_ADDRESS).apply()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun scanInternal() {
        scanner = adapter.bluetoothLeScanner ?: run {
            updateState("Bluetooth is unavailable")
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE)).build()
        scanner?.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        callbackScanActive = true
        updateState("Scanning for reader")
    }

    private fun startScan() {
        if (wifiHandoffActive || callbackScanActive || pendingIntentScanActive || gatt != null) return
        if (hasScanPermission()) scanInternal() else updateState("Bluetooth permission required")
    }

    private fun stopScan() {
        if (hasScanPermission()) {
            if (callbackScanActive) scanner?.stopScan(scanCallback)
            if (pendingIntentScanActive) scanner?.stopScan(backgroundScanPendingIntent())
        }
        callbackScanActive = false
        pendingIntentScanActive = false
        scanner = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBackgroundScanInternal() {
        if (pendingIntentScanActive || wifiHandoffActive) return
        scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE)).build()
        val result = scanner?.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
            backgroundScanPendingIntent(),
        )
        if (result == 0) {
            pendingIntentScanActive = true
            updateState("Waiting for reader advertisement")
        } else {
            scanner = null
            updateState("Could not start background Bluetooth scan (${result ?: -1})")
        }
    }

    private fun startBackgroundScan() {
        if (hasScanPermission()) startBackgroundScanInternal()
    }

    private fun backgroundScanPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            42,
            Intent(this, CrossPointBleService::class.java).setAction(ACTION_BACKGROUND_SCAN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasConnectPermission()) return
            updateState(
                mutableState.value.status,
                lastReaderSeenAt = System.currentTimeMillis(),
            )
            stopScan()
            val device = result.device
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                rememberReader(device)
                connectGatt(device, autoConnect = true)
            } else {
                pendingBondDevice = device
                updateState("Enter the reader's pairing code", connected = false)
                if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) {
                    pendingBondDevice = null
                    updateState("Could not start Bluetooth pairing")
                    startScan()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            callbackScanActive = false
            scanner = null
            updateState("Bluetooth scan failed ($errorCode)")
            if (knownReaderAddress() != null) startBackgroundScan()
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED || !hasConnectPermission()) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (
                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ==
                    BluetoothDevice.BOND_NONE &&
                device.address == knownReaderAddress()
            ) {
                forgetKnownReader()
                closeGatt()
                startScan()
                return
            }
            val pending = pendingBondDevice ?: return
            if (device.address != pending.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondDevice = null
                    rememberReader(device)
                    connectGatt(device, autoConnect = true)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondDevice = null
                    updateState("Bluetooth pairing was cancelled")
                    Handler(Looper.getMainLooper()).postDelayed({ startScan() }, 1000)
                }
            }
        }
    }

    private fun connectKnownReaderAutomatically(): Boolean {
        if (!hasConnectPermission()) return false
        val address = knownReaderAddress() ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            forgetKnownReader()
            return false
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            forgetKnownReader()
            return false
        }
        connectGatt(device, autoConnect = true)
        startBackgroundScan()
        return true
    }

    private fun ensureReaderConnection() {
        if (gatt != null || callbackScanActive || pendingIntentScanActive || wifiHandoffActive) return
        if (!connectKnownReaderAutomatically()) startScan()
    }

    private fun connectGatt(device: BluetoothDevice, autoConnect: Boolean) {
        if (!hasConnectPermission()) return
        rememberReader(device)
        updateState(if (autoConnect) "Waiting for paired reader" else "Connecting")
        stopScan()
        closeGatt()
        autoConnectGatt = autoConnect
        gattConnected = false
        gatt = device.connectGatt(
            this@CrossPointBleService, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun handleBackgroundScan(intent: Intent) {
        if (!hasConnectPermission() || wifiHandoffActive) return
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            pendingIntentScanActive = false
            scanner = null
            updateState("Background Bluetooth scan failed ($error)")
            return
        }
        val results = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
            )
        }.orEmpty()
        val knownAddress = knownReaderAddress() ?: run {
            stopScan()
            startScan()
            return
        }
        val result = results.firstOrNull {
            it.device.address == knownAddress
        } ?: return
        updateState(
            "Reader advertisement detected",
            lastReaderSeenAt = System.currentTimeMillis(),
        )
        stopScan()
        val pendingGatt = gatt
        if (pendingGatt != null && autoConnectGatt) {
            updateState(
                "Reader seen; waiting for automatic connection",
                lastReaderSeenAt = System.currentTimeMillis(),
            )
            Handler(Looper.getMainLooper()).postDelayed({
                if (gatt === pendingGatt && !gattConnected) {
                    connectGatt(result.device, autoConnect = true)
                    startBackgroundScan()
                }
            }, 3000)
        } else {
            connectGatt(result.device, autoConnect = true)
            startBackgroundScan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!hasConnectPermission()) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt !== g) {
                    g.close()
                    return
                }
                gattConnected = true
                rememberReader(g.device)
                stopScan()
                updateState(
                    "Negotiating Bluetooth link",
                    connected = true,
                    lastReaderSeenAt = System.currentTimeMillis(),
                    lastConnectedAt = System.currentTimeMillis(),
                )
                if (!g.requestMtu(517)) g.discoverServices()
            } else {
                clearProtocolState()
                gattConnected = false
                if (gatt !== g) {
                    g.close()
                    return
                }
                if (autoConnectGatt) {
                    if (!wifiHandoffActive) {
                        updateState("Waiting for reader to wake")
                        startBackgroundScan()
                    }
                } else {
                    g.close()
                    gatt = null
                    autoConnectGatt = false
                    if (!wifiHandoffActive) {
                        updateState("Reconnecting to paired reader")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!connectKnownReaderAutomatically()) startScan()
                        }, 500)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!hasConnectPermission()) return
            updateState("Discovering services", connected = true)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !hasConnectPermission()) return
            val service = g.getService(BleProtocol.SERVICE) ?: return
            control = service.getCharacteristic(BleProtocol.CONTROL)
            event = service.getCharacteristic(BleProtocol.EVENT)
            val eventCharacteristic = event ?: return
            g.setCharacteristicNotification(eventCharacteristic, true)
            val descriptor = eventCharacteristic.getDescriptor(BleProtocol.CLIENT_CONFIG)
            if (descriptor == null) {
                finishGattSetup()
            } else {
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    writeDescriptorLegacy(g, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                if (!started) finishGattSetup()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == BleProtocol.CLIENT_CONFIG) finishGattSetup()
        }

        @Deprecated("Deprecated by Android")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleEvent(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleEvent(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == BleProtocol.CONTROL) {
                val failed = if (status == BluetoothGatt.GATT_SUCCESS) null else controlInFlight
                controlWritePending = false
                controlInFlight = null
                if (failed != null) controlQueue.addFirst(failed)
                drainControlQueue()
            }
        }
    }

    private fun finishGattSetup() = sendHello()

    private fun sendHello() = lifecycleScope.launch(Dispatchers.IO) {
        val pendingActions = database.libraryDao().pendingDeviceActionCount()
        val notificationInterval =
            NotificationRepository.intervalMinutes(this@CrossPointBleService)
        val notificationAccess =
            NotificationManagerCompat.getEnabledListenerPackages(this@CrossPointBleService)
                .contains(packageName)
        pendingBookActions = pendingActions
        updateState("Connected", connected = true)
        writeControl(
            JSONObject().put("op", "hello").put("version", BleProtocol.VERSION)
                .put("pendingActions", pendingActions)
                .put(
                    "notificationPollMinutes",
                    if (
                        NotificationRepository.enabled(this@CrossPointBleService) &&
                        (notificationInterval == 1 || notificationAccess)
                    ) notificationInterval else 0
                )
        )
    }

    private fun handleEvent(bytes: ByteArray) {
        val message = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        when (message.optString("op")) {
            "sync_begin" -> beginReaderSync(message)
            "annotation_begin" -> beginAnnotation(message)
            "annotation_quote" -> appendAnnotationQuote(message)
            "annotation_note" -> appendAnnotationNote(message)
            "annotation_delete_ack" -> acknowledgeDelete(message)
            "annotation_put_next" -> sendAnnotationQuote(message)
            "annotation_put_note_next" -> sendAnnotationNote(message)
            "annotation_put_ack" -> acknowledgePut(message)
            "notification_poll" -> beginNotificationDelivery(message)
            "notification_next" -> sendNotification(message)
            "notification_chunk" -> sendNotificationChunk(message)
            "notifications_ack" -> updateState(
                "Notifications delivered",
                connected = true,
                progress = 100,
                lastNotificationDeliveryAt = System.currentTimeMillis(),
            )
            "sync_complete" -> {
                updateState("Highlights synchronized", connected = true, progress = 100)
                lifecycleScope.launch(Dispatchers.IO) {
                    pendingBookActions = database.libraryDao().pendingDeviceActionCount()
                    if (pendingBookActions > 0) {
                        writeControl(JSONObject().put("op", "wifi_request"))
                    } else {
                        writeControl(JSONObject().put("op", "wifi_skip"))
                    }
                }
            }
            "wifi_offer" -> connectToReaderWifi(message)
            "error" -> updateState(message.optString("message", "Reader error"), connected = true)
        }
    }

    private fun beginNotificationDelivery(message: JSONObject) {
        val limit = message.optInt("limit", 5).coerceIn(0, 10)
        pendingNotifications = if (NotificationRepository.enabled(this)) {
            NotificationRepository.latest(this, limit)
        } else {
            emptyList()
        }
        updateState(
            "Delivering ${pendingNotifications.size} notification(s)",
            connected = true,
            capturedNotifications = pendingNotifications.size,
        )
        writeControl(
            JSONObject().put("op", "notifications_begin").put("count", pendingNotifications.size)
        )
    }

    private fun sendNotification(message: JSONObject) {
        val index = message.optInt("index", -1)
        val item = pendingNotifications.getOrNull(index) ?: return
        val title = utf8Limited(item.title, 240)
        val text = utf8Limited(item.text, 600)
        writeControl(
            JSONObject().put("op", "notification_begin")
                .put("index", index)
                .put("key", utf8Limited(item.key, 80).toString(Charsets.UTF_8))
                .put("app", utf8Limited(item.app, 64).toString(Charsets.UTF_8))
                .put("timestamp", item.timestamp)
                .put("titleBytes", title.size)
                .put("textBytes", text.size)
        )
    }

    private fun sendNotificationChunk(message: JSONObject) {
        val index = message.optInt("index", -1)
        val item = pendingNotifications.getOrNull(index) ?: return
        val field = message.optString("field")
        val bytes = when (field) {
            "title" -> utf8Limited(item.title, 240)
            "text" -> utf8Limited(item.text, 600)
            else -> return
        }
        val offset = message.optInt("offset", -1)
        if (offset < 0 || offset > bytes.size) return
        val end = minOf(offset + 128, bytes.size)
        val hex = bytes.copyOfRange(offset, end)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        writeControl(
            JSONObject().put("op", "notification_data")
                .put("index", index)
                .put("field", field)
                .put("offset", offset)
                .put("hex", hex)
        )
    }

    private fun utf8Limited(value: String, maxBytes: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return bytes.copyOf(end)
    }

    private fun beginReaderSync(message: JSONObject) = lifecycleScope.launch(Dispatchers.IO) {
        val path = message.optString("bookPath")
        val sha256 = message.optString("bookSha256").lowercase()
        if (path.isNotBlank() && sha256.matches(Regex("[0-9a-f]{64}"))) {
            var book = database.libraryDao().bookBySha256(sha256)
            if (book == null) {
                val filename = path.substringAfterLast('/').ifBlank { "Reader book.epub" }
                book = BookEntity(
                    id = "reader-${sha256.take(32)}",
                    uri = "",
                    sha256 = sha256,
                    title = filename.substringBeforeLast('.').ifBlank { "Reader book" },
                    author = "",
                    sourceFileName = filename,
                    size = 0,
                    modifiedAt = System.currentTimeMillis(),
                    desiredOnDevice = true,
                    devicePath = path,
                    installedSha256 = sha256,
                )
                database.libraryDao().upsertBook(book)
            } else {
                database.libraryDao().associateReaderBook(book.id, path, sha256)
            }
        }
        sendPendingPhoneChanges()
    }

    private fun sendPendingPhoneChanges() = lifecycleScope.launch(Dispatchers.IO) {
        updateState("Synchronizing highlights", connected = true)
        val deletes = database.libraryDao().pendingDeletes()
        val upserts = database.libraryDao().pendingUpserts().mapNotNull { highlight ->
            val path = database.libraryDao().book(highlight.bookId)?.devicePath
            if (path == null || highlight.quote.isEmpty() || highlight.spineHref.isEmpty()) null
            else PendingHighlightPut(highlight, path)
        }
        synchronized(this@CrossPointBleService) {
            pendingSyncDeletes.clear()
            pendingSyncDeletes.addAll(deletes)
            pendingSyncUpserts.clear()
            pendingSyncUpserts.addAll(upserts)
            phoneChangeSyncActive = true
        }
        sendNextPhoneChange()
    }

    @Synchronized
    private fun sendNextPhoneChange() {
        val highlight = pendingSyncDeletes.firstOrNull()
        if (highlight != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val book = database.libraryDao().book(highlight.bookId)
                val path = book?.devicePath
                if (path == null) {
                    database.libraryDao().acknowledgeDelete(highlight.id)
                    synchronized(this@CrossPointBleService) {
                        if (pendingSyncDeletes.isNotEmpty()) pendingSyncDeletes.removeFirst()
                    }
                    sendNextPhoneChange()
                } else {
                    writeControl(
                        JSONObject().put("op", "annotation_delete")
                            .put("bookPath", path).put("id", highlight.id)
                    )
                }
            }
            return
        }
        val put = pendingSyncUpserts.firstOrNull()
        if (put == null) {
            phoneChangeSyncActive = false
            writeControl(JSONObject().put("op", "phone_changes_complete"))
            return
        }
        val item = put.highlight
        writeControl(
            JSONObject().put("op", "annotation_put_begin")
                .put("bookPath", put.bookPath)
                .put("id", item.id)
                .put("revision", item.revision)
                .put("spine", item.spineHref)
                .put("startBlock", item.startBlock)
                .put("startOffset", item.startOffset)
                .put("endBlock", item.endBlock)
                .put("endOffset", item.endOffset)
                .put("page", item.page)
                .put("line", item.line)
                .put("createdAt", item.createdAt)
                .put("updatedAt", item.updatedAt)
                .put("quoteBytes", item.quote.toByteArray(Charsets.UTF_8).size)
                .put("noteBytes", item.note.toByteArray(Charsets.UTF_8).size)
        )
    }

    @Synchronized
    private fun sendAnnotationQuote(message: JSONObject) {
        val put = pendingSyncUpserts.firstOrNull() ?: return
        if (put.highlight.id != message.optString("id")) return
        val bytes = put.highlight.quote.toByteArray(Charsets.UTF_8)
        val offset = message.optInt("offset", -1)
        if (offset < 0 || offset >= bytes.size) return
        val end = minOf(offset + 128, bytes.size)
        val hex = bytes.copyOfRange(offset, end).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        writeControl(
            JSONObject().put("op", "annotation_put_quote")
                .put("id", put.highlight.id).put("offset", offset).put("hex", hex)
        )
    }

    @Synchronized
    private fun sendAnnotationNote(message: JSONObject) {
        val put = pendingSyncUpserts.firstOrNull() ?: return
        if (put.highlight.id != message.optString("id")) return
        val bytes = put.highlight.note.toByteArray(Charsets.UTF_8)
        val offset = message.optInt("offset", -1)
        if (offset < 0 || offset >= bytes.size) return
        val end = minOf(offset + 128, bytes.size)
        val hex = bytes.copyOfRange(offset, end).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        writeControl(
            JSONObject().put("op", "annotation_put_note")
                .put("id", put.highlight.id).put("offset", offset).put("hex", hex)
        )
    }

    private fun acknowledgePut(message: JSONObject) = lifecycleScope.launch(Dispatchers.IO) {
        val id = message.optString("id")
        val acknowledged = synchronized(this@CrossPointBleService) {
            if (pendingSyncUpserts.firstOrNull()?.highlight?.id == id) {
                pendingSyncUpserts.removeFirst()
                true
            } else {
                false
            }
        }
        if (!acknowledged) return@launch
        database.libraryDao().acknowledgeUpsert(id)
        if (phoneChangeSyncActive) sendNextPhoneChange()
    }

    private fun acknowledgeDelete(message: JSONObject) = lifecycleScope.launch(Dispatchers.IO) {
        val id = message.getString("id")
        val highlight = database.libraryDao().highlight(id) ?: return@launch
        database.libraryDao().acknowledgeDelete(id)
        database.libraryDao().markExportDirty(highlight.bookId)
        AnnotationExporter.schedule(this@CrossPointBleService, highlight.bookId)
        synchronized(this@CrossPointBleService) {
            if (pendingSyncDeletes.firstOrNull()?.id == id) pendingSyncDeletes.removeFirst()
        }
        if (phoneChangeSyncActive) sendNextPhoneChange()
    }

    private fun beginAnnotation(message: JSONObject) {
        pendingAnnotation = PendingAnnotation(
            message,
            java.io.ByteArrayOutputStream(message.optInt("quoteBytes")),
            java.io.ByteArrayOutputStream(message.optInt("noteBytes")),
        )
        if (message.optBoolean("deleted") || message.optInt("quoteBytes") == 0) {
            storeAnnotation(message, "", "")
        } else {
            writeControl(
                JSONObject().put("op", "annotation_next")
                    .put("id", message.getString("id")).put("offset", 0)
            )
        }
    }

    private fun appendAnnotationQuote(message: JSONObject) {
        val pending = pendingAnnotation ?: return
        if (pending.metadata.getString("id") != message.getString("id")) return
        val bytes = runCatching {
            message.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return
        if (message.getLong("offset") != pending.quote.size().toLong()) return
        pending.quote.write(bytes)
        if (message.optBoolean("done")) {
            if (pending.metadata.optInt("noteBytes") > 0) {
                writeControl(
                    JSONObject().put("op", "annotation_note_next")
                        .put("id", message.getString("id")).put("offset", 0)
                )
            } else {
                storeAnnotation(pending.metadata, pending.quote.toByteArray().toString(Charsets.UTF_8), "")
            }
        } else {
            writeControl(
                JSONObject().put("op", "annotation_next")
                    .put("id", message.getString("id")).put("offset", pending.quote.size())
            )
        }
    }

    private fun appendAnnotationNote(message: JSONObject) {
        val pending = pendingAnnotation ?: return
        if (pending.metadata.getString("id") != message.getString("id")) return
        val bytes = runCatching {
            message.getString("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull() ?: return
        if (message.getLong("offset") != pending.note.size().toLong()) return
        pending.note.write(bytes)
        if (message.optBoolean("done")) {
            storeAnnotation(
                pending.metadata,
                pending.quote.toByteArray().toString(Charsets.UTF_8),
                pending.note.toByteArray().toString(Charsets.UTF_8),
            )
        } else {
            writeControl(
                JSONObject().put("op", "annotation_note_next")
                    .put("id", message.getString("id")).put("offset", pending.note.size())
            )
        }
    }

    private fun storeAnnotation(message: JSONObject, quote: String, note: String) = lifecycleScope.launch(Dispatchers.IO) {
        val path = message.optString("bookPath")
        val sha256 = message.optString("bookSha256").lowercase()
        var book = sha256.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?.let { database.libraryDao().bookBySha256(it) }
            ?: database.libraryDao().bookByDevicePath(path)
        if (book == null) {
            val filename = path.substringAfterLast('/')
            book = database.libraryDao().bookByFilename(filename)
            if (book != null) database.libraryDao().associateDevicePath(book.id, path)
        }
        if (book != null) {
            val existing = database.libraryDao().highlight(message.getString("id"))
            val incomingSequence = message.getLong("sequence")
            val incomingDeleted = message.optBoolean("deleted")
            val now = System.currentTimeMillis()
            val incomingCreatedAt = message.optLong("createdAt").takeIf { it > 0 } ?: now
            val incomingUpdatedAt = message.optLong("updatedAt").takeIf { it > 0 } ?: now
            val localDeletionWins = existing?.deleted == true && !incomingDeleted &&
                existing.deviceSequence >= incomingSequence
            if (!localDeletionWins && (existing == null || existing.deviceSequence <= incomingSequence)) {
                database.libraryDao().upsertHighlight(
                    HighlightEntity(
                        id = message.getString("id"),
                        bookId = book.id,
                        revision = message.getString("revision"),
                        spineHref = message.getString("spine"),
                        startBlock = message.getLong("startBlock"),
                        startOffset = message.getLong("startOffset"),
                        endBlock = message.getLong("endBlock"),
                        endOffset = message.getLong("endOffset"),
                        quote = quote,
                        page = message.optLong("page"),
                        line = message.optLong("line"),
                        note = if (existing != null && existing.updatedAt > incomingUpdatedAt) existing.note else note,
                        deviceSequence = incomingSequence,
                        deleted = incomingDeleted,
                        createdAt = existing?.createdAt ?: incomingCreatedAt,
                        updatedAt = maxOf(existing?.updatedAt ?: 0L, incomingUpdatedAt),
                        deletedAt = if (incomingDeleted) now else existing?.deletedAt,
                        pendingReaderDelete = false,
                        pendingReaderUpsert = false,
                    )
                )
            }
            database.libraryDao().markExportDirty(book.id)
            AnnotationExporter.schedule(this@CrossPointBleService, book.id)
        }
        writeControl(
            JSONObject().put("op", "annotation_ack")
                .put("id", message.getString("id")).put("sequence", message.getLong("sequence"))
        )
        pendingAnnotation = null
    }

    private fun connectToReaderWifi(message: JSONObject) {
        if (Build.VERSION.SDK_INT < 29) {
            updateState("Wi-Fi book sync requires Android 10 or newer", connected = true)
            return
        }
        unregisterNetwork()
        wifiHandoffActive = true
        val ssid = message.getString("ssid")
        val password = message.getString("password")
        updateState(
            "Approve the Android Wi-Fi request for $ssid",
            connected = true,
            wifiSsid = ssid,
            wifiPassword = password,
        )
        val host = "${message.getString("ip")}:${message.optInt("port", 80)}"
        val token = message.getString("token")
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                writeControl(JSONObject().put("op", "wifi_connected"))
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = CompanionWifiSync(
                        this@CrossPointBleService, network, host, token
                    ) { status, progress -> updateState(status, progress = progress) }.run()
                    updateState(
                        result.exceptionOrNull()?.message ?: "Synchronization complete",
                        progress = if (result.isSuccess) 100 else 0,
                        wifiSsid = null,
                        wifiPassword = null,
                    )
                    wifiHandoffActive = false
                    unregisterNetwork()
                    Handler(Looper.getMainLooper()).postDelayed({ ensureReaderConnection() }, 1500)
                }
            }

            override fun onUnavailable() {
                updateState("Could not connect to reader hotspot")
                wifiHandoffActive = false
                unregisterNetwork()
                ensureReaderConnection()
            }

            override fun onLost(network: Network) {
                if (wifiHandoffActive) updateState("Reader hotspot closed")
            }
        }
        networkCallback = callback
        Handler(Looper.getMainLooper()).post {
            try {
                connectivity.requestNetwork(request, callback, 60_000)
            } catch (error: SecurityException) {
                wifiHandoffActive = false
                unregisterNetwork()
                updateState(
                    "Android denied local-network control: ${error.message}",
                    connected = true,
                    wifiSsid = ssid,
                    wifiPassword = password,
                )
                ensureReaderConnection()
            } catch (error: RuntimeException) {
                wifiHandoffActive = false
                unregisterNetwork()
                updateState(
                    "Could not request reader Wi-Fi: ${error.message}",
                    connected = true,
                    wifiSsid = ssid,
                    wifiPassword = password,
                )
                ensureReaderConnection()
            }
        }
    }

    private fun unregisterNetwork() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    @Synchronized
    private fun writeControl(message: JSONObject) {
        val bytes = message.toString().toByteArray()
        if (bytes.size > 512) return
        controlQueue.add(bytes)
        drainControlQueue()
    }

    @Synchronized
    private fun drainControlQueue() {
        if (controlWritePending || controlQueue.isEmpty() || !hasConnectPermission()) return
        val characteristic = control ?: return
        val g = gatt ?: return
        val bytes = controlQueue.removeFirst()
        controlWritePending = true
        controlInFlight = bytes
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(
                characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            writeCharacteristicLegacy(g, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (!started) {
            controlWritePending = false
            controlInFlight = null
            controlQueue.addFirst(bytes)
        }
    }

    @Synchronized
    private fun clearProtocolState() {
        control = null
        event = null
        controlWritePending = false
        controlInFlight = null
        controlQueue.clear()
        pendingSyncDeletes.clear()
        pendingSyncUpserts.clear()
        phoneChangeSyncActive = false
        pendingAnnotation = null
    }

    private fun closeGatt() {
        val current = gatt
        gatt = null
        gattConnected = false
        autoConnectGatt = false
        clearProtocolState()
        if (hasConnectPermission()) {
            current?.disconnect()
            current?.close()
        }
    }

    private fun updateState(
        status: String,
        connected: Boolean = false,
        progress: Int = 0,
        wifiSsid: String? = mutableState.value.wifiSsid,
        wifiPassword: String? = mutableState.value.wifiPassword,
        capturedNotifications: Int = mutableState.value.capturedNotifications,
        lastReaderSeenAt: Long = mutableState.value.lastReaderSeenAt,
        lastConnectedAt: Long = mutableState.value.lastConnectedAt,
        lastNotificationDeliveryAt: Long = mutableState.value.lastNotificationDeliveryAt,
    ) {
        mutableState.value = UiState(
            status,
            connected,
            progress,
            wifiSsid,
            wifiPassword,
            capturedNotifications,
            lastReaderSeenAt,
            lastConnectedAt,
            lastNotificationDeliveryAt,
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(41, notification(status))
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        descriptor.value = value
        return gatt.writeDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        characteristic.writeType = writeType
        characteristic.value = value
        return gatt.writeCharacteristic(characteristic)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("crosspoint_ble", "Reader connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, "crosspoint_ble")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("CrossPoint Companion")
        .setContentText(text)
        .setOngoing(true)
        .build()
}
