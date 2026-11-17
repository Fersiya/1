package com.lumalink.bridge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
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
        val LIGHT_CHAR_NOTIFY: UUID = UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb")
        val LIGHT_CHAR_WRITE: UUID = UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val statusFlow = MutableStateFlow("Готов к подключению")
        val isRunning = MutableStateFlow(false)
        val liveColorFlow = MutableStateFlow(Color.parseColor("#1E293B"))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val writeQueue = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var writeAck: CompletableDeferred<Unit>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBridge()
            return START_NOT_STICKY
        }

        val pcIp = intent?.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
        val bleMac = intent.getStringExtra(EXTRA_MAC) ?: return START_NOT_STICKY

        startForegroundServiceNotification()
        acquireWakeLock()
        isRunning.value = true
        updateStatus("Подключение к ПК ($pcIp)...")

        connectWebSocket(pcIp, bleMac)
        return START_STICKY
    }

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
                updateStatus("ПК на связи. Подключаем лампу...")
                connectBle(bleMac)
                startWriteLoop()
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
                updateStatus("Нет связи с ПК: ${t.message}")
                stopBridge()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                updateStatus("Соединение с ПК закрыто")
                stopBridge()
            }
        })
    }

    private fun connectBle(mac: String) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(mac)
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                updateStatus("Bluetooth подключён, настройка канала...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateStatus("Лампа отключилась")
                webSocket?.send("""{"type":"ble_disconnected"}""")
                stopBridge()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateStatus("Ошибка чтения сервисов лампы")
                return
            }
            val service = gatt.getService(LIGHT_SERVICE_PRIMARY) ?: gatt.getService(LIGHT_SERVICE_ALT)
            if (service == null) {
                updateStatus("Выбрано неверное устройство (это не умная лампа)")
                stopBridge()
                return
            }

            val notifyChar = service.getCharacteristic(LIGHT_CHAR_NOTIFY)
            writeChar = service.getCharacteristic(LIGHT_CHAR_WRITE)

            if (notifyChar == null || writeChar == null) {
                updateStatus("Канал управления лампой не найден")
                stopBridge()
                return
            }

            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                notifyReadyToServer()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            notifyReadyToServer()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(Unit)
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
        updateStatus("Авторизация лампы...")
        webSocket?.send("""{"type":"ble_connected"}""")
    }

    private fun startWriteLoop() {
        scope.launch {
            for (chunk in writeQueue) {
                val gatt = bluetoothGatt ?: break
                val char = writeChar ?: break

                val deferred = CompletableDeferred<Unit>()
                writeAck = deferred

                val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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
                    withTimeoutOrNull(400) { deferred.await() }
                }
            }
        }
    }

    private fun updateStatus(msg: String) {
        statusFlow.value = msg
    }

    private fun stopBridge() {
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
