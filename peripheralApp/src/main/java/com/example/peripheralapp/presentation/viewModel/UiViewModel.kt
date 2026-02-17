package com.example.peripheralapp.presentation.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.useCase.ObservePeripheralLogsUseCase
import com.example.peripheralapp.domain.useCase.ObservePeripheralStateUseCase
import com.example.peripheralapp.domain.useCase.StartServerPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StartTransferPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopServerPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopTransferPeripheralUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UiViewModel @Inject constructor (
    private val startServerUseCase: StartServerPeripheralUseCase,
    private val stopServerUseCase: StopServerPeripheralUseCase,
    private val startTransferUseCase: StartTransferPeripheralUseCase,
    private val stopTransferUseCase: StopTransferPeripheralUseCase,
    private val observeState: ObservePeripheralStateUseCase,
    private val observeLogs: ObservePeripheralLogsUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(PeripheralState())
    val state: StateFlow<PeripheralState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        viewModelScope.launch {
            observeState().collect { _state.value = it }
        }
        viewModelScope.launch {
            observeLogs().collect { appendLog(it) }
        }
    }

    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.StartServer -> startServerUseCase()
            UiEvent.StopServer -> stopServerUseCase()
            UiEvent.StartTransfer -> startTransferUseCase()
            UiEvent.StopTransfer -> stopTransferUseCase()
            UiEvent.ClearLog -> _logs.value = emptyList()
        }
    }

    fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(2000)
    }
}