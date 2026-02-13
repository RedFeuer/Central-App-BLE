package com.example.central_app_ble.domain.domainModel

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int // можно сортировать по дальности устройства
)
