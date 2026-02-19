package com.example.central_app_ble.domain.domainModel

/**
 * Состояние соединения центрального устройства с периферийным.
 *
 * Назначение:
 * - описывает этапы жизненного цикла соединения BLE;
 * - используется как единый тип состояния для UI и доменного слоя.
 *
 * Состояния:
 * - [Idle] — соединение не активно, исходное состояние.
 * - [Scanning] — выполняется поиск устройств.
 * - [Bonding] — выполняется сопряжение (создание доверенной пары).
 * - [Connecting] — выполняется подключение и настройка соединения.
 * - [Ready] — соединение установлено и готово к обмену командами и данными.
 *
 * События/ошибки:
 * - [Disconnected] — разрыв соединения, полученный как событие от системы Bluetooth.
 *   Поля:
 *   - [status] — код результата, который пришёл в обратном вызове Bluetooth;
 *   - [newState] — новое состояние соединения на уровне Bluetooth (как правило, DISCONNECTED/CONNECTED).
 * - [Error] — логическая ошибка на стороне приложения (например, не удалось настроить характеристики,
 *   не выполнены предусловия или получено исключение).
 */
sealed interface ConnectionState {
    /** Соединение отсутствует, исходное состояние. */
    data object Idle : ConnectionState
    /** Выполняется поиск BLE-устройств. */
    data object Scanning : ConnectionState
    /** Выполняется связывание */
    data object Bonding : ConnectionState
    /** Выполняется подключение и настройка соединения. */
    data object Connecting : ConnectionState
    /** Соединение установлено и готово к обмену командами и данными. */
    data object Ready : ConnectionState

    /**
     * Соединение разорвано (событие от системы Bluetooth).
     *
     * @param status код результата отключения из системного обратного вызова
     * @param newState новое состояние соединения из системного обратного вызова
     */
    data class Disconnected(val status: Int, val newState: Int): ConnectionState
    /**
     * Ошибка работы приложения (логическая/техническая).
     *
     * @param message текстовое описание причины ошибки
     */
    data class Error(val message: String) : ConnectionState
}