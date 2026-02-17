package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository

class StartServerPeripheralUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.startServer()
}