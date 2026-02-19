package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.repository.BleRepository

/**
 * Сценарий использования: подключение к выбранному BLE-устройству.
 *
 * Роль:
 * - делегирует подключение в [BleRepository];
 * - инициирует связывание и установление соединения
 *
 * Параметры:
 * - [device] — устройство, к которому нужно подключиться.
 */
class ConnectUseCase(
    private val repo: BleRepository,
) {
    /**
     * Выполняет подключение к устройству [device].
     */
    suspend operator fun invoke(device: BleDevice) = repo.connect(device)
}