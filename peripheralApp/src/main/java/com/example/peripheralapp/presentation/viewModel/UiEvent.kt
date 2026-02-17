package com.example.peripheralapp.presentation.viewModel

sealed interface UiEvent {
    data object StartClicked: UiEvent
    data object StopClicked: UiEvent
    data object ClearLog: UiEvent
}