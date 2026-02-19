package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow

/**
 * Сценарий использования: наблюдение за журналом работы BLE-слоя.
 *
 * Назначение:
 * - предоставляет поток строк журнала из [BleRepository];
 * - используется интерфейсом для реактивного отображения сообщений.
 *
 * Результат:
 * - [Flow]<String> — поток строк журнала.
 */
class ObserveLogsUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke() : Flow<String> = repo.logs
}