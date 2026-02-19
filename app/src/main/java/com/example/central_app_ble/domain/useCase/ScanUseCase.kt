package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.repository.BleRepository

/**
 * Сценарий использования: сканирование BLE и выбор первого подходящего устройства.
 *
 * Роль:
 * - делегирует работу в [BleRepository];
 * - возвращает первое найденное устройство или `null`, если за отведённое время ничего не найдено.
 *
 * Параметры:
 * - [timeoutMs] — максимальное время сканирования в миллисекундах.
 *
 * Результат:
 * - [BleDevice] — найденное устройство;
 * - `null` — если устройство не найдено.
 */
class ScanUseCase(
    private val repo: BleRepository,
) {
    /**
     * Запускает сканирование и возвращает первое найденное устройство.
     */
    suspend operator fun invoke(timeoutMs: Long): BleDevice? =
        repo.scanFirst(timeoutMs)
}