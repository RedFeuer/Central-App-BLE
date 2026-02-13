package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command

class PeripheralTxControlUseCase(
    private val repo: BleRepository,
) {
    suspend fun start() = repo.sendCmd(Command.StartStream)

    suspend fun stop() = repo.sendCmd(Command.StopStream)
}