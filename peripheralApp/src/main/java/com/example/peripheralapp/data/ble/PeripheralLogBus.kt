package com.example.peripheralapp.data.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/* singleton to maintain state correct during sessions */
/**
 * Шина сообщений журнала для периферийной части.
 *
 * Назначение:
 * - хранит единый источник строк журнала для всего приложения;
 * - отправляет строки журнала в системный журнал и в интерфейс (UI);
 * - обеспечивает сохранение целостной истории сообщений в пределах работы приложения.
 *
 * Почему это singleton:
 * - один экземпляр нужен, чтобы журнал не обнулялся при пересоздании viewModel;
 * - UI может переподписываться на [logs], не влияя на источник сообщений.
 *
 */
@Singleton
class PeripheralLogBus @Inject constructor () {
    /**
     * Внутренний поток строк журнала.
     *
     * Настройки:
     * - [extraBufferCapacity] даёт небольшой буфер, чтобы не блокировать поток,
     *   когда UI временно не успевает обрабатывать сообщения.
     */
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 512)
    /**
     * Поток строк журнала для подписки из viewModel.
     *
     * Свойства:
     * - доступен только на чтение;
     * - передаёт строки по мере поступления.
     */
    val logs = _logs.asSharedFlow()

    /**
     * Добавляет строку в журнал.
     *
     * Делает два действия:
     * 1) пишет сообщение в системный журнал устройства;
     * 2) публикует сообщение в [logs], чтобы его мог отобразить UI.
     *
     * Параметры:
     * - [tag] — метка сообщения для системного журнала;
     * - [message] — текст сообщения.
     *
     * Примечание:
     * - используется неблокирующая отправка в поток (`tryEmit`); если буфер переполнен,
     *   сообщение может не попасть в [logs].
     */
    fun log(
        tag: String = "Peripheral BLE",
        message: String
    ) {
        /* логируем в Logcat */
        Log.i(tag, message)
        /* отправка лога в UI на Peripheral */
        _logs.tryEmit(message)
    }
}