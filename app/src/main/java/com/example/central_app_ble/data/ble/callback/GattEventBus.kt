package com.example.central_app_ble.data.ble.callback

import com.example.central_app_ble.di.AppScope
import com.example.central_app_ble.domain.domainModel.BleNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * События GATT-уровня, публикуемые BLE-слоем в шину событий.
 *
 * Назначение:
 * - передавать наверх (в репозиторий и далее) единообразные события работы GATT;
 * - разделять типы событий: журнал, входящие уведомления, разрыв соединения.
 *
 * Варианты:
 * - [Log] — строка журнала для отображения и отладки.
 * - [Notify] — входящее уведомление от периферийного устройства (данные/событие).
 * - [Disconnected] — разрыв соединения, пришедший как системное событие Bluetooth.
 */
sealed interface GattEvent {
    /**
     * Сообщение журнала BLE-части.
     *
     * @param line текст сообщения
     */
    data class Log(val line: String) : GattEvent
    /**
     * Уведомление от периферийного устройства.
     *
     * @param notification данные уведомления в доменной форме
     */
    data class Notify(val notification: BleNotification) : GattEvent
    /**
     * Событие разрыва соединения.
     *
     * @param status код результата отключения из системного обратного вызова
     * @param newState новое состояние соединения из системного обратного вызова
     */
    data class Disconnected(val status: Int, val newState: Int): GattEvent
}

/**
 * Шина событий GATT-уровня.
 *
 * Назначение:
 * - предоставляет единый поток [events], на который подписываются репозиторий;
 * - собирает события из разных частей BLE-слоя (сканирование, подключение, обмен данными, обратные вызовы);
 * - обеспечивает единый канал для:
 *   1) строк журнала,
 *   2) входящих уведомлений,
 *   3) события разрыва соединения.
 *
 * Архитектурная роль:
 * - отделяет низкоуровневые обратные вызовы Bluetooth от доменного слоя:
 *   BLE-код публикует события сюда, а репозиторий забирает их в свой API.
 *
 * Буфер:
 * - используется [MutableSharedFlow] с буфером, чтобы отправители не блокировались при всплесках событий.
 */
@Singleton
class GattEventBus @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
) {
    /**
     * Внутренний поток событий.
     *
     * `extraBufferCapacity` позволяет делать `tryEmit` без приостановки,
     * если подписчик временно не успевает обработать событие.
     */
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 512)
    /**
     * Публичный поток событий GATT-уровня.
     *
     * На этот поток подписывается репозиторий, чтобы строить:
     * - журнал [BleRepository.logs],
     * - уведомления [BleRepository.notifications],
     * - состояние соединения (через обработку [GattEvent.Disconnected]).
     */
    val events = _events.asSharedFlow()

    /**
     * Публикует строку журнала.
     *
     * Используется для отладки и отображения диагностических сообщений в интерфейсе.
     */
    fun log(line: String) {
        _events.tryEmit(GattEvent.Log(line))
    }

    /**
     * Публикует уведомление от периферийного устройства.
     *
     * Используется для передачи входящих данных/событий наверх по слоям.
     */
    fun notify(n: BleNotification) {
        _events.tryEmit(GattEvent.Notify(n))
    }

    /**
     * Публикует событие разрыва соединения.
     *
     * Почему используется `emit` в [appScope], а не `tryEmit`:
     * - разрыв соединения — критичное событие, которое желательно не терять;
     * - `emit` приостанавливается, пока событие не будет помещено в поток
     *
     * Параметры:
     * - [status] и [newState] берутся из системного обратного вызова Bluetooth.
     */
    fun disconnected(status: Int, newState: Int) {
        appScope.launch {
            _events.emit(GattEvent.Disconnected(status, newState))
        }
    }
}