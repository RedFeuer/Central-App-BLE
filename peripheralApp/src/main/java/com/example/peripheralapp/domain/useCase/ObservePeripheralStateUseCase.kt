package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.peripheralapp.domain.repository.PeripheralRepository
import kotlinx.coroutines.flow.StateFlow

class ObservePeripheralStateUseCase(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() : StateFlow<PeripheralState> = repo.state
}