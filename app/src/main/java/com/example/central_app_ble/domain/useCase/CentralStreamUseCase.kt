package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Protocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class CentralStreamUseCase(
    private val repo: BleRepository,
) {
    /* central -> peripheral: блоки 160 байт каждые 60мс
    * возвращает количество отправленных блоков*/
    suspend operator fun invoke(durationMs: Long): Int {
        val start = System.currentTimeMillis()
        var sent = 0

        while (currentCoroutineContext().isActive && System.currentTimeMillis() - start < durationMs) {
            val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE) { (it and 0xFF).toByte() } // массив из Byte [0, 255]
            repo.writeCentralData(buf)
            ++sent
            delay(Protocol.STREAM_PERIOD_MS)
        }
        return sent
    }
}