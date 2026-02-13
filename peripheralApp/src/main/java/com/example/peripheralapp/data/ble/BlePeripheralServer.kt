package com.example.peripheralapp.data.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.shared.BleUuids
import com.example.shared.Command
import com.example.shared.CommandCodec
import com.example.shared.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class BlePeripheralServer(
    private val context: Context,
    private val deviceName: String = "BLE-Peripheral",
    private val logger: (String) -> Unit = { android.util.Log.i("BlePeripheral", it) },
) {
    private val logTag = "BlePeripheral"

    private fun log(s: String) = logger("$logTag $s")

    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val subscribedCmd = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribedData = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private lateinit var cmdTxChar: BluetoothGattCharacteristic
    private lateinit var dataTxChar: BluetoothGattCharacteristic

    private val txScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var txJob: Job? = null
    private var txSeq = 0

    private fun startTxStream() {
        if (txJob?.isActive == true) return

        txJob = txScope.launch {
            var tick = 0
            while (isActive) {
                val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE)

                // первые 4 байта - счётчик (удобно для отладки потерь/порядка)
                val s = txSeq++
                buf[0] = (s and 0xFF).toByte()
                buf[1] = ((s shr 8) and 0xFF).toByte()
                buf[2] = ((s shr 16) and 0xFF).toByte()
                buf[3] = ((s shr 24) and 0xFF).toByte()

                if (tick++ % 16 == 0) { // примерно раз в 1секунду = 16*60ms
                    Log.i(logTag, "TX tick seq=$s subscribedData=${subscribedData.size}")
                }

                // шлём всем, кто подписался на DATA_TX
                for (dev in subscribedData) {
                    notifyData(dev, buf)
                }

                delay(Protocol.STREAM_PERIOD_MS)
            }
        }

        log("TX stream started; subscribedData=${subscribedData.size}")
    }

    private fun stopTxStream() {
        txJob?.cancel()
        txJob = null
        Log.i(logTag, "TX stream stopped")
    }


    fun isPeripheralSupported(): Boolean {
        val adv = adapter.bluetoothLeAdvertiser
//        return adv != null && adapter.isMultipleAdvertisementSupported
        return adv != null
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start() {
        require(adapter.isEnabled) { "Bluetooth выключен" }
        adapter.name = deviceName

        startGattServer()
        startAdvertising()

        Log.i("BlePeripheral_START", "BT enabled=${adapter.isEnabled}, adv=${adapter.bluetoothLeAdvertiser}")
    }

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun stop() {
        txJob?.cancel()
        txJob = null

        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiser?.stopAdvertising(advCallback)
            }
        } catch (_: SecurityException) {
        } finally {
            advertiser = null
        }

        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                gattServer?.close()
            }
        } catch (_: SecurityException) {
        } finally {
            gattServer = null
        }

        subscribedCmd.clear()
        subscribedData.clear()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startGattServer() {
        gattServer = btManager.openGattServer(context, gattCallback)
        val server = gattServer ?: error("openGattServer() вернул null")

        val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val cmdRx = BluetoothGattCharacteristic(
            BleUuids.CMD_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        cmdTxChar = BluetoothGattCharacteristic(
            BleUuids.CMD_TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
        }

        val dataRx = BluetoothGattCharacteristic(
            BleUuids.DATA_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        dataTxChar = BluetoothGattCharacteristic(
            BleUuids.DATA_TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
        }

        service.addCharacteristic(cmdRx)
        service.addCharacteristic(cmdTxChar)
        service.addCharacteristic(dataRx)
        service.addCharacteristic(dataTxChar)

        val ok = server.addService(service)
        Log.i(logTag, "addService: $ok")
    }

    private fun cccdDescriptor(): BluetoothGattDescriptor =
        BluetoothGattDescriptor(
            BleUuids.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

    private fun startAdvertising() {
        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: error("Advertising не поддерживается на этом устройстве")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, data, scanResponse, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(logTag, "Advertising failed: $errorCode")
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BlePeripheral", "Advertising started")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(logTag, "Service added status=$status uuid=${service.uuid}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(logTag, "Conn state: ${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCmd.remove(device)
                subscribedData.remove(device)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val charUuid = descriptor.characteristic.uuid

            when (charUuid) {
                BleUuids.CMD_TX -> if (enabled) subscribedCmd.add(device) else subscribedCmd.remove(device)
                BleUuids.DATA_TX -> if (enabled) subscribedData.add(device) else subscribedData.remove(device)
            }
            Log.i(logTag, "CCCD write for $charUuid enabled=$enabled subCmd=${subscribedCmd.size} subData=${subscribedData.size}")

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            Log.i(logTag, "CCCD write for $charUuid enabled=$enabled")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {

                BleUuids.CMD_RX -> {
                    val hex = value.joinToString(" ") { "%02X".format(it) }
                    val cmd = CommandCodec.decode(value)
                    Log.i(logTag, "CMD_RX raw=[$hex] decoded=$cmd")

                    when (cmd) {
                        Command.Ping -> notifyCmd(device, Command.Pong)
                        Command.StartStream -> startTxStream()
                        Command.StopStream -> stopTxStream()
                        null -> Log.w(logTag, "CMD_RX unknown bytes=[$hex]")
                        else -> {}
                    }
                }

                BleUuids.DATA_RX -> {
                    Log.i(logTag, "DATA_RX size=${value.size}")
                    if (value.size == Protocol.STREAM_BLOCK_SIZE) {
                        notifyData(device, value) // эхо
                    } else {
                        Log.w(logTag, "DATA_RX wrong size=${value.size}")
                    }
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyCmd(device: BluetoothDevice, cmd: Command) {
        if (!subscribedCmd.contains(device)) return
        val payload = CommandCodec.encode(cmd)
        notifyCharacteristic(device, cmdTxChar, payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyData(device: BluetoothDevice, data: ByteArray) {
        if (!subscribedData.contains(device)) return
        notifyCharacteristic(device, dataTxChar, data)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyCharacteristic(device: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val server = gattServer ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            server.notifyCharacteristicChanged(device, ch, false, value)
        } else {
            ch.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, ch, false)
        }
    }
}
