package com.example.central_app_ble.presentation.permissions

import android.Manifest
import android.os.Build

class BlePermissionGate(
    private val request: (Array<String>) -> Unit, // лямбда для запроса разрешений
    private val isGranted: (String) -> Boolean, // лямбда для проверки, выдано ли конкретное разрешение
) {
    /* Обязательные permission'ы для Android 12 и выше */
    private val blePerms31 = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    fun ensurePermsOrRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // Android 11 и ниже

        /* проверка, что права выданы */
        val ok = blePerms31.all(isGranted)
        if (ok) return true // Android 12 и выше

        request(blePerms31) // запрос разрешений (вызов диалога)
        return false // отложит событие pendingEvent = event и выполнит если дам разрешения
    }
}