package com.example.central_app_ble.presentation.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.useCase.CentralStreamUseCase
import com.example.central_app_ble.domain.useCase.ConnectUseCase
import com.example.central_app_ble.domain.useCase.DisconnectUseCase
import com.example.central_app_ble.domain.useCase.ObserveConnectionStateUseCase
import com.example.central_app_ble.domain.useCase.ObserveLogsUseCase
import com.example.central_app_ble.domain.useCase.PeripheralTxControlUseCase
import com.example.central_app_ble.domain.useCase.PingUseCase
import com.example.central_app_ble.domain.useCase.ScanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UiViewModel @Inject constructor(
    private val scanUseCase: ScanUseCase,
    private val connectUseCase: ConnectUseCase,
    private val pingUseCase: PingUseCase,
    private val centralStreamUseCase: CentralStreamUseCase,
    private val peripheralTxControlUseCase: PeripheralTxControlUseCase,
    private val observeLogsUseCase: ObserveLogsUseCase,
    private val observeConnectionStateUseCase: ObserveConnectionStateUseCase,
    private val disconnectUseCase: DisconnectUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs = _logs.asSharedFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            observeLogsUseCase().collect { _logs.tryEmit(it) }
        }
        viewModelScope.launch {
            observeConnectionStateUseCase().collect { connectionState ->
                _state.value = _state.value.copy(connectionState = connectionState)
            }
        }
    }

    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.ScanClicked -> scan()
            UiEvent.ConnectClicked -> connect()
            UiEvent.PingClicked -> ping()

            UiEvent.CentralStreamStartClicked -> startCentralStream10s()
            UiEvent.CentralStreamStopClicked -> stopCentralStream()

            UiEvent.PeripheralTxStartClicked -> peripheralTxStart()
            UiEvent.PeripheralTxStopClicked -> peripheralTxStop()
        }
    }

    fun log(line: String) {
        _logs.tryEmit(line)
    }

    private fun scan() = viewModelScope.launch {
        val device = scanUseCase(timeoutMs = 5_000)
        _state.value = _state.value.copy(selected = device)
    }

    private fun connect() = viewModelScope.launch {
        val device = _state.value.selected
        if (device == null) {
            _logs.tryEmit("Сначала Scan")
            return@launch
        }

        try {
            connectUseCase(device)
            _logs.tryEmit("CONNECTED + INIT OK")
        } catch (e: Exception) {
            _logs.tryEmit("CONNECT/INIT FAILED: ${e.message}")
        }
    }

    private fun ping() = viewModelScope.launch {
        if (_state.value.connectionState != ConnectionState.Ready) {
            _logs.tryEmit("Not ready: сначала Connect и дождись INIT OK")
            return@launch
        }
        try {
            pingUseCase()
            _logs.tryEmit("PING sent")
        } catch (e: Exception) {
            _logs.tryEmit("PING failed: ${e.message}")
        }
    }

    private fun startCentralStream10s() {
        if (streamJob?.isActive == true) {
            _logs.tryEmit("stream already running")
            return
        }
        if (_state.value.connectionState != ConnectionState.Ready) {
            _logs.tryEmit("Not ready: сначала Connect и дождись INIT OK")
            return
        }

        _state.value = _state.value.copy(isCentralStreaming = true)

        streamJob = viewModelScope.launch {
            try {
                val sent = centralStreamUseCase(durationMs = 10_000)
                _logs.tryEmit("stream done. blocksSent=$sent")
            } catch (e: Exception) {
                _logs.tryEmit("stream failed: ${e.message}")
            } finally {
                _state.value = _state.value.copy(isCentralStreaming = false)
            }
        }
    }

    private fun stopCentralStream() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(isCentralStreaming = false)
        _logs.tryEmit("stream stopped")
    }

    private fun peripheralTxStart() = viewModelScope.launch {
        if (_state.value.connectionState != ConnectionState.Ready) {
            _logs.tryEmit("Not ready: сначала Connect и дождись INIT OK")
            return@launch
        }
        try {
            peripheralTxControlUseCase.start()
            _logs.tryEmit("StartStream sent")
        } catch (e: Exception) {
            _logs.tryEmit("StartStream failed: ${e.message}")
        }
    }

    private fun peripheralTxStop() = viewModelScope.launch {
        if (_state.value.connectionState != ConnectionState.Ready) {
            _logs.tryEmit("Not ready: сначала Connect и дождись INIT OK")
            return@launch
        }
        try {
            peripheralTxControlUseCase.stop()
            _logs.tryEmit("StopStream sent")
        } catch (e: Exception) {
            _logs.tryEmit("StopStream failed: ${e.message}")
        }
    }

    fun onClearedByUi() {
        stopCentralStream()
        disconnectUseCase()
    }

    override fun onCleared() {
        onClearedByUi()
        super.onCleared()
    }
}