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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel пользовательского интерфейса для Peripheral-приложения.
 *
 * Назначение:
 * - хранит и отдаёт в UI текущее состояние периферийного устройства [PeripheralState];
 * - собирает и хранит журнал сообщений для отображения на экране;
 * - обрабатывает события интерфейса [UiEvent] и вызывает соответствующие use case'ы.
 *
 * Источники данных:
 * - [observeState] — поток состояния периферийной части (запущено ли, подключено ли, подписки, ошибки);
 * - [observeLogs] — поток строк журнала работы BLE-части.
 *
 * Управляющие действия:
 * - запуск/остановка сервера;
 * - запуск/остановка передачи данных;
 * - очистка журнала.
 */
@HiltViewModel
class UiViewModel @Inject constructor (
    private val startServerUseCase: StartServerPeripheralUseCase,
    private val stopServerUseCase: StopServerPeripheralUseCase,
    private val startTransferUseCase: StartTransferPeripheralUseCase,
    private val stopTransferUseCase: StopTransferPeripheralUseCase,
    private val observeState: ObservePeripheralStateUseCase,
    private val observeLogs: ObservePeripheralLogsUseCase,
) : ViewModel() {
    /**
     * Текущее состояние Peripheral части.
     *
     * - начальное значение — пустой [PeripheralState];
     * - обновляется реактивно из [observeState].
     */
    private val _state = MutableStateFlow(PeripheralState())
    val state: StateFlow<PeripheralState> = _state.asStateFlow()

    /**
     * Журнал сообщений для интерфейса.
     *
     * Особенности:
     * - хранится как список строк, чтобы UI мог легко отрисовать весь журнал;
     * - наполняется реактивно из [observeLogs] через [appendLog];
     * - ограничивается последними 2000 строками, чтобы не раздувать память.
     */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs = _logs.asSharedFlow()

    /**
     * Подписки на потоки состояния и журнала.
     *
     * - состояние приходит из [observeState] и целиком заменяет текущее значение [_state];
     * - строки журнала приходят из [observeLogs] и добавляются через [appendLog].
     */
    init {
        viewModelScope.launch {
            observeState().collect { _state.value = it }
        }
        viewModelScope.launch {
            observeLogs().collect { appendLog(it) }
        }
    }

    /**
     * Обрабатывает события интерфейса и запускает соответствующие сценарии.
     *
     * - [UiEvent.StartServer] — запуск GATT-сервера и рекламы;
     * - [UiEvent.StopServer] — остановка сервера и освобождение ресурсов;
     * - [UiEvent.StartTransfer] — старт отправки данных от Peripheral к Central;
     * - [UiEvent.StopTransfer] — остановка отправки данных;
     */
    fun onEvent(e: UiEvent) {
        when (e) {
            UiEvent.StartServer -> startServerUseCase()
            UiEvent.StopServer -> stopServerUseCase()
            UiEvent.StartTransfer -> startTransferUseCase()
            UiEvent.StopTransfer -> stopTransferUseCase()
        }
    }

    /**
     * Добавляет строку в журнал.
     *
     * Поведение:
     * - добавляет [line] в конец журнала;
     * - оставляет только последние 2000 строк.
     */
    fun appendLog(line: String) {
        _logs.tryEmit(line)
    }
}