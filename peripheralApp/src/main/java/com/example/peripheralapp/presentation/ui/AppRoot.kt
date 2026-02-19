package com.example.peripheralapp.presentation.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.presentation.viewModel.UiEvent
import com.example.peripheralapp.presentation.viewModel.UiViewModel
import com.example.shared.BlePermissionGate
import com.example.shared.BlePermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Корневой экран Peripheral-приложения.
 *
 * Ответственность:
 * - получает [UiViewModel] через внедрение зависимостей;
 * - подписывается на [PeripheralState] с учётом жизненного цикла;
 * - собирает поток строк журнала из viewModel в список для отображения;
 * - оборачивает пользовательские действия в проверку разрешений Bluetooth;
 * - передаёт состояние и обработчики событий в [AppScreen].
 *
 * Поведение с журналом:
 * - журнал приходит как поток строк из viewModel;
 * - в UI поток собирается в список (не более 2000 строк);
 * - при добавлении новой строки список автоматически прокручивается вниз.
 */
@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val appContext = LocalContext.current
    val viewModel = hiltViewModel<UiViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logUi = rememberLogsUi(viewModel.logs)

    val runWithBlePerms = rememberRunWithPeripheralBlePerms(
        appContext = appContext,
        log = { line -> viewModel.appendLog(line) },
        onEvent = { viewModel.onEvent(it) }
    )

    AppScreen(
        state = state,
        logs = logUi.logs,
        listState = logUi.listState,
        onClearLog = { logUi.logs.clear() },
        onStartServer = { runWithBlePerms(UiEvent.StartServer) },
        onStopServer = { viewModel.onEvent(UiEvent.StopServer) },
        onStartTransfer = { viewModel.onEvent(UiEvent.StartTransfer) },
        onStopTransfer = { viewModel.onEvent(UiEvent.StopTransfer) }
    )
}

/**
 * Основной экран управления Peripheral-частью.
 *
 * Показывает:
 * - поддержку BLE на устройстве;
 * - факт запуска сервера;
 * - количество подключённых центральных устройств;
 * - наличие подписки на уведомления по командному и потоковому каналам;
 * - последнюю ошибку;
 * - журнал событий.
 *
 * Кнопки:
 * - Start Server / Stop Server — запуск и остановка GATT-сервера и рекламы;
 * - Start TX / Stop TX — управление отправкой данных;
 * - Clear log — очистка журнала.
 *
 * Параметры:
 * - [state] — текущее состояние периферийной части;
 * - [logs] и [listState] — данные и состояние прокрутки журнала;
 * - остальные параметры — обработчики кнопок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    state: PeripheralState,
    logs: List<String>,
    listState: LazyListState,
    onClearLog: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onStartTransfer: () -> Unit,
    onStopTransfer: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Peripheral BLE") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Supported: ${state.isSupported}")
            Text("Running: ${state.isRunning}")
            Text("Connected: ${state.connectedCount}")
            Text("Subscribed CMD: ${state.subscribedCmd}")
            Text("Subscribed DATA: ${state.subscribedData}")
            if (state.lastError != null) Text("Error: ${state.lastError}")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartServer, modifier = Modifier.weight(1f)) { Text("Start Server") }
                Button(onClick = onStopServer, modifier =Modifier.weight(1f)) { Text("Stop Server") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartTransfer, modifier = Modifier.weight(1f)) { Text("Start TX") }
                Button(onClick = onStopTransfer, modifier =Modifier.weight(1f)) { Text("Stop TX") }
            }

            OutlinedButton(onClick = onClearLog) { Text("Clear log") }

            Divider()
            Text("Log:")
            LogsList(logs, listState)
        }
    }
}

/**
 * Список строк журнала.
 *
 * Использует [LazyColumn], чтобы эффективно отображать большое число строк.
 * [listState] передаётся снаружи, чтобы можно было управлять прокруткой
 * (автоматически прокручивать к последней строке).
 */
@Composable
private fun LogsList(logs: List<String>, listState: LazyListState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(logs.size) { i ->
            Text(logs[i], style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Создаёт функцию-обёртку для запуска событий интерфейса с проверкой разрешений Bluetooth.
 *
 * Логика:
 * - если все нужные разрешения уже выданы — событие выполняется сразу;
 * - если разрешений нет — событие запоминается как отложенное и будет выполнено
 *   после успешной выдачи разрешений пользователем.
 *
 * Разрешения:
 * - список берётся из [BlePermissions.peripheralRuntime] (`BLUETOOTH_ADVERTISE` и `BLUETOOTH_CONNECT` на Android 12+).
 *
 * Параметры:
 * - [appContext] — используется для проверки наличия разрешений;
 * - [log] — вывод диагностических сообщений (например, результат запроса разрешений);
 * - [onEvent] — фактическая отправка события в viewModel.
 *
 * Возвращает:
 * - функцию вида `(UiEvent) -> Unit` для последующего вызова в обработчиках нажатий кнопок.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun rememberRunWithPeripheralBlePerms(
    appContext: Context,
    log: (String) -> Unit,
    onEvent: (UiEvent) -> Unit,
): (UiEvent) -> Unit {

    /* отложенное действие пользователя */
    var pendingEvent by remember { mutableStateOf<UiEvent?>(null) }

    /* проверяет, все ли выданы разрешения, и продолжает отложенное событие */
    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        log("permissions result: $result")
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingEvent?.let {
                pendingEvent = null
                onEvent(it)
            }
        }
    }

    /* шлюз разрешений: хранит список нужных разрешений в requiredPerms
    * умеет подтверждать, что все выдано, либо инициировать запрос разрешений у пользователя */
    val permGate = remember(appContext) {
        BlePermissionGate(
            requiredPerms = BlePermissions.peripheralRuntime(),
            request = { permsLauncher.launch(it) },
            isGranted = { perm ->
                ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    return { event ->
        if (permGate.ensurePermsOrRequest()) {
            /* разрешения есть */
            onEvent(event)
            pendingEvent = null // на всякий случай очищаем отложенное событие, чтобы потом не выполнилось
        } else {
            /* разрешений нет */
            pendingEvent = event // отклыдываем и выполним позже
        }
    }
}

/**
 * Состояние журнала для интерфейса.
 *
 * Содержит:
 * - [logs] — список строк журнала, который изменяется по мере поступления новых сообщений;
 * - [listState] — состояние прокрутки списка журнала.
 */
@Stable
private class LogsUiState(
    val logs: MutableList<String>,
    val listState: LazyListState,
)

/**
 * Подписывается на поток строк журнала и накапливает их в списке для отображения.
 *
 * Поведение:
 * - каждую пришедшую строку добавляет в `logs`;
 * - ограничивает размер журнала (не более 2000 строк), чтобы уменьшить расход памяти;
 * - прокручивает список вниз к последней строке.
 *
 * Параметры:
 * - [logsFlow] — поток строк журнала из модели представления.
 *
 * Возвращает:
 * - [LogsUiState], содержащий список строк и состояние прокрутки.
 */
@Composable
private fun rememberLogsUi(logsFlow: Flow<String>): LogsUiState {
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(logsFlow) {
        logsFlow.collect { line ->
            logs.add(line)
            if (logs.size > 2000) logs.removeRange(0, logs.size - 2000)
            scope.launch {
                if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
            }
        }
    }

    return remember { LogsUiState(logs, listState) }
}
