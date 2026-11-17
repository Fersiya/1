package com.tuya.blebridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var statusText: TextView
    private lateinit var scanBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var listView: ListView

    private val foundDevices = LinkedHashMap<String, String>()
    private val displayList = ArrayList<String>()
    private val macList = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var scanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            startBleScan()
        } else {
            statusText.text = "Нужны разрешения Bluetooth для поиска лампы"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        val title = TextView(this).apply {
            text = "Tuya BLE Ambilight Мост"
            textSize = 20f
            setTextColor(Color.WHITE)
        }

        ipInput = EditText(this).apply {
            hint = "IP адрес ПК (например 192.168.1.45)"
            setText(prefs.getString("pc_ip", "192.168.1."))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        statusText = TextView(this).apply {
            text = "Готов к работе"
            setTextColor(Color.parseColor("#58A6FF"))
            setPadding(0, 20, 0, 20)
        }

        scanBtn = Button(this).apply {
            text = "1. Найти лампу (BLE Scan)"
            setOnClickListener { checkPermissionsAndScan() }
        }

        stopBtn = Button(this).apply {
            text = "Остановить мост"
            setOnClickListener {
                startService(Intent(this@MainActivity, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_STOP
                })
            }
        }

        val hintText = TextView(this).apply {
            text = "Нажмите на лампу в списке ниже, чтобы запустить мост:"
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 8)
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setBackgroundColor(Color.parseColor("#161B22"))
            setOnItemClickListener { _, _, position, _ ->
                stopBleScan()
                val ip = ipInput.text.toString().trim()
                if (ip.isEmpty()) {
                    statusText.text = "Введите IP компьютера!"
                    return@setOnItemClickListener
                }
                prefs.edit().putString("pc_ip", ip).apply()
                val mac = macList[position]

                val intent = Intent(this@MainActivity, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_START
                    putExtra(BridgeService.EXTRA_IP, ip)
                    putExtra(BridgeService.EXTRA_MAC, mac)
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }

        root.addView(title)
        root.addView(ipInput)
        root.addView(statusText)
        root.addView(scanBtn)
        root.addView(stopBtn)
        root.addView(hintText)
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        lifecycleScope.launch {
            BridgeService.statusFlow.collect { msg ->
                statusText.text = msg
            }
        }
    }

    private fun checkPermissionsAndScan() {
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
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startBleScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val name = result.device.name ?: "Без имени (Tuya BLE)"
            val label = "$name\n$mac  |  RSSI: ${result.rssi} dBm"
            if (foundDevices[mac] != label) {
                foundDevices[mac] = label
                displayList.clear()
                macList.clear()
                foundDevices.forEach { (m, l) ->
                    macList.add(m)
                    displayList.add(l)
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun startBleScan() {
        if (scanning) return
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: run {
            statusText.text = "Включите Bluetooth на телефоне!"
            return
        }

        foundDevices.clear()
        displayList.clear()
        macList.clear()
        adapter.notifyDataSetChanged()

        scanning = true
        statusText.text = "Сканирование BLE (10 сек)..."
        scanner.startScan(scanCallback)

        lifecycleScope.launch {
            delay(10_000)
            stopBleScan()
        }
    }

    private fun stopBleScan() {
        if (!scanning) return
        scanning = false
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (!BridgeService.isRunning.value) {
            statusText.text = "Выберите устройство из списка"
        }
    }
}
