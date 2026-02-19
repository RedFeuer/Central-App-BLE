package com.example.shared

import android.os.Build

/**
 * Проверка и запрос разрешений Bluetooth для Android 12+ (API 31+).
 *
 * Назначение:
 * - единообразно проверяет наличие нужных разрешений Bluetooth;
 * - при отсутствии разрешений запускает запрос у пользователя;
 * - возвращает признак, можно ли прямо сейчас выполнять BLE-действие.
 *
 * Контекст использования:
 * - Central: обычно нужны разрешения на сканирование и подключение
 *   (BLUETOOTH_SCAN, BLUETOOTH_CONNECT).
 * - Peripheral: обычно нужны разрешения на рекламу и подключение
 *   (BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT).
 *
 * Как работает:
 * - на Android 11 и ниже (API < 31) всегда возвращает `true`,
 *   потому что эти разрешения как отдельные системные права ещё не используются;
 * - на Android 12 и выше:
 *   - проверяет, что все разрешения из [requiredPerms31] уже выданы;
 *   - если выданы — возвращает `true`;
 *   - если нет — вызывает [request] и возвращает `false`.
 *
 * Важно:
 * - класс не “ждёт” выдачи разрешений. Он только запускает запрос.
 * - обработка результата и выполнение отложенного действия обрабатываются
 *   снаружи (через pendingEvent в UI).
 */
class BlePermissionGate(
    /** Список обязательных разрешений для Android 12+ (API 31+). */
    private val requiredPerms31: Array<String>, // теперь подходит и для Central и для Peripheral
    /** Лямбда, которая запускает системный запрос разрешений (показывает диалог). */
    private val request: (Array<String>) -> Unit,
    /** Лямбда проверки: выдано ли конкретное разрешение. */
    private val isGranted: (String) -> Boolean,
) {
    /**
     * Гарантирует наличие разрешений или запускает их запрос.
     *
     * @return `true`, если действие можно выполнять прямо сейчас (разрешения уже есть или API < 31);
     *         `false`, если разрешений нет и был запущен их запрос.
     */
    fun ensurePermsOrRequest(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // Android 11 и ниже

        /* проверка, что права выданы */
        val ok = requiredPerms31.all(isGranted)
        if (ok) return true // Android 12 и выше

        request(requiredPerms31) // запрос разрешений (вызов диалога)
        return false // отложит событие pendingEvent = event и выполнит если дам разрешения
    }
}