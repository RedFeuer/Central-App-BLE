package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import javax.inject.Inject

class StopTransferPeripheralUseCase @Inject constructor(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.stopTransfer()
}