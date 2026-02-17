package com.example.peripheralapp.data.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/* singleton to maintain state correct during sessions */
@Singleton
class PeripheralLogBus @Inject constructor () {
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val logs = _logs.asSharedFlow()

    fun log(
        tag: String = "Peripheral BLE",
        message: String
    ) {
        /* логируем в Logcat */
        Log.i(tag, message)
        /* отправка лога в UI на Peripheral */
        _logs.tryEmit(message)
    }
}