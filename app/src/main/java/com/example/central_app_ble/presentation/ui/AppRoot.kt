package com.example.central_app_ble.presentation.ui

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.central_app_ble.presentation.permissions.BlePermissionGate
import com.example.central_app_ble.presentation.viewModel.UiEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.log("perm result: $result")
    }

    val permGate = remember {
        BlePermissionGate(
            request = { permsLauncher.launch(it) }
        )
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
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.ScanClicked)
                    }
                ) { Text("Scan (5s)") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.ConnectClicked)
                    }
                ) { Text("Connect") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.PingClicked)
                    }
                ) { Text("Ping") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.CentralStreamStartClicked)
                    }
                ) { Text("Stream start") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.onEvent(UiEvent.CentralStreamStopClicked) }
                ) { Text("Stream stop") }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.PeripheralTxStartClicked)
                    }
                ) { Text("Peripheral TX start") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!permGate.ensurePermsOrRequest()) return@Button
                        viewModel.onEvent(UiEvent.PeripheralTxStopClicked)
                    }
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