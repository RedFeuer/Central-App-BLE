package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository

class DisconnectUseCase(
    private val repo: BleRepository,
) {
    operator fun invoke() = repo.disconnect()
}