package com.example.central_app_ble.data.repositoryImpl

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.data.ble.AndroidBleScanner
import com.example.central_app_ble.data.ble.AndroidBondingManager
import com.example.central_app_ble.data.ble.AndroidGattClient
import com.example.central_app_ble.data.ble.callback.AndroidGattClientFactory
import com.example.central_app_ble.data.ble.callback.GattEvent
import com.example.central_app_ble.data.ble.callback.GattEventBus
import com.example.central_app_ble.data.mapper.BluetoothDeviceMapper
import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command
import com.example.shared.CommandCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class BleRepositoryImpl @Inject constructor (
    @ApplicationContext private val appContext: Context,
    private val bus: GattEventBus, /* шина Gatt */
    private val scanner: AndroidBleScanner,
    private val bonding: AndroidBondingManager,
    private val gattFactory: AndroidGattClientFactory, // фабрика для создания gattClient
) : BleRepository {
    override val logs: Flow<String> =
        bus.events.filterIsInstance<GattEvent.Log>().map { it.line }

    override val notifications: Flow<BleNotification> =
        bus.events.filterIsInstance<GattEvent.Notify>().map { it.notification }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    /* Central-устройство (Клиент) */
    private var gattClient: AndroidGattClient? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override suspend fun scanFirst(timeoutMs: Long): BleDevice? {
        _connectionState.value = ConnectionState.Scanning // сканируем
        bus.log("scan start")

        val res = scanner.scanFirst(timeoutMs) // вызов сканнера
        val dev = if (res != null) {
            val domainModel: BleDevice = BluetoothDeviceMapper.toDomain(res.device, res.rssi)
            bus.log("FOUND: name=${domainModel.name} addr=${domainModel.address} rssi=${domainModel.rssi}")
            domainModel
        } else null

        bus.log("scan stop; selected=${dev?.address ?: "none"}")
        _connectionState.value = ConnectionState.Idle // ожидание действий
        return dev
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun connect(device: BleDevice) {
        disconnect() // закрываем GATT и меняем состояние на ожидание

        /* связывание */
        _connectionState.value = ConnectionState.Bonding
        bonding.ensureBonded(device.address)
        bus.log("BONDED OK")

        _connectionState.value = ConnectionState.Connecting

        /* создаем клиента через фабрику */
        val client = gattFactory.create(device.address)
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

    /* закрываем старый GATT и меняем состояние на ожидание */
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

    /* Central -> Peripheral */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun writeCentralData(bytes: ByteArray) {
        val client = gattClient ?: error("not connected")
        client.writeDataNoResp(bytes)
    }
}