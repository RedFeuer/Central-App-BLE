package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository

class StopServerPeripheralUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.stopServer()
}