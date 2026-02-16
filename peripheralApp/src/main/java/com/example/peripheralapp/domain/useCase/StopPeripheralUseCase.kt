package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository

class StopPeripheralUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.stop()
}