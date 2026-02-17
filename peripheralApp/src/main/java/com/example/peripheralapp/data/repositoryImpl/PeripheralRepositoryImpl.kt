package com.example.peripheralapp.data.repositoryImpl

import android.Manifest
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.data.ble.AndroidBlePeripheralServer
import com.example.peripheralapp.data.ble.PeripheralLogBus
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class PeripheralRepositoryImpl @Inject constructor (
    private val server: AndroidBlePeripheralServer,
    private val logBus: PeripheralLogBus,
) : PeripheralRepository {
    private val _state = MutableStateFlow(PeripheralState())
    override val state: StateFlow<PeripheralState> = _state.asStateFlow()

    override val logs: Flow<String> = logBus.logs

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun startServer() {
        if (!server.isPeripheralSupported()) {
            logBus.log(message = "Peripheral не поддерживается")
            return
        }
        server.start() // стартуем сервак
        _state.value = _state.value.copy(isRunning = true)
        logBus.log(message = "Peripheral запущен: advertising + GATT server")
    }

    override fun stopServer() {
        server.stop() // останавливаем сервак
        _state.value = PeripheralState()
        logBus.log(message = "Peripheral приостановлен")
    }

    override fun startTransfer() {
        if (!_state.value.isRunning) {
            logBus.log(message = "Сначала нужно запустить Peripheral Server")
            return
        }
        server.startTransfer()
        _state.value = _state.value.copy(isTransferring = true)
        logBus.log(message = "Peripheral TX started")
    }

    override fun stopTransfer() {
        server.stopTransfer()
        _state.value = _state.value.copy(isTransferring = false)
        logBus.log(message = "Peripheral TX stopped")
    }

    override fun clearStateError() {
        server.clearError()
    }
}