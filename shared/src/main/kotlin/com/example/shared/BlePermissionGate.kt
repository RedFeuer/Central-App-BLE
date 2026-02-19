package com.example.shared

/**
 * Проверка и запрос runtime-разрешений, необходимых для BLE-действия.
 *
 * Назначение:
 * - проверяет, выданы ли все разрешения из [requiredPerms];
 * - если не выданы — запускает запрос у пользователя через [request];
 * - возвращает признак, можно ли выполнять BLE-действие прямо сейчас.
 *
 * Контекст использования:
 * - список [requiredPerms] формируется снаружи (через [BlePermissions]):
 *   - Central: BLUETOOTH_SCAN, BLUETOOTH_CONNECT (или ACCESS_FINE_LOCATION на старых версиях);
 *   - Peripheral: BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT (или пустой список на старых версиях).
 *
 * Как работает:
 * - если [requiredPerms] пустой — возвращает `true` (например, Peripheral на Android 11 и ниже);
 * - иначе проверяет, что все разрешения уже выданы:
 *   - если выданы — возвращает `true`;
 *   - если нет — вызывает [request] и возвращает `false`.
 *
 * Важно:
 * - класс не “ждёт” выдачи разрешений. Он только запускает запрос.
 * - обработка результата и выполнение отложенного действия выполняются снаружи
 *   (через pendingEvent в UI).
 */
class BlePermissionGate(
    /** Список обязательных runtime-разрешений для текущего действия. */
    private val requiredPerms: Array<String>, // теперь подходит и для Central и для Peripheral
    /** Лямбда, которая запускает системный запрос разрешений (показывает диалог). */
    private val request: (Array<String>) -> Unit,
    /** Лямбда проверки: выдано ли конкретное разрешение. */
    private val isGranted: (String) -> Boolean,
) {
    /**
     * Гарантирует наличие разрешений или запускает их запрос.
     *
     * @return `true`, если действие можно выполнять прямо сейчас (разрешения уже есть или список пуст);
     *         `false`, если разрешений нет и был запущен их запрос.
     */
    fun ensurePermsOrRequest(): Boolean {
        if (requiredPerms.isEmpty()) return true // пустой список при SDK < 31 на Peripheral

        /* проверка, что права выданы */
        val ok = requiredPerms.all(isGranted)
        if (ok) return true // Android 12 и выше

        request(requiredPerms) // запрос разрешений (вызов диалога)
        return false // отложит событие pendingEvent = event и выполнит если дам разрешения
    }
}