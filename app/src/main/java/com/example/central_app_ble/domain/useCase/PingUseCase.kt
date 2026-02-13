package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command

class PingUseCase(
    private val repo: BleRepository,
) {
    suspend operator fun invoke() = repo.sendCmd(Command.Ping)
}