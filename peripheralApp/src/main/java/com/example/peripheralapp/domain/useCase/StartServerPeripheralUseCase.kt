package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository

/**
 * Сценарий запуска BLE-сервера периферийного устройства.
 *
 * Назначение:
 * - делегирует запуск сервера в [PeripheralRepository];
 * - используется моделью представления как единая точка входа для команды "Start Server".
 *
 */
class StartServerPeripheralUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.startServer()
}