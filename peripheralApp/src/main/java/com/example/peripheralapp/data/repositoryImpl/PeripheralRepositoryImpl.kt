package com.example.peripheralapp.data.repositoryImpl

import android.Manifest
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.data.ble.AndroidBlePeripheralServer
import com.example.peripheralapp.data.ble.PeripheralLogBus
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Реализация [PeripheralRepository] на Android BLE API.
 *
 * Логика:
 * - [state] берётся напрямую из [AndroidBlePeripheralServer];
 * - [logs] формируются через [PeripheralLogBus];
 * - при запуске/остановке сервера выполняются проверки:
 *   - повторный запуск/остановка не выполняются;
 *   - при отсутствии поддержки BLE-периферии операция не выполняется;
 * - основные действия делегируются в [AndroidBlePeripheralServer].
 *
 * Важно:
 * - документация у `override`-членов должна полностью совпадать с документацией в интерфейсе
 *   (контракт задаётся интерфейсом).
 */
class PeripheralRepositoryImpl @Inject constructor (
    private val server: AndroidBlePeripheralServer,
    private val logBus: PeripheralLogBus,
) : PeripheralRepository {
    /**
     * Поток состояния периферийной части.
     *
     * Свойства:
     * - содержит последнее известное состояние;
     * - используется интерфейсом для реактивного отображения признаков работы сервера,
     *   подключений, подписок и ошибок.
     */
    override val state: StateFlow<PeripheralState> = server.state

    /**
     * Поток строк журнала работы периферийной части.
     *
     * Используется для вывода диагностических сообщений в интерфейсе.
     */
    override val logs: Flow<String> = logBus.logs

    /**
     * Запускает BLE-сервер периферийного устройства.
     *
     * Поведение:
     * - поднимает GATT-сервер;
     * - включает рекламу, чтобы центральное устройство могло найти и подключиться;
     * - обновляет [state] и пишет диагностические сообщения в [logs].
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun startServer() {
        if (state.value.isRunning) {
            logBus.log(message = "Peripheral уже запущен")
            return
        }
        if (!server.isPeripheralSupported()) {
            logBus.log(message = "Peripheral не поддерживается")
            return
        }
        server.start() // стартуем сервак
        logBus.log(message = "Peripheral запущен: advertising + GATT server")
    }

    /**
     * Останавливает BLE-сервер периферийного устройства.
     *
     * Поведение:
     * - останавливает рекламу;
     * - закрывает GATT-сервер и освобождает ресурсы Bluetooth;
     * - обновляет [state] и пишет диагностические сообщения в [logs].
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun stopServer() {
        if (!state.value.isRunning) {
            logBus.log(message = "Peripheral уже остановлен")
            return
        }
        server.stop() // останавливаем сервак
        logBus.log(message = "Peripheral приостановлен")
    }

    /**
     * Запускает передачу данных от периферийного устройства к центральному.
     *
     * Поведение:
     * - начинает периодически отправлять данные через уведомления (notify);
     * - обновляет [state] и пишет диагностические сообщения в [logs], если это предусмотрено реализацией.
     *
     * Примечание:
     * - фактическая отправка данных возможна только при наличии подписки у центрального устройства
     *   на соответствующую характеристику.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun startTransfer() {
        server.startTransfer()
    }

    /**
     * Останавливает передачу данных от периферийного устройства к центральному.
     *
     * Поведение:
     * - прекращает периодическую отправку данных;
     * - обновляет [state] и пишет диагностические сообщения в [logs], если это предусмотрено реализацией.
     */
    override fun stopTransfer() {
        server.stopTransfer()
    }

    /**
     * Сбрасывает ошибку в состоянии периферийной части.
     *
     * Назначение:
     * - очищает поле ошибки в [state], чтобы интерфейс мог убрать отображение ошибки после ознакомления.
     */
    override fun clearStateError() {
        server.clearError()
    }
}