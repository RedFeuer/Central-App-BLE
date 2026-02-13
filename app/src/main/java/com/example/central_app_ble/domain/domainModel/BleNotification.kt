package com.example.central_app_ble.domain.domainModel

import com.example.shared.Command

sealed interface BleNotification {
    data class Cmd(val cmd: Command?) : BleNotification
    data class Data(val bytes: ByteArray) : BleNotification
}