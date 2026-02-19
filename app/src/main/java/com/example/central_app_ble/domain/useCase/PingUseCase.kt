package com.example.central_app_ble.domain.useCase

import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command

/**
 * Сценарий использования: отправка команды проверки связи.
 *
 * Роль:
 * - отправляет в подключённое устройство команду [Command.Ping] через [BleRepository].
 *
 * Примечания:
 * - предполагается, что соединение уже установлено и готово к обмену командами;
 * - обработка ответа и изменения состояния выполняются в репозитории.
 */
class PingUseCase(
    private val repo: BleRepository,
) {
    /**
     * Отправляет команду проверки связи (PING).
     */
    suspend operator fun invoke() = repo.sendCmd(Command.Ping)
}