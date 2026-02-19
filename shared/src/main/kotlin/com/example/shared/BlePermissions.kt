package com.example.shared

import android.Manifest
import android.os.Build

/**
 * Набор функций для получения списка runtime-permissions Bluetooth,
 * которые нужно запросить у пользователя в зависимости от роли (Central/Peripheral)
 * и версии Android.
 *
 * Назначение:
 * - централизовать список разрешений в одном месте, чтобы не хардкодить это в UI;
 * - учитывать различия в модели разрешений до Android 12 (API < 31) и начиная с Android 12 (API 31+).
 *
 * Примечание:
 * - на Android 12+ используются отдельные runtime-разрешения Bluetooth:
 *   `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`;
 * - на Android 11 и ниже для сканирования (Central) обычно требуется `ACCESS_FINE_LOCATION`;
 * - для Peripheral на Android 11 и ниже отдельного runtime BLE-разрешения нет, поэтому возвращается пустой список.
 * Это было выяснено почти опытным путем при попытке сделать симметричный стенд:
 * До этого Central был на Android 16 (SDK 36), а Peripheral на Android 10 (SDK 29)
 * Я же после написания решил запустить в обратной связке и почему-то не мог отсканировать
 */
object BlePermissions {
    /**
     * Возвращает список runtime-разрешений для роли Central (сканирование + подключение).
     *
     * Android 12+ (API 31+):
     * - [Manifest.permission.BLUETOOTH_SCAN]
     * - [Manifest.permission.BLUETOOTH_CONNECT]
     *
     * Android 11 и ниже (API < 31):
     * - [Manifest.permission.ACCESS_FINE_LOCATION]
     *
     * @return массив разрешений, который нужно запросить у пользователя
     */
    fun centralRuntime(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    /**
     * Возвращает список runtime-разрешений для роли Peripheral (реклама + подключение).
     *
     * Android 12+ (API 31+):
     * - [Manifest.permission.BLUETOOTH_ADVERTISE]
     * - [Manifest.permission.BLUETOOTH_CONNECT]
     *
     * Android 11 и ниже (API < 31):
     * - отдельного runtime BLE-разрешения нет, поэтому список пустой.
     *
     * @return массив разрешений, который нужно запросить у пользователя;
     *         может быть пустым на Android 11 и ниже
     */
    fun peripheralRuntime(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            /* до 31 отдельного runtime BLE permission нет */
            emptyArray()
        }
}