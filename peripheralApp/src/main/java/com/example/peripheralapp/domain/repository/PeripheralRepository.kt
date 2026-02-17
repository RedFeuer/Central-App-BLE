package com.example.peripheralapp.domain.repository

import com.example.peripheralapp.domain.domainModel.PeripheralState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PeripheralRepository {
    val state: StateFlow<PeripheralState>
    val logs: Flow<String>

    fun startServer()
    fun stopServer()
    fun startTransfer()
    fun stopTransfer()
    fun clearStateError()
}