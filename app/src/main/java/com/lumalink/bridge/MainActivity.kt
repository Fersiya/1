package com.lumalink.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    data class BleItem(
        val mac: String,
        val name: String,
        val rssi: Int,
        val isSmartLamp: Boolean
    )

    private val bgMain = Color.parseColor("#0B0E14")
    private val bgCard = Color.parseColor("#141923")
    private val bgInput = Color.parseColor("#1C2331")
    private val borderCol = Color.parseColor("#263042")
    private val accentCol = Color.parseColor("#7C5CFF")
    private val cyanCol = Color.parseColor("#00E5FF")
    private val greenCol = Color.parseColor("#10B981")
    private val redCol = Color.parseColor("#EF4444")
    private val textPrimary = Color.parseColor("#F8FAFC")
    private val textMuted = Color.parseColor("#94A3B8")

    private lateinit var ipInput: EditText
    private lateinit var discoveredPcBtn: TextView
    private lateinit var statusText: TextView
    private lateinit var colorOrb: View
    private lateinit var quickConnectBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var devicesContainer: LinearLayout
    private lateinit var emptyListHint: TextView

    private val foundMap = LinkedHashMap<String, BleItem>()
    private var scanning = false
    private var pendingAutoConnectMac: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            val mac = pendingAutoConnectMac
            pendingAutoConnectMac = null
            if (mac != null) {
                launchBridgeService(mac)
            } else {
                startBleScan()
            }
        } else {
            pendingAutoConnectMac = null
            statusText.text = "Разрешите доступ к Bluetooth для поиска лампы"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgMain
        window.navigationBarColor = bgMain

        val prefs = getSharedPreferences("lumalink_prefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("pc_ip", "192.168.1.") ?: "192.168.1."
        val savedMac = prefs.getString("last_mac", null)
        val savedLampName = prefs.getString("last_name", "Умная лампа")

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgMain)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }
        scroll.addView(root)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        colorOrb = View(this).apply {
            background = createCircleDrawable(Color.parseColor("#1E293B"), borderCol)
        }
        headerRow.addView(colorOrb, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(14)
        })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = "LumaLink"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        titleCol.addView(TextView(this).apply {
            text = "Беспроводной мост подсветки"
            textSize = 13f
            setTextColor(textMuted)
        })
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow)

        val statusCard = createCard().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusText = TextView(this).apply {
            text = "Готов к подключению"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyanCol)
        }
        statusCard.addView(statusText)
        root.addView(statusCard, cardParams())

        val pcCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        pcCard.addView(TextView(this).apply {
            text = "1. Компьютер в локальной сети"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })

        discoveredPcBtn = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(greenCol)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = createRoundedDrawable(Color.parseColor("#0F2922"), greenCol, dp(10))
        }
        pcCard.addView(discoveredPcBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        ipInput = EditText(this).apply {
            hint = "IP-адрес ПК (например 192.168.1.45)"
            setText(savedIp)
            textSize = 16f
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = createRoundedDrawable(bgInput, borderCol, dp(12))
        }
        pcCard.addView(ipInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        root.addView(pcCard, cardParams())

        if (!savedMac.isNullOrEmpty()) {
            val quickCard = createCard().apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(16))
            }
            quickCard.addView(TextView(this).apply {
                text = "Последняя лампа"
                textSize = 13f
                setTextColor(textMuted)
            })
            quickConnectBtn = createButton("Подключить $savedLampName", accentCol).apply {
                setOnClickListener {
                    checkPermissionsAndRun(autoMac = savedMac)
                }
            }
            quickCard.addView(quickConnectBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
            root.addView(quickCard, cardParams())
        }

        val scanCard = createCard().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        scanCard.addView(TextView(this).apply {
            text = "2. Выбор лампы по Bluetooth"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        scanBtn = createButton("Найти лампу", accentCol).apply {
            setOnClickListener { checkPermissionsAndRun(autoMac = null) }
        }
        stopBtn = createButton("Остановить", bgInput, borderColor = redCol, textColor = redCol).apply {
            setOnClickListener {
                startService(Intent(this@MainActivity, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_STOP
                })
            }
        }

        btnRow.addView(scanBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply {
            marginEnd = dp(8)
        })
        btnRow.addView(stopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scanCard.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        emptyListHint = TextView(this).apply {
            text = "Нажмите «Найти лампу» и выберите устройство из списка ниже. Умные лампы поднимаются наверх списка."
            textSize = 13f
            setTextColor(textMuted)
            setPadding(0, dp(12), 0, dp(4))
        }
        scanCard.addView(emptyListHint)

        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scanCard.addView(devicesContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(scanCard, cardParams())
        setContentView(scroll)

        lifecycleScope.launch {
            BridgeService.statusFlow.collect { msg ->
                statusText.text = msg
            }
        }
        lifecycleScope.launch {
            BridgeService.liveColorFlow.collect { col ->
                colorOrb.background = createCircleDrawable(col, col)
            }
        }

        startPcDiscovery()
    }

    private fun startPcDiscovery() {
        lifecycleScope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1500
                }
                val payload = "LUMALINK_DISCOVER".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                repeat(6) {
                    if (BridgeService.isRunning.value) return@launch
                    try {
                        socket.send(DatagramPacket(payload, payload.size, broadcastAddr, 8001))
                        val buf = ByteArray(512)
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val senderIp = resp.address?.hostAddress ?: return@repeat
                        val json = JSONObject(String(resp.data, 0, resp.length, Charsets.UTF_8))
                        if (json.optString("service") == "lumalink") {
                            val pcName = json.optString("name", "ПК")
                            withContext(Dispatchers.Main) {
                                discoveredPcBtn.visibility = View.VISIBLE
                                discoveredPcBtn.text = "Найден сервер: $pcName ($senderIp) — нажми, чтобы подставить"
                                discoveredPcBtn.setOnClickListener {
                                    ipInput.setText(senderIp)
                                }
                                if (ipInput.text.toString().endsWith(".")) {
                                    ipInput.setText(senderIp)
                                }
                            }
                            return@launch
                        }
                    } catch (_: Exception) {
                    }
                    delay(2000)
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun checkPermissionsAndRun(autoMac: String?) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingAutoConnectMac = autoMac
            permissionLauncher.launch(missing.toTypedArray())
        } else if (autoMac != null) {
            launchBridgeService(autoMac)
        } else {
            startBleScan()
        }
    }

    private fun launchBridgeService(mac: String, lampName: String? = null) {
        stopBleScan()
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty() || ip.endsWith(".")) {
            statusText.text = "Введите полный IP-адрес компьютера!"
            return
        }
        val prefs = getSharedPreferences("lumalink_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("pc_ip", ip)
            putString("last_mac", mac)
            if (!lampName.isNullOrBlank()) putString("last_name", lampName)
            apply()
        }

        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_IP, ip)
            putExtra(BridgeService.EXTRA_MAC, mac)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address ?: return
            val rawName = result.device.name ?: result.scanRecord?.deviceName
            val isLamp = detectSmartLamp(result)
            val displayName = when {
                !rawName.isNullOrBlank() -> rawName
                isLamp -> "Умная лампа BLE"
                else -> "Неизвестное BLE-устройство"
            }

            val prev = foundMap[mac]
            if (prev == null || prev.rssi != result.rssi || prev.name != displayName) {
                foundMap[mac] = BleItem(mac, displayName, result.rssi, isLamp || (prev?.isSmartLamp == true))
                renderDeviceList()
            }
        }
    }

    private fun detectSmartLamp(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        val uuids = record.serviceUuids ?: emptyList()
        if (uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_PRIMARY)) ||
            uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_ALT)) ||
            uuids.contains(ParcelUuid(BridgeService.LIGHT_SERVICE_TUYA_FD50))
        ) {
            return true
        }
        val sData = record.serviceData
        if (sData != null) {
            for (key in sData.keys) {
                val u = key.uuid.toString().lowercase()
                if (u.startsWith("00001910") || u.startsWith("0000a201") || u.startsWith("0000fd50")) return true
            }
        }
        if (record.getManufacturerSpecificData(0x07D0) != null) {
            return true
        }
        return false
    }

    private fun renderDeviceList() {
        devicesContainer.removeAllViews()
        val sorted = foundMap.values.sortedWith(
            compareByDescending<BleItem> { it.isSmartLamp }.thenByDescending { it.rssi }
        )

        if (sorted.isEmpty()) return
        emptyListHint.text = "Нажмите на лампу ниже, чтобы запустить синхронизацию:"

        for (item in sorted) {
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = createRoundedDrawable(
                    bgInput,
                    if (item.isSmartLamp) accentCol else borderCol,
                    dp(12)
                )
                setOnClickListener {
                    launchBridgeService(item.mac, item.name)
                }
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            titleLine.addView(TextView(this).apply {
                text = item.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
            })

            if (item.isSmartLamp) {
                titleLine.addView(TextView(this).apply {
                    text = "ЛАМПА"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textPrimary)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = createRoundedDrawable(accentCol, accentCol, dp(6))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            }

            leftCol.addView(titleLine)
            leftCol.addView(TextView(this).apply {
                text = item.mac
                textSize = 12f
                setTextColor(textMuted)
                setPadding(0, dp(3), 0, 0)
            })

            val signalBadge = TextView(this).apply {
                val (sigLabel, sigColor) = when {
                    item.rssi >= -68 -> "Отличный\n${item.rssi} dBm" to greenCol
                    item.rssi >= -82 -> "Хороший\n${item.rssi} dBm" to cyanCol
                    else -> "Слабый\n${item.rssi} dBm" to textMuted
                }
                text = sigLabel
                textSize = 11f
                gravity = Gravity.END
                setTextColor(sigColor)
            }

            rowCard.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowCard.addView(signalBadge)

            devicesContainer.addView(rowCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun startBleScan() {
        if (scanning) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: run {
            statusText.text = "Включите Bluetooth на смартфоне!"
            return
        }

        foundMap.clear()
        devicesContainer.removeAllViews()
        scanning = true
        scanBtn.text = "Поиск..."
        statusText.text = "Сканируем эфир Bluetooth (10 сек)..."
        scanner.startScan(scanCallback)

        lifecycleScope.launch {
            delay(10_000)
            stopBleScan()
        }
    }

    private fun stopBleScan() {
        if (!scanning) return
        scanning = false
        scanBtn.text = "Найти лампу"
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (!BridgeService.isRunning.value) {
            statusText.text = if (foundMap.isEmpty()) "Устройства не найдены" else "Выберите лампу из списка"
        }
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = createRoundedDrawable(bgCard, borderCol, dp(16))
    }

    private fun cardParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun createButton(
        label: String,
        bgColor: Int,
        borderColor: Int = bgColor,
        textColor: Int = textPrimary
    ): Button = Button(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(textColor)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = createRoundedDrawable(bgColor, borderColor, dp(12))
    }

    private fun createRoundedDrawable(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }

    private fun createCircleDrawable(fillColor: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dp(2), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
