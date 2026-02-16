package com.example.peripheralapp.domain.repository

import com.example.peripheralapp.domain.domainModel.PeripheralState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PeripheralRepository {
    val state: StateFlow<PeripheralState>
    val logs: Flow<String>

    fun start()
    fun stop()
    fun clearStateError()
}