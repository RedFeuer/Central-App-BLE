package com.example.central_app_ble.domain.domainModel

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Scanning : ConnectionState
    data object Bonding : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data class Error(val message: String) : ConnectionState
}