package com.lumalink.bridge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_IP = "pc_ip"
        const val EXTRA_MAC = "ble_mac"

        val LIGHT_SERVICE_PRIMARY: UUID = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb")
        val LIGHT_SERVICE_ALT: UUID = UUID.fromString("0000a201-0000-1000-8000-00805f9b34fb")
        val LIGHT_SERVICE_TUYA_FD50: UUID = UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val statusFlow = MutableStateFlow("Готов к подключению")
        val isRunning = MutableStateFlow(false)
        val liveColorFlow = MutableStateFlow(Color.parseColor("#1E293B"))
    }

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val writeQueue = Channel<ByteArray>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var writeAck: CompletableDeferred<Int>? = null
    private var readAck: CompletableDeferred<ByteArray?>? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var bleConnectedOnce = false

    @Volatile
    private var negotiatedMtu = 23

    private var targetMac: String = ""
    private var lastAdvHex: String = ""
    private var connectAttempt = 0
    private val maxConnectAttempts = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge("Мост остановлен")
            return START_NOT_STICKY
        }

        val pcIp = intent?.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
        val bleMac = intent?.getStringExtra(EXTRA_MAC) ?: return START_NOT_STICKY

        // После stopBridge() scope отменён, а сервис мог остаться тем же экземпляром — пересоздаём.
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        while (writeQueue.tryReceive().isSuccess) { /* сбрасываем устаревшие пакеты */ }

        stopping = false
        bleConnectedOnce = false
        negotiatedMtu = 23
        targetMac = bleMac
        lastAdvHex = ""
        connectAttempt = 0

        startForegroundServiceNotification()
        acquireWakeLock()
        isRunning.value = true
        updateStatus("Подключение к ПК ($pcIp)...")

        connectWebSocket(pcIp, bleMac)
        return START_STICKY
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun startForegroundServiceNotification() {
        val channelId = "lumalink_bridge_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "LumaLink Мост", NotificationManager.IMPORTANCE_LOW)
        )

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LumaLink активен")
            .setContentText("Синхронизация света ПК → Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaLink::WakeLock").apply {
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun connectWebSocket(pcIp: String, bleMac: String) {
        okHttpClient = OkHttpClient()
        val request = Request.Builder().url("ws://$pcIp:8000/ws").build()

        webSocket = okHttpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                updateStatus("ПК на связи. Поиск лампы по Bluetooth...")
                startWriteLoop()
                scope.launch {
                    connectBleWithRetry(bleMac)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                writeQueue.trySend(bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("rgb")) {
                        val arr = json.getJSONArray("rgb")
                        liveColorFlow.value = Color.rgb(arr.getInt(0), arr.getInt(1), arr.getInt(2))
                    }
                    if (json.has("status")) updateStatus(json.getString("status"))
                    else if (json.has("log")) updateStatus(json.getString("log"))
                } catch (_: Exception) {
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!stopping) {
                    stopBridge("Нет связи с ПК: ${t.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!stopping) {
                    stopBridge("Соединение с ПК закрыто")
                }
            }
        })
    }

    private suspend fun connectBleWithRetry(mac: String) {
        if (stopping) return
        connectAttempt++

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            stopBridge("Включите Bluetooth на телефоне!")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val foundSignal = CompletableDeferred<Boolean>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (result.device.address.equals(mac, ignoreCase = true)) {
                        val rawAdv = result.scanRecord?.bytes
                        if (rawAdv != null) {
                            lastAdvHex = rawAdv.toHex()
                        }
                        foundSignal.complete(true)
                    }
                }
            }
            try {
                val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(listOf(filter), settings, cb)
                withTimeoutOrNull(2500) { foundSignal.await() }
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }

        if (stopping) return
        updateStatus("Подключаем лампу (попытка $connectAttempt/$maxConnectAttempts)...")

        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        val device = adapter.getRemoteDevice(mac)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(gatt)
        } catch (_: Exception) {
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (stopping) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bleConnectedOnce = true
                updateStatus("Bluetooth подключён, согласование MTU...")
                refreshGattCache(gatt)
                scope.launch {
                    delay(200)
                    if (!stopping) {
                        val mtuRequested = try {
                            gatt.requestMtu(247)
                        } catch (_: Exception) {
                            false
                        }
                        if (!mtuRequested) {
                            gatt.discoverServices()
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { gatt.close() } catch (_: Exception) {}

                if (!bleConnectedOnce && connectAttempt < maxConnectAttempts && !stopping) {
                    updateStatus("Повтор подключения BLE (код $status)...")
                    scope.launch {
                        delay(700)
                        connectBleWithRetry(targetMac)
                    }
                } else {
                    webSocket?.send("""{"type":"ble_disconnected"}""")
                    val reason = if (status == 133 || status == 8 || status == 22) {
                        "Лампа занята или недоступна (BLE код $status). Перезапустите Bluetooth!"
                    } else {
                        "Лампа отключилась (BLE код $status)"
                    }
                    stopBridge(reason)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (stopping) return
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                negotiatedMtu = mtu
            }
            scope.launch {
                delay(150)
                if (!stopping) gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (stopping) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopBridge("Ошибка чтения сервисов лампы (код $status)")
                return
            }

            var foundNotify: BluetoothGattCharacteristic? = null
            var foundWrite: BluetoothGattCharacteristic? = null
            val readableChars = mutableListOf<BluetoothGattCharacteristic>()
            val gattDump = JSONArray()

            for (srv in gatt.services) {
                val srvObj = JSONObject()
                srvObj.put("uuid", srv.uuid.toString())
                val chArr = JSONArray()
                for (ch in srv.characteristics) {
                    val u = ch.uuid.toString().lowercase()
                    val props = ch.properties
                    val chObj = JSONObject()
                    chObj.put("uuid", ch.uuid.toString())
                    chObj.put("props", props)
                    chArr.put(chObj)

                    if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        readableChars.add(ch)
                    }

                    if (u.startsWith("00002b10") ||
                        u.endsWith("0a0b0c0d2b10") ||
                        u == "00000002-0000-1001-8001-00805f9b07d0"
                    ) {
                        foundNotify = ch
                    } else if (u.startsWith("00002b11") ||
                        u.endsWith("0a0b0c0d2b11") ||
                        u == "00000001-0000-1001-8001-00805f9b07d0"
                    ) {
                        foundWrite = ch
                    }
                }
                srvObj.put("chars", chArr)
                gattDump.put(srvObj)
            }

            if (foundNotify == null || foundWrite == null) {
                val tuyaService = gatt.getService(LIGHT_SERVICE_TUYA_FD50)
                    ?: gatt.getService(LIGHT_SERVICE_PRIMARY)
                    ?: gatt.getService(LIGHT_SERVICE_ALT)

                if (tuyaService != null) {
                    for (ch in tuyaService.characteristics) {
                        val props = ch.properties
                        if (foundNotify == null &&
                            ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                             (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                        ) {
                            foundNotify = ch
                        }
                        if (foundWrite == null &&
                            ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
                             (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                        ) {
                            foundWrite = ch
                        }
                    }
                }
            }

            if (foundNotify == null || foundWrite == null) {
                stopBridge("Канал не найден")
                return
            }

            writeChar = foundWrite
            val notifyChar = foundNotify

            scope.launch {
                // Читаем все доступные для чтения характеристики (например 00000003 в FD50)
                val readResults = JSONObject()
                for (rch in readableChars) {
                    if (stopping) break
                    val deferred = CompletableDeferred<ByteArray?>()
                    readAck = deferred
                    val ok = try {
                        gatt.readCharacteristic(rch)
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        val bytes = withTimeoutOrNull(800) { deferred.await() }
                        if (bytes != null) {
                            readResults.put(rch.uuid.toString(), bytes.toHex())
                        }
                    }
                }

                val dumpMsg = JSONObject().apply {
                    put("type", "ble_gatt_dump")
                    put("mac", targetMac)
                    put("adv", lastAdvHex)
                    put("gatt", gattDump)
                    put("reads", readResults)
                }
                webSocket?.send(dumpMsg.toString())

                delay(100)
                if (stopping) return@launch

                gatt.setCharacteristicNotification(notifyChar, true)
                val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    val enableVal = if ((notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, enableVal)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = enableVal
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } else {
                    notifyReadyToServer()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val data = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
            readAck?.complete(data)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val data = if (status == BluetoothGatt.GATT_SUCCESS) value else null
            readAck?.complete(data)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope.launch {
                delay(150)
                if (!stopping) notifyReadyToServer()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            webSocket?.send(data.toByteString())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            webSocket?.send(value.toByteString())
        }
    }

    private fun notifyReadyToServer() {
        val props = writeChar?.properties ?: 0
        updateStatus("Канал готов (MTU=$negotiatedMtu)! Авторизация...")
        webSocket?.send("""{"type":"ble_connected","mtu":$negotiatedMtu,"write_props":$props}""")
    }

    private fun startWriteLoop() {
        scope.launch {
            for (chunk in writeQueue) {
                if (stopping) break
                val gatt = bluetoothGatt ?: break
                val char = writeChar ?: break

                val deferred = CompletableDeferred<Int>()
                writeAck = deferred

                val hasWriteResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                val writeType = if (hasWriteResp) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, chunk, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    char.writeType = writeType
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }

                if (started) {
                    val ackStatus = withTimeoutOrNull(500) { deferred.await() }
                    if (ackStatus != null && ackStatus != BluetoothGatt.GATT_SUCCESS) {
                        webSocket?.send("""{"type":"ble_tx_err","status":$ackStatus,"len":${chunk.size}}""")
                    }
                    delay(if (hasWriteResp) 15L else 35L)
                } else {
                    webSocket?.send("""{"type":"ble_tx_err","status":-1,"len":${chunk.size}}""")
                }
            }
        }
    }

    private fun updateStatus(msg: String) {
        statusFlow.value = msg
    }

    private fun stopBridge(finalMessage: String? = null) {
        if (stopping) return
        stopping = true
        if (finalMessage != null) {
            updateStatus(finalMessage)
        }
        isRunning.value = false
        liveColorFlow.value = Color.parseColor("#1E293B")
        scope.cancel()
        try { webSocket?.close(1000, "Stopped") } catch (_: Exception) {}
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.value = false
    }
}
