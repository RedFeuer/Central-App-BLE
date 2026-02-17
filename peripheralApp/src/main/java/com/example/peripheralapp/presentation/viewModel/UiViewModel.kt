package com.example.peripheralapp.presentation.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.useCase.ObservePeripheralLogsUseCase
import com.example.peripheralapp.domain.useCase.ObservePeripheralStateUseCase
import com.example.peripheralapp.domain.useCase.StartPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopPeripheralUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UiViewModel @Inject constructor (
    private val startUseCase: StartPeripheralUseCase,
    private val stopUseCase: StopPeripheralUseCase,
    private val observeState: ObservePeripheralStateUseCase,
    private val observeLogs: ObservePeripheralLogsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(PeripheralState())
    val state: StateFlow<PeripheralState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs = _logs.asSharedFlow()

    init {
        viewModelScope.launch {
            observeState().collect { _state.value = it }
        }
        viewModelScope.launch {
            observeLogs().collect { _logs.tryEmit(it) }
        }
    }

    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.StartClicked -> startUseCase()
            UiEvent.StopClicked -> stopUseCase()
            UiEvent.ClearLog -> _logs.tryEmit("----- cleared in UI -----")
        }
    }
}