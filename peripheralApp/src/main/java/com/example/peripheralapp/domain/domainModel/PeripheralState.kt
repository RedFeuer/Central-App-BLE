package com.example.peripheralapp.domain.domainModel

data class PeripheralState (
    val isSupported: Boolean = true,
    val isRunning: Boolean = false,
    val isTransferring: Boolean = false,
    val connectedCount: Int = 0,
    val subscribedCmd: Int = 0,
    val subscribedData: Int = 0,
    val lastError: String? = null,
)