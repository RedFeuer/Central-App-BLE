package com.example.central_app_ble.presentation.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.useCase.CentralTransferUseCase
import com.example.central_app_ble.domain.useCase.ConnectUseCase
import com.example.central_app_ble.domain.useCase.DisconnectUseCase
import com.example.central_app_ble.domain.useCase.ObserveConnectionStateUseCase
import com.example.central_app_ble.domain.useCase.ObserveLogsUseCase
import com.example.central_app_ble.domain.useCase.PingUseCase
import com.example.central_app_ble.domain.useCase.ScanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
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
    private val centralTransferUseCase: CentralTransferUseCase,
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
        /* реактивное отображение логов */
        viewModelScope.launch {
            observeLogsUseCase().collect { _logs.tryEmit(it) }
        }
        /* реактивное отображение работы Central устройства */
        viewModelScope.launch {
            observeConnectionStateUseCase().collect { connectionState ->
                when (connectionState) {
                    is ConnectionState.Disconnected -> {
                        stopCentralTransfer()
                        disconnectUseCase() // добавил проверить
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Idle,
                            selected = null,
                            isCentralStreaming = false,
                        )
                        _logs.tryEmit("REMOTE DISCONNECTED status=${connectionState.status} newState=${connectionState.newState}")
                    }
                    else -> {
                        _state.value = _state.value.copy(connectionState = connectionState)
                    }
                }
            }
        }
    }

    /* обработчик кнопок и вызов методов */
    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.ScanClicked -> scan()
            UiEvent.ConnectClicked -> connect()
            UiEvent.PingClicked -> ping()
            UiEvent.DisconnectClicked -> disconnectUseCase()

            UiEvent.CentralStreamStartClicked -> startCentralTransfer()
            UiEvent.CentralStreamStopClicked -> stopCentralTransfer()
        }
    }

    /* добавляет строчку в логи */
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
            /* неудачный скан или попытка коннекта без скана */
            _logs.tryEmit("Сначала Scan")
            return@launch
        }

        try {
            /* подключение Central к Peripheral */
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

    private fun startCentralTransfer() {
        if (streamJob?.isActive == true) {
            _logs.tryEmit("stream already running")
            return
        }
        if (_state.value.connectionState != ConnectionState.Ready) {
            _logs.tryEmit("Not ready: сначала Connect и дождись INIT OK")
            return
        }

        /* обновляем состояние на отправку данных Central -> Peripheral */
        _state.value = _state.value.copy(isCentralStreaming = true)

        streamJob = viewModelScope.launch {
            try {
                val sent = centralTransferUseCase(durationMs = 10_000)
                _logs.tryEmit("stream done. blocksSent=$sent")
            } catch (e: CancellationException) {
                /* отдельно обрабатываем отмену корутины, чтобы не засорять логи */
                throw e
            } catch (e: Exception) {
                _logs.tryEmit("stream failed: ${e.message}")
            } finally {
                /* обновляем состояние на прерывание отрпавки данных
                * после успешной передачи в течение 10 секунд*/
                _state.value = _state.value.copy(isCentralStreaming = false)
            }
        }
    }

    private fun stopCentralTransfer() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(isCentralStreaming = false)
        _logs.tryEmit("stream stopped")
    }

    fun onClearedByUi() {
        stopCentralTransfer()
        disconnectUseCase()
    }

    override fun onCleared() {
        onClearedByUi()
        super.onCleared()
    }
}