package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Сценарий использования: наблюдение за состоянием соединения.
 *
 * Назначение:
 * - предоставляет поток состояния соединения из [BleRepository];
 * - используется интерфейсом для реактивного отображения этапов подключения и отключения.
 *
 * Результат:
 * - [StateFlow]<[ConnectionState]> — текущее состояние соединения и его изменения.
 */
class ObserveConnectionStateUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke(): StateFlow<ConnectionState> = repo.connectionState
}