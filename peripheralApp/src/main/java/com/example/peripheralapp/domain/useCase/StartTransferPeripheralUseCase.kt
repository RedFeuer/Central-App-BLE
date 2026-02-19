package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import javax.inject.Inject

/**
 * Сценарий запуска передачи данных (трансфер уведомлений Peripheral -> Central).
 *
 * Назначение:
 * - делегирует запуск передачи в [PeripheralRepository];
 * - используется моделью представления по команде "StartTransfer".
 *
 */
class StartTransferPeripheralUseCase (
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.startTransfer()
}