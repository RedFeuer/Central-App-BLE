package com.example.central_app_ble.presentation.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.central_app_ble.presentation.viewModel.UiEvent
import com.example.central_app_ble.presentation.viewModel.UiState
import com.example.central_app_ble.presentation.viewModel.UiViewModel
import com.example.shared.BlePermissionGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Корневой компонент интерфейса приложения центрального BLE.
 *
 * Ответственность:
 * - получает [UiViewModel] через внедрение зависимостей;
 * - подписывается на [UiState] с учётом жизненного цикла;
 * - собирает поток строк лога в список для отображения;
 * - оборачивает пользовательские действия в проверку разрешений Bluetooth;
 * - передаёт состояние и обработчики событий в [AppScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val appContext: Context = LocalContext.current // для проверки permission'ов
    val viewModel = hiltViewModel<UiViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logUi = rememberLogsUi(viewModel.logs)

    val runWithBlePermissions = rememberRunWithBlePerms(
        appContext = appContext,
        log = { line ->
            viewModel.log(line)
        },
        onEvent = { event ->
            viewModel.onEvent(event)
        }
    )

    AppScreen(
        state = state,
        logs = logUi.logs,
        listState = logUi.listState,
        onClearLog = { logUi.logs.clear() },
        onScan = { runWithBlePermissions(UiEvent.ScanClicked) },
        onConnect = { runWithBlePermissions(UiEvent.ConnectClicked) },
        onPing = { runWithBlePermissions(UiEvent.PingClicked) },
        onDisconnect = { runWithBlePermissions(UiEvent.DisconnectClicked) },
        onTransferStart = { runWithBlePermissions(UiEvent.CentralStreamStartClicked) },
        onTransferStop = { runWithBlePermissions(UiEvent.CentralStreamStopClicked) },
    )
}

/**
 * Экран управления Central BLE: команды + состояние + журнал событий.
 *
 * Входные параметры:
 * - [state] — текущее состояние интерфейса;
 * - [logs] и [listState] — данные и состояние прокрутки журнала;
 * - остальные параметры — обработчики нажатий кнопок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    state: UiState,
    logs: List<String>,
    listState: LazyListState,
    onClearLog: () -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onPing: () -> Unit,
    onDisconnect: () -> Unit,
    onTransferStart: () -> Unit,
    onTransferStop: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Central BLE") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Header(state)

            TwoButtonsRow(
                leftText = "Scan (5s)",
                onLeft = onScan,
                rightText = "Disconnect",
                onRight = onDisconnect,
                rightOutlined = true,
            )

            TwoButtonsRow(
                leftText = "Connect",
                onLeft = onConnect,
                rightText = "Ping",
                onRight = onPing,
            )

            TwoButtonsRow(
                leftText = "Stream start",
                onLeft = onTransferStart,
                rightText = "Stream stop",
                onRight = onTransferStop,
            )

            Divider()

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Log:",
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onClearLog
                ) { Text("ClearLog") }
            }
            LogsList(logs = logs, listState = listState)
        }
    }
}

/**
 * Шапка экрана: отображает выбранное устройство и признаки текущего состояния.
 *
 * Показывает:
 * - выбранное устройство (имя и адрес), если оно есть;
 * - состояние соединения;
 * - признак запущенного потока данных со стороны центрального устройства.
 */
@Composable
private fun Header(state: UiState) {
    Text("Selected: ${state.selected?.name ?: "—"} / ${state.selected?.address ?: "—"}")
    Text("ConnState: ${state.connectionState}")
    Text("Central stream: ${if (state.isCentralStreaming) "ON" else "OFF"}")
}

/**
 * Унифицированный ряд из двух кнопок одинаковой ширины.
 *
 * Параметры:
 * - [leftText], [onLeft] — подпись и действие для левой кнопки;
 * - [rightText], [onRight] — подпись и действие для правой кнопки;
 * - [rightOutlined] — если true, правая кнопка рисуется контурной
 *   (удобно для опасных действий типа "Disconnect" или "ClearLog").
 */
@Composable
private fun TwoButtonsRow(
    leftText: String,
    onLeft: () -> Unit,
    rightText: String,
    onRight: () -> Unit,
    rightOutlined: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = onLeft
        ) { Text(leftText) }

        if (rightOutlined) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onRight
            ) { Text(rightText) }
        } else {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onRight
            ) { Text(rightText) }
        }
    }
}

/**
 * Список строк журнала.
 *
 * Использует [LazyColumn], чтобы эффективно отображать большое число строк.
 * [listState] передаётся снаружи, чтобы можно было управлять прокруткой
 * (работает автопрокрутка к последней строке при добавлении новых записей).
 */
@Composable
private fun LogsList(
    logs: List<String>,
    listState: LazyListState,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(count = logs.size) { i ->
            Text(text = logs[i], style = MaterialTheme.typography.bodySmall)
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
 * Параметры:
 * - [appContext] — используется для проверки наличия разрешений;
 * - [log] — вывод диагностических сообщений;
 * - [onEvent] — фактическая отправка события в модель представления.
 *
 * Возвращает:
 * - функцию вида `(UiEvent) -> Unit` для последующего вызова в обработчиках нажатий кнопок.
 */
@Composable
private fun rememberRunWithBlePerms(
    appContext: Context,
    log: (String) -> Unit,
    onEvent: (UiEvent) -> Unit,
) : (UiEvent) -> Unit {
    /* отложенное действие пользователя */
    var pendingEvent by remember { mutableStateOf<UiEvent?>(null) }

    /* проверяет, все ли выданы разрешения, и продолжает отложенное событие */
    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        log("permissions result: $result")

        /* если все permission'ы выданы, то выполняем отложенное событие */
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingEvent?.let { event ->
                pendingEvent = null // очистка, чтобы не выполнилось повторно
                onEvent(event) // вызов отложенного события
            }
        }
    }

    /* шлюз разрешений: хранит список нужных разрешений в requiredPerms31
    * умеет подтверждать, что все выдано, либо инициировать запрос разрешений у пользователя */
    val permGate = remember(appContext) {
        BlePermissionGate(
            requiredPerms31 = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            request = { permsLauncher.launch(it) }, // запуск запроса разрешений
            isGranted = { permission -> // проверка конктретного разрешения
                ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    return { event ->
        if (permGate.ensurePermsOrRequest()) {
            /* разрешения есть */
            onEvent(event)
            pendingEvent = null // на всякий случай очищаем отложенное событие, чтобы потом не выполнилось
        }
        else {
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

/* сбор логов из viewModel */
/**
 * Подписывается на поток строк журнала и накапливает их в списке для отображения.
 *
 * Поведение:
 * - каждую пришедшую строку добавляет в `logs`;
 * - ограничивает размер журнала (не более 2000 строк), чтобы уменьшить расход памяти;
 * - прокручивает список вниз к последней строке.
 *
 * Параметры:
 * - [logsFlow] — поток сообщений журнала из модели представления.
 *
 * Возвращает:
 * - [LogsUiState], содержащий список строк и состояние прокрутки.
 */
@Composable
private fun rememberLogsUi(logsFlow: Flow<String>) : LogsUiState {
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