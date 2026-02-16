package com.example.shared

import android.os.Build

/* Central: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
* Peripheral: BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT */
class BlePermissionGate(
    private val requiredPerms31: Array<String>, // теперь подходит и для Central и для Peripheral
    private val request: (Array<String>) -> Unit, // лямбда для запроса разрешений
    private val isGranted: (String) -> Boolean, // лямбда для проверки, выдано ли конкретное разрешение
) {
    /* Обязательные permission'ы для Android 12 и выше */
    /* было для CentralApp */
//    private val blePerms31 = arrayOf(
//        Manifest.permission.BLUETOOTH_SCAN,
//        Manifest.permission.BLUETOOTH_CONNECT
//    )

    fun ensurePermsOrRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // Android 11 и ниже

        /* проверка, что права выданы */
        val ok = requiredPerms31.all(isGranted)
        if (ok) return true // Android 12 и выше

        request(requiredPerms31) // запрос разрешений (вызов диалога)
        return false // отложит событие pendingEvent = event и выполнит если дам разрешения
    }
}