package com.example.peripheralapp.domain.useCase

import com.example.peripheralapp.domain.repository.PeripheralRepository
import javax.inject.Inject

class StartTransferPeripheralUseCase @Inject constructor(
    private val repo: PeripheralRepository,
) {
    operator fun invoke() = repo.startTransfer()
}