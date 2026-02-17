package com.example.peripheralapp.presentation.viewModel

sealed interface UiEvent {
    data object StartServer: UiEvent
    data object StopServer: UiEvent
    data object StartTransfer: UiEvent
    data object StopTransfer: UiEvent

    data object ClearLog: UiEvent
}