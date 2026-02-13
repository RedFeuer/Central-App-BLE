package com.example.central_app_ble.presentation.viewModel

data class UiState(
    val selected: BleDevice? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val isCentralStreaming: Boolean = false
)
