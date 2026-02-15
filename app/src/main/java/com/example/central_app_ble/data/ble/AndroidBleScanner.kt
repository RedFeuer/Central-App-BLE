package com.example.central_app_ble.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.example.shared.BleUuids
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class AndroidBleScanner @Inject constructor(
    // @ApplicationContext context: Context,
) {
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val scanner: BluetoothLeScanner by lazy { adapter.bluetoothLeScanner }

    data class Result(val device: BluetoothDevice, val rssi: Int)

    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    suspend fun scanFirst(timeoutMs: Long): Result? {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val flow = callbackFlow {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    trySend(Result(result.device, result.rssi))
                }
                override fun onScanFailed(errorCode: Int) {
                    close() // просто завершаем без результата
                }
            }

            scanner.startScan(listOf(filter), settings, callback)

            awaitClose {
                runCatching { scanner.stopScan(callback) }
            }
        }

        return withTimeoutOrNull(timeoutMs) {
            flow.firstOrNull()
        }
    }
}