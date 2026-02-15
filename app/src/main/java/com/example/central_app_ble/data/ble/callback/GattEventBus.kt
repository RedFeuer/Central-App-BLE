package com.example.central_app_ble.data.ble.callback

import com.example.central_app_ble.domain.domainModel.BleNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GattEvent {
    data class Log(val line: String) : GattEvent
    data class Notify(val notification: BleNotification) : GattEvent
}

@Singleton
class GattEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 512)
    val events = _events.asSharedFlow()

    fun log(line: String) {
        _events.tryEmit(GattEvent.Log(line))
    }

    fun notify(n: BleNotification) {
        _events.tryEmit(GattEvent.Notify(n))
    }
}