package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.Flow

class ObservePeripheralLogsUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke(): Flow<String> = repo.logs
}