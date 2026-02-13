package com.example.central_app_ble.presentation.viewModel

sealed interface UiEvent {
    data object ScanClicked : UiEvent
    data object ConnectClicked : UiEvent
    data object PingClicked : UiEvent

    data object CentralStreamStartClicked : UiEvent
    data object CentralStreamStopClicked : UiEvent

    data object PeripheralTxStartClicked : UiEvent
    data object PeripheralTxStopClicked : UiEvent
}