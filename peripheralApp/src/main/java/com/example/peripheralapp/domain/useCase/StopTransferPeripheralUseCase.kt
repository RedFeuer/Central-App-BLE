package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import javax.inject.Inject

/**
 * Сценарий остановки передачи данных (трансфер уведомлений Peripheral -> Central).
 *
 * Назначение:
 * - делегирует остановку передачи в [PeripheralRepository];
 * - используется моделью представления по команде "StopTransfer".
 *
 */
class StopTransferPeripheralUseCase (
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.stopTransfer()
}