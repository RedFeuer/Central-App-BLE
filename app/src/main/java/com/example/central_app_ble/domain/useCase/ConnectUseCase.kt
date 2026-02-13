package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.repository.BleRepository

class ConnectUseCase(
    private val repo: BleRepository,
) {
    suspend operator fun invoke(device: BleDevice) = repo.connect(device)
}