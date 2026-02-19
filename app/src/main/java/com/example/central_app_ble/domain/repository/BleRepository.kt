package com.example.central_app_ble.domain.repository

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.shared.Command
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Слой доступа к BLE для центрального устройства.
 *
 * Назначение:
 * - предоставляет операции сканирования, подключения и отключения;
 * - обеспечивает отправку команд и передачу данных в периферийное устройство;
 * - отдаёт наружу потоки состояния соединения, журнала и входящих уведомлений.
 */
interface BleRepository {
    /**
     * Поток состояния соединения центрального устройства с периферийным.
     *
     * Свойства:
     * - содержит последнее известное состояние;
     * - начальное значение — [ConnectionState.Idle];
     * - значения обновляются при запуске сканирования, подключении, готовности и отключении.
     */
    val connectionState: StateFlow<ConnectionState>
    /**
     * Поток строк журнала работы BLE-части.
     *
     * Используется для вывода диагностических сообщений в интерфейсе.
     */
    val logs: Flow<String>
    /**
     * Поток уведомлений, приходящих от периферийного устройства.
     *
     * Используется для приёма данных/событий, отправляемых периферийной стороной.
     */
    val notifications: Flow<BleNotification>

    /**
     * Выполняет сканирование и возвращает первое найденное устройство.
     *
     * Параметры:
     * - [timeoutMs] — максимальное время сканирования в миллисекундах.
     *
     * Результат:
     * - [BleDevice] — первое найденное устройство;
     * - `null` — если за [timeoutMs] ничего не найдено.
     *
     * Примечание:
     * - реализация переводит [connectionState] в [ConnectionState.Scanning] на время поиска.
     */
    suspend fun scanFirst(timeoutMs: Long): BleDevice?

    /**
     * Подключается к указанному устройству и подготавливает соединение к обмену данными.
     *
     * Параметры:
     * - [device] — устройство, к которому нужно подключиться.
     *
     * Поведение:
     * - может включать сопряжение (создание пары), если это требуется;
     * - после успешного подключения переводит [connectionState] в [ConnectionState.Ready].
     *
     * Ошибки:
     * - при неудаче может выбросить исключение (например, ошибка подключения/инициализации).
     */
    suspend fun connect(device: BleDevice)

    /**
     * Отключается от устройства и освобождает ресурсы Bluetooth.
     *
     * Поведение:
     * - закрывает активное GATT-соединение (если оно есть);
     * - переводит [connectionState] в [ConnectionState.Idle].
     */
    fun disconnect()

    /**
     * Отправляет команду в подключённое устройство.
     *
     * Параметры:
     * - [cmd] — команда протокола.
     *
     * Предусловие:
     * - соединение должно быть установлено и готово к обмену (обычно [ConnectionState.Ready]).
     *
     */
    suspend fun sendCmd(cmd: Command)

    /* central -> peripheral */
    /**
     * Отправляет блок данных от центрального устройства к периферийному.
     *
     * Параметры:
     * - [bytes] — отправляемые данные.
     *
     * Предусловие:
     * - соединение должно быть установлено и готово к обмену (обычно [ConnectionState.Ready]).
     *
     * Примечание:
     * - метод предназначен для трансфера потока данных.
     */
    suspend fun writeCentralData(bytes: ByteArray)
}