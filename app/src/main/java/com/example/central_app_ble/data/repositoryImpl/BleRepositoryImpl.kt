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
import com.example.central_app_ble.di.AppScope
import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command
import com.example.shared.CommandCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private sealed interface RepoAction {
    data class SetState(val state: ConnectionState) : RepoAction
    data class RemoteDisconnected(val status: Int, val newState: Int) : RepoAction
}
class BleRepositoryImpl @Inject constructor (
    @ApplicationContext private val appContext: Context,
    private val bus: GattEventBus, /* шина Gatt */
    private val scanner: AndroidBleScanner,
    private val bonding: AndroidBondingManager,
    private val gattFactory: AndroidGattClientFactory, // фабрика для создания gattClient
    @AppScope private val appScope: CoroutineScope,
) : BleRepository {
    override val logs: Flow<String> =
        bus.events.filterIsInstance<GattEvent.Log>().map { it.line }

    override val notifications: Flow<BleNotification> =
        bus.events.filterIsInstance<GattEvent.Notify>().map { it.notification }

    private val actions = MutableSharedFlow<RepoAction>(extraBufferCapacity = 64)

    /* поток дисконектов */
    private val remoteDisconnectedActions: Flow<RepoAction> =
        bus.events
            .filterIsInstance<GattEvent.Disconnected>() // фильтруем события
            .onEach { closeGatt() } // закрываем GATT сразу при разрыве соединения
            .map{ RepoAction.RemoteDisconnected(it.status, it.newState) } // маппим событие GattEvent.Disconnected -> RepoAction

    /* склеиваем два потока в один */
    override val connectionState: StateFlow<ConnectionState> =
        merge(actions, remoteDisconnectedActions)
            .scan<RepoAction, ConnectionState>(ConnectionState.Idle) { _,action ->
                when (action) {
                    is RepoAction.SetState -> action.state // ставим новое состояние
                    is RepoAction.RemoteDisconnected -> ConnectionState.Disconnected(action.status, action.newState) // ставим состояние дисконнекта
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, ConnectionState.Idle)


    /* Central-устройство (Клиент) */
    private var gattClient: AndroidGattClient? = null

    private fun closeGatt() {
        runCatching { gattClient?.close() }
        gattClient = null
    }

    /* закрываем старый GATT и меняем состояние на ожидание */
    override fun disconnect() {
        closeGatt()
        setState(ConnectionState.Idle)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override suspend fun scanFirst(timeoutMs: Long): BleDevice? {
        setState(ConnectionState.Scanning) // сканируем
        bus.log("scan start")

        val res = scanner.scanFirst(timeoutMs) // вызов сканнера
        val dev = if (res != null) {
            val domainModel: BleDevice = BluetoothDeviceMapper.toDomain(res.device, res.rssi)
            bus.log("FOUND: name=${domainModel.name} addr=${domainModel.address} rssi=${domainModel.rssi}")
            domainModel
        } else null

        bus.log("scan stop; selected=${dev?.address ?: "none"}")
        setState(ConnectionState.Idle) // ожидание действий
        return dev
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun connect(device: BleDevice) {
        disconnect() // закрываем GATT и меняем состояние на ожидание

        /* связывание */
        setState(ConnectionState.Bonding)
        bonding.ensureBonded(device.address)
        bus.log("BONDED OK")

        setState(ConnectionState.Connecting)

        /* создаем клиента через фабрику */
        val client = gattFactory.create(device.address)
        gattClient = client

        try {
            client.connectAndInit()
            setState(ConnectionState.Ready)
        } catch (e: Exception) {
            setState(ConnectionState.Error(e.message ?: "connect/init error"))
            disconnect()
            throw e
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

    private fun setState(state: ConnectionState) {
        actions.tryEmit(RepoAction.SetState(state))
    }
}