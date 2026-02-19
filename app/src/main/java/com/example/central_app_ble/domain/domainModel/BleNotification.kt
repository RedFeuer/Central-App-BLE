package com.example.central_app_ble.domain.domainModel

import com.example.shared.Command

/**
 * Уведомления, приходящие в Central от периферийного устройства через notify.
 *
 * Назначение:
 * - представить входящие сообщения от Peripheral в типизированном виде;
 * - отделить что пришло (команда или данные) от низкоуровневых деталей BLE
 *   (UUID характеристик, массивы байтов в обратных вызовах GATT).
 *
 * Варианты:
 * - [Cmd] — командное сообщение (например, ответ Pong на Ping).
 * - [Data] — блок данных (например, фрагмент потока данных от Peripheral).
 */
sealed interface BleNotification {
    /**
     * Команда, полученная от Peripheral.
     *
     * @param cmd декодированная команда протокола;
     *            может быть `null`, если декодирование не удалось или пришли некорректные данные.
     */
    data class Cmd(val cmd: Command?) : BleNotification

    /**
     * Блок данных, полученный от Peripheral.
     *
     * Важно про сравнение:
     * - стандартное сравнение массивов в Kotlin (включая [ByteArray]) выполняется по ссылке, а не по содержимому;
     * - здесь переопределены [equals] и [hashCode], чтобы сравнение выполнялось по содержимому массива:
     *   - [equals] использует [ByteArray.contentEquals];
     *   - [hashCode] использует [ByteArray.contentHashCode].
     *
     * Это полезно, если:
     * - объект сравнивается через `==` (например, в тестах);
     * - объект хранится в коллекциях, где важны [equals]/[hashCode] (например, Set/Map);
     * - требуется корректно определять “одинаковые данные” по байтам.
     *
     * @param bytes полезная нагрузка уведомления
     */
    data class Data(val bytes: ByteArray) : BleNotification {
        override fun equals(other: Any?): Boolean {
            return other is Data && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            return bytes.contentHashCode()
        }
    }
}