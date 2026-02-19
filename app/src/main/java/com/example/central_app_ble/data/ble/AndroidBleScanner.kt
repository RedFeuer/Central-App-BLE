package com.example.central_app_ble.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.example.shared.BleUuids
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Сканер Peripheral BLE-устройств для роли Central.
 *
 * Назначение:
 * - выполняет сканирование BLE и возвращает первое найденное устройство,
 *   которое подходит под заданный фильтр (по UUID сервиса).
 *
 * Особенности реализации:
 * - использует системный [BluetoothLeScanner];
 * - оборачивает обратные вызовы сканирования в поток через [callbackFlow];
 * - прекращает сканирование при получении первого результата или по таймауту.
 */
class AndroidBleScanner @Inject constructor() {
    /** Адаптер Bluetooth устройства (точка входа в Bluetooth API). */
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    /** Системный сканер BLE
     * ленивая инициализация, чтобы создавать сканер только при необходимости
     * и не трогать Bluetooth заранее. Например, когда зависимость уже подтянулась,
     * а Bluetooth еще не был включен */
    private val scanner: BluetoothLeScanner by lazy { adapter.bluetoothLeScanner }

    /**
     * Результат сканирования.
     *
     * Поля:
     * - [device] — найденное устройство;
     * - [rssi] — уровень сигнала, полезен для оценки дальности и выбора устройства.
     */
    data class Result(val device: BluetoothDevice, val rssi: Int)

    /**
     * Выполняет сканирование и возвращает первое найденное устройство, подходящее под фильтр.
     *
     * Фильтрация:
     * - выбираются только устройства, которые рекламируют сервис с UUID [BleUuids.SERVICE].
     *
     * Настройки:
     * - режим [ScanSettings.SCAN_MODE_LOW_LATENCY] для максимальной скорости обнаружения.
     *
     * Остановка:
     * - при получении первого результата метод возвращает его немедленно;
     * - если за [timeoutMs] ничего не найдено — возвращает `null`;
     * - при ошибке сканирования — возвращает `null`.
     *
     * Предусловия:
     * - требуется системное разрешение [Manifest.permission.BLUETOOTH_SCAN].
     *
     * @param timeoutMs максимальное время сканирования в миллисекундах
     * @return первый найденный [Result] или `null`, если ничего не найдено, либо ошибка, либо таймаут
     */
    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    suspend fun scanFirst(timeoutMs: Long): Result? {
        /* фильтруем и берем только устройства с UUID = BleUuids.SERVICE */
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()

        /* настройки сканера */
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val flow = callbackFlow {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    trySend(Result(result.device, result.rssi)) // нашли девайс
                }
                override fun onScanFailed(errorCode: Int) {
                    close() // просто завершаем без результата
                }
            }

            /* старт сканирования с фильтром и настройками */
            scanner.startScan(listOf(filter), settings, callback)

            /* гарантировано останавливаем скан при завершении flow
            * при превышении timeoutMs в withTimeoutOrNull */
            awaitClose {
                runCatching { scanner.stopScan(callback) }
            }
        }

        /* не укладываемся в тайминги или в потоке нет элементо - null + закрытие flow,
        * иначе - первый найденный Result из потока */
        return withTimeoutOrNull(timeoutMs) {
            flow.firstOrNull() // возвращаем первый найденный сразу как получили его
        }
    }
}