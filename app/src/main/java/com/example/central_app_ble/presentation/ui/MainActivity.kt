package com.example.central_app_ble.presentation.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import com.example.centralapp.SimpleGattClient
import com.example.shared.BleUuids
import com.example.shared.Command
import com.example.shared.CommandCodec
import com.example.shared.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tv: TextView
    private val adapter: BluetoothAdapter by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val scanner: BluetoothLeScanner by lazy { adapter.bluetoothLeScanner }

    private val foundDevice = AtomicReference<BluetoothDevice?>(null)
    private var gattClient: SimpleGattClient? = null

    private var isReady = false // флаг проверки, что connect завершен и можно пинговать или стримить

    private var streamJob: Job? = null

    private val blePerms31 = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        log("perm result: $result")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply { textSize = 14f }

        val bScan = Button(this).apply { text = "Scan (5s)" }
        val bConnect = Button(this).apply { text = "Connect" }
        val bPing = Button(this).apply { text = "Ping" }
        val bStreamStart = Button(this).apply { text = "Stream start" }
        val bStreamStop  = Button(this).apply { text = "Stream stop" }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(24, 80, 24, 24)

            addView(bScan)
            addView(bConnect)
            addView(bPing)
            addView(bStreamStart)
            addView(bStreamStop)
            addView(ScrollView(this@MainActivity).apply { addView(tv) })
        }
        setContentView(layout)

        /* обработка кнопок  */
        bScan.setOnClickListener {
            if (!ensurePermsOrRequest()) return@setOnClickListener
            startScan5s()
        }
        bConnect.setOnClickListener {
            if (!ensurePermsOrRequest()) return@setOnClickListener
            scope.launch { connect() }
        }
        bPing.setOnClickListener {
            if (!ensurePermsOrRequest()) return@setOnClickListener
            scope.launch { sendPing() }
        }
        bStreamStart.setOnClickListener {
            if (!ensurePermsOrRequest()) return@setOnClickListener
            if (streamJob?.isActive == true) { log("stream already running"); return@setOnClickListener }
            streamJob = scope.launch { stream10s() }
        }

        bStreamStop.setOnClickListener {
            streamJob?.cancel()
            streamJob = null
            log("stream stopped")
        }

    }

    private fun hasBlePerms(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return blePerms31.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Возвращает true, если права УЖЕ есть. Иначе запускает диалог и возвращает false. */
    private fun ensurePermsOrRequest(): Boolean {
        if (hasBlePerms()) return true
        if (Build.VERSION.SDK_INT >= 31) reqPerms.launch(blePerms31)
        log("Requesting BLE permissions…")
        return false
    }

    @Suppress("MissingPermission") // мы гарантируем check выше в onClick
    private fun startScan5s() {
        foundDevice.set(null)

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                if (foundDevice.get() == null) {
                    foundDevice.set(dev)
                    log("FOUND: name=${dev.name} addr=${dev.address} rssi=${result.rssi}")
                }
            }
            override fun onScanFailed(errorCode: Int) {
                log("scan failed code=$errorCode")
            }
        }

        log("scan start")
        scanner.startScan(listOf(filter), settings, cb)

        scope.launch {
            delay(5_000)
            scanner.stopScan(cb)
            log("scan stop; selected=${foundDevice.get()?.address ?: "none"}")
        }
    }

    @Suppress("MissingPermission")
    private suspend fun connect() {
        val dev = foundDevice.get() ?: run { log("Сначала Scan"); return }

        val client = SimpleGattClient(this, dev) { log(it) } // теперь log безопасный
        gattClient = client

        try {
            client.connectAndInit()
            isReady = true
            log("CONNECTED + INIT OK")
        } catch (e: Exception) {
            isReady = false
            log("CONNECT/INIT FAILED: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sendPing() {
        if (!isReady) { log("Not ready: сначала Connect и дождись INIT OK"); return }
        val c = gattClient ?: run { log("not connected"); return }
        c.writeCmd(CommandCodec.encode(Command.Ping))
        log("PING sent")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun stream10s() {
        val c = gattClient ?: run { log("not connected"); return }
        val start = System.currentTimeMillis()
        var sent = 0

        /* isActive чтобы цикл следил за job.cancel */
        while (currentCoroutineContext().isActive && System.currentTimeMillis() - start < 10_000) {
            val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE) { (it and 0xFF).toByte() }
            c.writeDataNoResp(buf)
            sent++
            delay(Protocol.STREAM_PERIOD_MS)
        }
        log("stream done. blocksSent=$sent")
    }

    private fun log(s: String) {
        tv.post { tv.append("$s\n") }   // можно вызывать с любого потока
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            gattClient?.close()
        } catch (_: SecurityException) { /* если права отозвали */ }
        super.onDestroy()
    }
}
