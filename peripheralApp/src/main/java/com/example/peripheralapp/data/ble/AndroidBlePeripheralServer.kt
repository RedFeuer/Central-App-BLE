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
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.shared.BleUuids
import com.example.shared.Command
import com.example.shared.CommandCodec
import com.example.shared.Protocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class AndroidBlePeripheralServer @Inject constructor (
    @ApplicationContext private val context: Context,
    private val bus: PeripheralEventBus,
) {
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = btManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val subscribedCmd = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribedData = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val connected = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private lateinit var cmdTxChar: BluetoothGattCharacteristic
    private lateinit var dataTxChar: BluetoothGattCharacteristic

    private val txScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var txJob: Job? = null
    private var txSeq = 0

    private val _state = MutableStateFlow(PeripheralState())
    val state: StateFlow<PeripheralState> = _state

    fun isPeripheralSupported(): Boolean = adapter.bluetoothLeAdvertiser != null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(deviceName: String = "BLE-Peripheral") {
        if (!isPeripheralSupported()) {
            _state.value = _state.value.copy(isSupported = false, lastError = "Peripheral/Advertising не поддерживается")
            bus.log("Peripheral/Advertising НЕ поддерживается")
            return
        }

        require(adapter.isEnabled) { "Bluetooth выключен" }

        runCatching { adapter.name = deviceName }.onFailure {
            bus.log("setName failed: ${it.message}")
        }

        startGattServer()
        startAdvertising()

        _state.value = _state.value.copy(isSupported = true, isRunning = true, lastError = null)
        bus.log("Peripheral запущен: advertising + GATT server")
        publishCounters()
    }

    fun stop() {
        stopTxStream()

        runCatching {
            advertiser?.stopAdvertising(advCallback)
        }.onFailure { bus.log("stopAdvertising failed: ${it.message}") }
        advertiser = null

        runCatching {
            gattServer?.close()
        }.onFailure { bus.log("gattServer.close failed: ${it.message}") }
        gattServer = null

        subscribedCmd.clear()
        subscribedData.clear()
        connected.clear()

        _state.value = _state.value.copy(isRunning = false)
        bus.log("Peripheral stopped")
        publishCounters()
    }

    fun clearError() {
        _state.value = _state.value.copy(lastError = null)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startGattServer() {
        gattServer = btManager.openGattServer(context, gattCallback)
        val server = gattServer ?: error("openGattServer() вернул null")

        val service =
            BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

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
        bus.log("addService: $ok")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: error("Advertising не поддерживается")

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
            bus.log("Advertising failed: $errorCode")
            _state.value = _state.value.copy(lastError = "Advertising failed: $errorCode")
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            bus.log("Advertising started")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            bus.log("Service added status=$status uuid=${service.uuid}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            bus.log("Conn state: ${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.remove(device)
                subscribedCmd.remove(device)
                subscribedData.remove(device)
            }
            publishCounters()
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

            bus.log("CCCD write for $charUuid enabled=$enabled subCmd=${subscribedCmd.size} subData=${subscribedData.size}")
            publishCounters()

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
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
                    val cmd = CommandCodec.decode(value)
                    bus.log("CMD_RX decoded=$cmd rawSize=${value.size}")

                    when (cmd) {
                        Command.Ping -> notifyCmd(device, Command.Pong)
                        Command.StartStream -> startTxStream()
                        Command.StopStream -> stopTxStream()
                        null -> bus.log("CMD_RX unknown bytes size=${value.size}")
                        else -> {}
                    }
                }

                BleUuids.DATA_RX -> {
                    bus.log("DATA_RX size=${value.size}")
                    if (value.size == Protocol.STREAM_BLOCK_SIZE) {
                        notifyData(device, value) // echo
                    } else {
                        bus.log("DATA_RX wrong size=${value.size}")
                    }
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun publishCounters() {
        _state.value = _state.value.copy(
            connectedCount = connected.size,
            subscribedCmd = subscribedCmd.size,
            subscribedData = subscribedData.size,
        )
    }

    private fun startTxStream() {
        if (txJob?.isActive == true) return

        txJob = txScope.launch {
            var tick = 0
            while (isActive) {
                val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE)

                val s = txSeq++
                buf[0] = (s and 0xFF).toByte()
                buf[1] = ((s shr 8) and 0xFF).toByte()
                buf[2] = ((s shr 16) and 0xFF).toByte()
                buf[3] = ((s shr 24) and 0xFF).toByte()

                if (tick++ % 16 == 0) {
                    bus.log("TX tick seq=$s subscribedData=${subscribedData.size}")
                }

                for (dev in subscribedData) {
                    notifyData(dev, buf)
                }

                delay(Protocol.STREAM_PERIOD_MS)
            }
        }

        bus.log("TX stream started; subscribedData=${subscribedData.size}")
    }

    private fun stopTxStream() {
        txJob?.cancel()
        txJob = null
        bus.log("TX stream stopped")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyCmd(device: BluetoothDevice, cmd: Command) {
        if (!subscribedCmd.contains(device)) return
        notifyCharacteristic(device, cmdTxChar, CommandCodec.encode(cmd))
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