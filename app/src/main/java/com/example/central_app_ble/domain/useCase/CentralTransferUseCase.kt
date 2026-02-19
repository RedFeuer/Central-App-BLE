package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Protocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Сценарий использования: трансфер потока данных от центрального устройства к периферийному.
 *
 * Назначение:
 * - периодически формирует блоки данных фиксированного размера и отправляет их в периферийное устройство;
 * - соответствует требованиям потока: 160 байт каждые 60 мс.
 *
 * Входные параметры:
 * - [durationMs] — длительность передачи в миллисекундах.
 *
 * Поведение:
 * - пока корутина активна и не истекло [durationMs]:
 *   1) формирует блок данных размером [Protocol.STREAM_BLOCK_SIZE];
 *   2) отправляет блок через [BleRepository.writeCentralData];
 *   3) ожидает [Protocol.STREAM_PERIOD_MS] перед отправкой следующего блока.
 *
 * Результат:
 * - возвращает число успешно инициированных отправок блоков за время передачи.
 *
 * Примечания:
 * - остановка передачи выполняется отменой корутины;
 */
class CentralTransferUseCase(
    private val repo: BleRepository,
) {
    /* central -> peripheral: блоки 160 байт каждые 60мс
    * возвращает количество отправленных блоков */
    suspend operator fun invoke(durationMs: Long): Int {
        val start = System.currentTimeMillis()
        var sent = 0

        while (currentCoroutineContext().isActive && System.currentTimeMillis() - start < durationMs) {
            /* генерация блока 160 байт */
            val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE) { (it and 0xFF).toByte() } // массив из Byte [0, 255]
            repo.writeCentralData(buf)
            ++sent
            /* отрпавка каждые 60мс */
            delay(Protocol.STREAM_PERIOD_MS)
        }
        return sent
    }
}