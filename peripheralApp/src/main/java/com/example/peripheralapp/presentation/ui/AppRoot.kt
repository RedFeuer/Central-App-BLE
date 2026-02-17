package com.example.peripheralapp.presentation.ui

import android.Manifest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val appContext = LocalContext.current
    val viewModel = hiltViewModel<UiViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logs by viewModel.logs.collectAsStateWithLifecycle(emptyList())
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    val runWithBlePerms = rememberRunWithPeripheralBlePerms(
        appContext = appContext,
        log = { log ->
            viewModel.appendLog(log)
        },
        onEvent = { viewModel.onEvent(it) }
    )

    AppScreen(
        state = state,
        logs = logs,
        listState = listState,
        onClearLog = { viewModel.onEvent(UiEvent.ClearLog) },
        onStartServer = { runWithBlePerms(UiEvent.StartServer) },
        onStopServer = { viewModel.onEvent(UiEvent.StopServer) },
        onStartTransfer = { viewModel.onEvent(UiEvent.StartTransfer) },
        onStopTransfer = { viewModel.onEvent(UiEvent.StopTransfer) }
    )
}

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

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun rememberRunWithPeripheralBlePerms(
    appContext: Context,
    log: (String) -> Unit,
    onEvent: (UiEvent) -> Unit,
): (UiEvent) -> Unit {

    var pending by remember { mutableStateOf<UiEvent?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        log("permissions result: $result")
        val allGranted = result.values.all { it }
        if (allGranted) {
            pending?.let {
                pending = null
                onEvent(it)
            }
        }
    }

    val gate = remember(appContext) {
        BlePermissionGate(
            requiredPerms31 = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            request = { launcher.launch(it) },
            isGranted = { perm ->
                ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    return { event ->
        if (gate.ensurePermsOrRequest()) {
            pending = null
            onEvent(event)
        } else {
            pending = event
        }
    }
}

@Stable
private class LogsUiState(
    val logs: MutableList<String>,
    val listState: LazyListState,
)

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
