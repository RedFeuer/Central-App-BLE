package com.example.central_app_ble.presentation.ui

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
import com.example.central_app_ble.presentation.viewModel.UiViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val appContext = LocalContext.current

    /* действие пользователя */
    var pendingEvent by remember { mutableStateOf<UiEvent?>(null) }

    val viewModel = hiltViewModel<UiViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.log("perm result: $result")

        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingEvent?.let { e ->
                pendingEvent = null
                viewModel.onEvent(e)
            }
        }
    }

    val permGate = remember {
        BlePermissionGate(
            request = { permsLauncher.launch(it) },
            isGranted = { permission ->
                ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    /* обработчик кнопки */
    fun runWithBlePerms(event: UiEvent) {
        if (permGate.ensurePermsOrRequest()) {
            viewModel.onEvent(event)
        } else {
            pendingEvent = event
        }
    }

    LaunchedEffect(Unit) {
        viewModel.logs.collect { line ->
            logs.add(line)
            /* ограничим, чтобы не раздувать память */
            if (logs.size > 2000) logs.removeRange(0, logs.size - 2000)
            scope.launch {
                if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Central BLE") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Selected: ${state.selected?.name ?: "—"} / ${state.selected?.address ?: "—"}")
            Text("ConnState: ${state.connectionState}")
            Text("Central stream: ${if (state.isCentralStreaming) "ON" else "OFF"}")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { runWithBlePerms(UiEvent.ScanClicked) }
                ) { Text("Scan (5s)") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { runWithBlePerms(UiEvent.ConnectClicked) }
                ) { Text("Connect") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {runWithBlePerms(UiEvent.PingClicked)}
                ) { Text("Ping") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { runWithBlePerms(UiEvent.CentralStreamStartClicked) }
                ) { Text("Stream start") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.onEvent(UiEvent.CentralStreamStopClicked) }
                ) { Text("Stream stop") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { runWithBlePerms(UiEvent.PeripheralTxStartClicked) }
                ) { Text("Peripheral TX start") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { runWithBlePerms(UiEvent.PeripheralTxStopClicked) }
                ) { Text("Peripheral TX stop") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { logs.clear() }
                ) { Text("Clear log") }
            }

            Divider()

            Text("Log:")
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
    }
}