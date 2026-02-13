package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow

class ObserveNotificationsUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke() : Flow<BleNotification> = repo.notifications
}