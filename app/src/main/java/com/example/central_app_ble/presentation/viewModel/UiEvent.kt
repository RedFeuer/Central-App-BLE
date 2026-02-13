package com.example.central_app_ble.presentation.viewModel

sealed interface UiEvent {
    data object ScanClicked : MainUiEvent
    data object ConnectClicked : MainUiEvent
    data object PingClicked : MainUiEvent

    data object CentralStreamStartClicked : MainUiEvent
    data object CentralStreamStopClicked : MainUiEvent

    data object PeripheralTxStartClicked : MainUiEvent
    data object PeripheralTxStopClicked : MainUiEvent
}