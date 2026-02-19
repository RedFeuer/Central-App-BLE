package com.example.central_app_ble.data.mapper

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.domain.domainModel.BleDevice

/**
 * Преобразователь системной модели Bluetooth в доменную модель приложения.
 *
 * Назначение:
 * - отделяет Android-класс [BluetoothDevice] от доменного слоя;
 * - создаёт [BleDevice], который безопасно использовать в логике приложения, состоянии UI и use case’ах;
 * - добавляет к устройству уровень сигнала [rssi], который приходит только из результатов сканирования.
 *
 * Почему нужен:
 * - доменная модель хранит только те данные, которые реально нужны приложению;
 * - доменная модель остаётся стабильной при изменении Data слоя.
 */
object BluetoothDeviceMapper {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun toDomain(device: BluetoothDevice, rssi: Int): BleDevice =
        BleDevice(
            name = device.name,
            address = device.address,
            rssi = rssi
        )
}