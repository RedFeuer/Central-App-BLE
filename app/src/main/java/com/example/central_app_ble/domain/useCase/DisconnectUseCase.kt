package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository

/**
 * Сценарий использования: отключение от периферийного устройства.
 *
 * Назначение:
 * - делегирует отключение в [BleRepository];
 * - используется для ручного отключения по кнопке и для очистки ресурсов при завершении работы.
 *
 * Примечания:
 * - реализация репозитория должна корректно закрывать соединение (GATT) и приводить
 *   внутреннее состояние к исходному (Idle).
 */
class DisconnectUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke() = repo.disconnect()
}