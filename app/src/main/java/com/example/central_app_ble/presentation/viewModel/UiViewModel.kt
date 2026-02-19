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

/**
 * Модель представления главного экрана центрального BLE.
 *
 * Роль:
 * - хранит состояние интерфейса [UiState] и отдаёт его наружу через [state];
 * - отдаёт поток строк журнала наружу через [logs];
 * - принимает события интерфейса [UiEvent] и вызывает use case'ы;
 * - следит за изменениями состояния соединения реактивно и обновляет интерфейс.
 *
 * Принципы работы:
 * - состояние интерфейса хранится в [MutableStateFlow] и изменяется только внутри этой модели;
 * - журнал выводится как поток строк, чтобы интерфейс мог отображать его в реальном времени;
 * - длительная отправка данных (поток) выполняется в отдельной корутине и может быть остановлена
 * посредством `streamJob?.cancel()`.
 */
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
    /**
     * Внутреннее изменяемое состояние интерфейса.
     * Снаружи доступно только как [StateFlow] через [state].
     */
    private val _state = MutableStateFlow(UiState())
    /**
     * Публичное состояние интерфейса, на которое подписывается UI.
     */
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Внутренний поток строк журнала.
     *
     * Используется [extraBufferCapacity], чтобы не блокировать отправителя,
     * если интерфейс временно не успевает обрабатывать события.
     */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    /**
     * Публичный поток строк журнала, на который подписывается UI.
     */
    val logs = _logs.asSharedFlow()

    /**
     * Задача (корутина) отправки потока данных Central -> Peripheral.
     * Нужна для контроля: не запускать трансфер повторно и уметь остановить.
     */
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
                        /* обработка состояние Disconnect между Central и Peripheral */
                        stopCentralTransfer()
                        disconnectUseCase() // добавил проверить НЕ РАБОТАЕТ!1!!!
                        _state.value = _state.value.copy(
                            connectionState = ConnectionState.Idle,
                            selected = null,
                            isCentralStreaming = false,
                        )
                        _logs.tryEmit("REMOTE DISCONNECTED status=${connectionState.status} newState=${connectionState.newState}")
                    }
                    else -> {
                        /* для всех остальных состояний просто обновляем connectionState на новый */
                        _state.value = _state.value.copy(connectionState = connectionState)
                    }
                }
            }
        }
    }

    /**
     * Обработчик событий интерфейса (нажатия кнопок).
     *
     * События приходят из UI и переводятся в вызовы use case'ов.
     */
    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.ScanClicked -> scan()
            UiEvent.ConnectClicked -> connect()
            UiEvent.PingClicked -> ping()
            UiEvent.DisconnectClicked -> disconnect()

            UiEvent.CentralStreamStartClicked -> startCentralTransfer()
            UiEvent.CentralStreamStopClicked -> stopCentralTransfer()
        }
    }

    /**
     * Добавляет строку в журнал.
     *
     * Используется UI-слоем как единый способ выводить диагностические сообщения.
     */
    fun log(line: String) {
        _logs.tryEmit(line)
    }

    /**
     * Запускает поиск устройства.
     *
     * Поведение:
     * - вызывает [scanUseCase] с таймаутом 5 секунд;
     * - сохраняет найденное устройство в [UiState.selected].
     */
    private fun scan() = viewModelScope.launch {
        val device = scanUseCase(timeoutMs = 5_000)
        _state.value = _state.value.copy(selected = device)
    }

    /**
     * Выполняет подключение к ранее найденному устройству.
     *
     * Предусловия:
     * - в [UiState.selected] должно быть устройство (то есть до этого выполнен Scan).
     *
     * Поведение:
     * - вызывает [connectUseCase], который выполняет связывание и подключение;
     * - пишет результат в журнал.
     */
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

    /**
     * Отправляет команду проверки связи.
     *
     * Предусловия:
     * - состояние соединения должно быть [ConnectionState.Ready]
     *   (то есть связывание и подключение завершены успешно).
     *
     * Поведение:
     * - вызывает [pingUseCase];
     * - пишет результат в журнал.
     */
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

    /**
     * Инициирует отключение от периферийного устройства.
     *
     * Логика:
     * - проверяет текущее состояние соединения:
     *   - если соединение уже разорвано ([ConnectionState.Disconnected]) или не активно ([ConnectionState.Idle]),
     *     повторное отключение не выполняется и в журнал добавляется сообщение;
     *   - иначе вызывает [disconnectUseCase], который закрывает GATT-соединение и переводит
     *     состояние соединения в [ConnectionState.Idle].
     *
     * Назначение проверки:
     * - сделать отключение безопасным при повторных нажатиях кнопки и при повторных событиях
     *   от устройства.
     */
    private fun disconnect() = viewModelScope.launch {
        val cs = _state.value.connectionState
        if (cs is ConnectionState.Disconnected || cs is ConnectionState.Idle) {
            _logs.tryEmit("Already disconnected")
            return@launch
        }

        disconnectUseCase()
    }

    /**
     * Запускает трансфер потока данных Central -> Peripheral.
     *
     * Предусловия:
     * - поток ещё не запущен (защита от повторного старта);
     * - состояние соединения должно быть [ConnectionState.Ready].
     *
     * Поведение:
     * - выставляет признак передачи данных [UiState.isCentralStreaming] = true;
     * - запускает корутину, которая вызывает [centralTransferUseCase] на 10 секунд;
     * - по завершении или ошибке пишет результат в журнал;
     * - в любом случае снимает признак передачи данных в [finally]: [UiState.isCentralStreaming] = false.
     */
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

    /**
     * Останавливает трансфер потока данных Central -> Peripheral.
     *
     * Поведение:
     * - отменяет задачу передачи, если она запущена;
     * - сбрасывает признак [UiState.isCentralStreaming] в false;
     * - пишет в журнал, что поток остановлен.
     */
    private fun stopCentralTransfer() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(isCentralStreaming = false)
        _logs.tryEmit("stream stopped")
    }

    /**
     * Явная очистка ресурсов по запросу UI.
     *
     * Используется, когда UI хочет гарантированно:
     * - остановить передачу данных;
     * - разорвать соединение.
     */
    fun onClearedByUi() {
        stopCentralTransfer()
        disconnect()
    }

    /**
     * Очистка ресурсов при уничтожении viewModel.
     *
     * Вызывается системой, когда модель больше не нужна.
     * Здесь дополнительно вызывается [onClearedByUi] для гарантии остановки задач и разрыва соединения.
     */
    override fun onCleared() {
        onClearedByUi()
        super.onCleared()
    }
}