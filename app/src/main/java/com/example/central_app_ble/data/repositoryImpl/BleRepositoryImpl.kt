package com.example.central_app_ble.data.repositoryImpl

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.data.ble.AndroidBleScanner
import com.example.central_app_ble.data.ble.AndroidBondingManager
import com.example.central_app_ble.data.ble.AndroidGattClient
import com.example.central_app_ble.data.ble.callback.GattEvent
import com.example.central_app_ble.data.ble.callback.GattEventBus
import com.example.central_app_ble.data.mapper.BluetoothDeviceMapper
import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command
import com.example.shared.CommandCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

class BleRepositoryImpl(
    private val appContext: Context,
) : BleRepository {
    /* шина Gatt */
    private val bus = GattEventBus()

    override val logs: Flow<String> =
        bus.events.filterIsInstance<GattEvent.Log>().map { it.line }

    override val notifications: Flow<BleNotification> =
        bus.events.filterIsInstance<GattEvent.Notify>().map { it.notification }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scanner = AndroidBleScanner(appContext)
    private val bonding = AndroidBondingManager(appContext, bus)

    private var gattClient: AndroidGattClient? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override suspend fun scanFirst(timeoutMs: Long): BleDevice? {
        _connectionState.value = ConnectionState.Scanning
        bus.log("scan start")

        val res = scanner.scanFirst(timeoutMs)
        val dev = if (res != null) {
            val mapped = BluetoothDeviceMapper.toDomain(res.device, res.rssi)
            bus.log("FOUND: name=${mapped.name} addr=${mapped.address} rssi=${mapped.rssi}")
            mapped
        } else null

        bus.log("scan stop; selected=${dev?.address ?: "none"}")
        _connectionState.value = ConnectionState.Idle
        return dev
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun connect(device: BleDevice) {
        disconnect()

        _connectionState.value = ConnectionState.Bonding
        bonding.ensureBonded(device.address)
        bus.log("BONDED OK")

        _connectionState.value = ConnectionState.Connecting

        val client = AndroidGattClient(
            context = appContext,
            address = device.address,
            bus = bus
        )
        gattClient = client

        try {
            client.connectAndInit()
            _connectionState.value = ConnectionState.Ready
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "connect/init error")
            disconnect()
            throw e
        }
    }

    override fun disconnect() {
        runCatching { gattClient?.close() }
        gattClient = null
        if (_connectionState.value != ConnectionState.Idle) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun sendCmd(cmd: Command) {
        val client = gattClient ?: error("not connected")
        client.writeCmd(CommandCodec.encode(cmd))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun writeCentralData(bytes: ByteArray) {
        val client = gattClient ?: error("not connected")
        client.writeDataNoResp(bytes)
    }
}