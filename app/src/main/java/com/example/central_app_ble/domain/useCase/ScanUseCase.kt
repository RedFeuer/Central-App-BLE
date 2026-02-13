package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.repository.BleRepository

class ScanUseCase(
    private val repo: BleRepository,
) {
    suspend operator fun invoke(timeoutMs: Long): BleDevice? =
        repo.scanFirst(timeoutMs)
}