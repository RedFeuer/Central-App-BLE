package com.example.central_app_ble.domain.repository

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.shared.Command
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleRepository {
    val connectionState: StateFlow<ConnectionState>
    val logs: Flow<String>
    val notifications: Flow<BleNotification>

    suspend fun scanFirst(timeoutMs: Long): BleDevice?

    suspend fun connect(device: BleDevice)
    fun disconnect()

    suspend fun sendCmd(cmd: Command)

    /* central -> peripheral */
    suspend fun writeCentralData(bytes: ByteArray)
}