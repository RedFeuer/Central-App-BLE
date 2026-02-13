package com.example.central_app_ble.data.mapper

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.domain.domainModel.BleDevice

object BluetoothDeviceMapper {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun toDomain(device: BluetoothDevice, rssi: Int): BleDevice =
        BleDevice(
            name = device.name,
            address = device.address,
            rssi = rssi
        )
}