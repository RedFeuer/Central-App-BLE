package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import kotlinx.coroutines.flow.StateFlow

class ObserveConnectionStateUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke(): StateFlow<ConnectionState> = repo.connectionState
}