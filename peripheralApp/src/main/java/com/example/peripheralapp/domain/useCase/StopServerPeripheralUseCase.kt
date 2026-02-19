package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository

/**
 * Сценарий остановки BLE-сервера периферийного устройства.
 *
 * Назначение:
 * - делегирует остановку сервера в [PeripheralRepository];
 * - используется моделью представления как единая точка входа для команды "Stop Server".
 *
 */
class StopServerPeripheralUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.stopServer()
}