package com.example.central_app_ble.presentation.viewModel

import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.ConnectionState

/**
 * Состояние пользовательского интерфейса главного экрана центрального BLE.
 *
 * Назначение:
 * - хранит данные, необходимые для отображения текущего выбора устройства и состояния соединения;
 * - используется UI-слоем для реактивного обновления элементов экрана.
 *
 * Поля:
 * - [selected] — выбранное BLE-устройство (результат сканирования). Может быть `null`,
 *   если устройство ещё не найдено или выбор сброшен.
 * - [connectionState] — текущее состояние соединения (Idle, Scanning, Bonding, Connecting, Ready).
 *   По умолчанию [ConnectionState.Idle].
 * - [isCentralStreaming] — признак того, что центральное устройство сейчас отправляет поток данных
 *   в периферийное устройство. Нужен для отображения статуса и предотвращения повторного запуска.
 */
data class UiState(
    val selected: BleDevice? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val isCentralStreaming: Boolean = false
)
