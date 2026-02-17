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
    override val state: StateFlow<PeripheralState> = server.state

    override val logs: Flow<String> = logBus.logs

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun startServer() {
        if (state.value.isRunning) {
            logBus.log(message = "Peripheral уже запущен")
            return
        }
        if (!server.isPeripheralSupported()) {
            logBus.log(message = "Peripheral не поддерживается")
            return
        }
        server.start() // стартуем сервак
        logBus.log(message = "Peripheral запущен: advertising + GATT server")
    }

    override fun stopServer() {
        if (!state.value.isRunning) {
            logBus.log(message = "Peripheral уже остановлен")
            return
        }
        server.stop() // останавливаем сервак
        logBus.log(message = "Peripheral приостановлен")
    }

    override fun startTransfer() {
        server.startTransfer()
    }

    override fun stopTransfer() {
        server.stopTransfer()
    }

    override fun clearStateError() {
        server.clearError()
    }
}