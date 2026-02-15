package com.example.central_app_ble.presentation.ui

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
import com.example.central_app_ble.presentation.permissions.BlePermissionGate
import com.example.central_app_ble.presentation.viewModel.UiEvent
import com.example.central_app_ble.presentation.viewModel.UiState
import com.example.central_app_ble.presentation.viewModel.UiViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
        onCentralStreamStart = { runWithBlePermissions(UiEvent.CentralStreamStartClicked) },
        onCentralStreamStop = { runWithBlePermissions(UiEvent.CentralStreamStopClicked) },
        onPeripheralTxStart = { runWithBlePermissions(UiEvent.PeripheralTxStartClicked) },
        onPeripheralTxStop = { runWithBlePermissions(UiEvent.PeripheralTxStopClicked) },
    )
}

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
    onCentralStreamStart: () -> Unit,
    onCentralStreamStop: () -> Unit,
    onPeripheralTxStart: () -> Unit,
    onPeripheralTxStop: () -> Unit,
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
                rightText = "Clear log",
                onRight = onClearLog,
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
                onLeft = onCentralStreamStart,
                rightText = "Stream stop",
                onRight = onCentralStreamStop,
            )

            TwoButtonsRow(
                leftText = "Peripheral TX start",
                onLeft = onPeripheralTxStart,
                rightText = "Peripheral TX stop",
                onRight = onPeripheralTxStop,
            )

            Divider()

            Text("Log:")
            LogsList(logs = logs, listState = listState)
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Text("Selected: ${state.selected?.name ?: "—"} / ${state.selected?.address ?: "—"}")
    Text("ConnState: ${state.connectionState}")
    Text("Central stream: ${if (state.isCentralStreaming) "ON" else "OFF"}")
}

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

/* разрешения и отложенные события */
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

        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingEvent?.let { event ->
                pendingEvent = null // очистка, чтобы не выполнилось повторно
                onEvent(event) // вызов отложенного события
            }
        }
    }

    val permGate = remember(appContext) {
        BlePermissionGate(
            request = { permsLauncher.launch(it) }, // запуск запроса разрешений
            isGranted = { permission -> // проверка конктретного разрешения
                ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    return { event ->
        if (permGate.ensurePermsOrRequest()) {
            onEvent(event)
        }
        pendingEvent = event
    }
}

@Stable
private class LogsUiState(
    val logs: MutableList<String>,
    val listState: LazyListState,
)

/* сбор логов из viewModel */
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