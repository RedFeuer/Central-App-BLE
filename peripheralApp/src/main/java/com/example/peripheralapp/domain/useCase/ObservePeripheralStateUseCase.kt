package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Сценарий наблюдения за состоянием периферийной части.
 *
 * Назначение:
 * - предоставляет наружу поток состояния [PeripheralState] из [PeripheralRepository];
 * - используется интерфейсом для реактивного обновления экрана.
 *
 * Свойства потока:
 * - содержит последнее известное состояние;
 * - обновляется при запуске/остановке сервера, изменениях подключений и подписок, ошибках.
 */
class ObservePeripheralStateUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() : StateFlow<PeripheralState> = repo.state
}