package com.example.peripheralapp.data.repositoryImpl

import android.Manifest
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.data.ble.AndroidBlePeripheralServer
import com.example.peripheralapp.data.ble.PeripheralEventBus
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class PeripheralRepositoryImpl(
    private val server: AndroidBlePeripheralServer,
    private val bus: PeripheralEventBus,
) : PeripheralRepository {
    override val state: StateFlow<PeripheralState> = server.state

    override val logs: Flow<String> = bus.logs

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun start() {
        server.start()
    }

    override fun stop() {
        server.stop()
    }

    override fun clearStateError() {
        server.clearError()
    }
}