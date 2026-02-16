package com.example.peripheralapp.data.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class PeripheralEventBus() {
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val logs = _logs.asSharedFlow()

    fun log(line: String) {
        _logs.tryEmit(line)
    }
}