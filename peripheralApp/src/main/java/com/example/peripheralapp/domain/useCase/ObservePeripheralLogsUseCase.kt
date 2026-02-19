package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.Flow

/**
 * Сценарий наблюдения за журналом работы периферийной части.
 *
 * Назначение:
 * - предоставляет поток строк журнала из [PeripheralRepository];
 * - используется интерфейсом для отображения диагностических сообщений.
 *
 */
class ObservePeripheralLogsUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke(): Flow<String> = repo.logs
}