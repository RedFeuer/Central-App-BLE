package com.example.central_app_ble.presentation.permissions

import android.Manifest
import android.os.Build

class BlePermissionGate(
    private val request: (Array<String>) -> Unit,
) {
    private val blePerms31 = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    fun ensurePermsOrRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        request(blePerms31)
        return false
    }
}