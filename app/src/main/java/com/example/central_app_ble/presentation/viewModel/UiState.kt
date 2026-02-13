package com.example.central_app_ble.presentation.viewModel

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.ConnectionState

data class UiState(
    val selected: BleDevice? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val isCentralStreaming: Boolean = false
)
