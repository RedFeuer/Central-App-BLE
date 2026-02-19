package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case для наблюдения за уведомлениями от Peripheral устройства.
 *
 * Назначение:
 * - предоставляет верхнему слою (viewModel) единый способ подписаться
 *   на поток входящих уведомлений от Peripheral;
 *
 * Что приходит в потоке:
 * - [BleNotification.Cmd] — командные сообщения (например, ответы на команды);
 * - [BleNotification.Data] — блоки данных, приходящие через notify.
 *
 * @return поток [BleNotification], который публикует репозиторий
 */
class ObserveNotificationsUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke() : Flow<BleNotification> = repo.notifications
}