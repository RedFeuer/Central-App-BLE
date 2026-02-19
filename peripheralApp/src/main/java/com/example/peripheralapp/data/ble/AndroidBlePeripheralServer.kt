package com.example.peripheralapp.data.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.domain.domainModel.PeripheralState
import com.example.shared.BleUuids
import com.example.shared.Command
import com.example.shared.CommandCodec
import com.example.shared.Protocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * BLE-периферийная часть: реклама (Advertising) + GATT-сервер + потоковая отправка данных.
 *
 * Назначение:
 * - поднимает GATT-сервис [BleUuids.SERVICE] с характеристиками команд и данных;
 * - запускает рекламу, чтобы Central мог найти устройство и подключиться;
 * - принимает записи в RX-характеристики (Central -> Peripheral);
 * - отправляет уведомления (notify) в TX-характеристики (Peripheral -> Central);
 * - ведёт учёт подключённых устройств и подписок на уведомления;
 * - публикует реактивное состояние [state] для UI.
 *
 * Потоки и безопасность:
 * - обратные вызовы GATT приходят из системных потоков;
 * - множества [connected], [subscribedCmd], [subscribedData] — потокобезопасные (ConcurrentHashMap.newKeySet);
 * - состояние [state] обновляется через [MutableStateFlow].
 *
 * Протокол:
 * - команды принимаются через CMD_RX и отдаются через CMD_TX (notify);
 * - поток данных принимается через DATA_RX, а также может отдаваться через DATA_TX (notify);
 * - параметры размера блока и периода берутся из [Protocol].
 *
 * Важно:
 * - для корректной работы уведомлений Central обязан записать CCCD-дескриптор соответствующей TX-характеристики
 *   (подписаться на notify).
 */
class AndroidBlePeripheralServer @Inject constructor (
    @ApplicationContext private val context: Context,
    private val logBus: PeripheralLogBus,
) {
    /** Системный BluetoothManager, используемый для открытия GATT-сервера. */
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    /** Bluetooth-адаптер устройства. */
    private val adapter: BluetoothAdapter = btManager.adapter

    /** Текущий экземпляр GATT-сервера (null, если сервер не запущен или уже остановлен). */
    private var gattServer: BluetoothGattServer? = null
    /** Текущий экземпляр рекламодателя (null, если реклама не запущена или не поддерживается). */
    private var advertiser: BluetoothLeAdvertiser? = null

    /**
     * Набор Central-устройств, подписанных на уведомления по CMD_TX.
     *
     * Подписка выставляется при записи Central в CCCD-дескриптор CMD_TX.
     */
    private val subscribedCmd = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    /**
     * Набор Central-устройств, подписанных на уведомления по DATA_TX.
     *
     * Подписка выставляется при записи Central в CCCD-дескриптор DATA_TX.
     */
    private val subscribedData = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    /**
     * Набор подключённых Central-устройств.
     *
     * Заполняется и очищается по событиям [BluetoothGattServerCallback.onConnectionStateChange].
     */
    private val connected = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    /**
     * TX-характеристика для отправки команд (Peripheral -> Central) через notify.
     * Инициализируется при создании GATT-сервера в [startGattServer].
     */
    private lateinit var cmdTxChar: BluetoothGattCharacteristic
    /**
     * TX-характеристика для отправки данных (Peripheral -> Central) через notify.
     * Инициализируется при создании GATT-сервера в [startGattServer].
     */
    private lateinit var dataTxChar: BluetoothGattCharacteristic

    /**
     * Область выполнения для фонового трансфера данных (TX stream).
     *
     * Отдельная область нужна, чтобы передача не блокировала главный поток и коллбеки Bluetooth.
     */
    private val txScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Текущая задача передачи данных (null, если поток не запущен). */
    private var txJob: Job? = null
    /** Счётчик последовательности для формирования блоков потока. */
    private var txSeq = 0

    /** Внутреннее состояние периферийной части. */
    private val _state = MutableStateFlow(PeripheralState())
    /**
     * Поток состояния периферийной части.
     *
     * Используется UI для отображения:
     * - поддержки BLE/рекламы на устройстве;
     * - запуска сервера;
     * - числа подключений и подписок;
     * - последней ошибки.
     */
    val state: StateFlow<PeripheralState> = _state.asStateFlow()

    /**
     * Проверяет, поддерживает ли устройство режим периферии с рекламой.
     *
     * @return `true`, если доступен [BluetoothLeAdvertiser]; иначе `false`.
     */
    fun isPeripheralSupported(): Boolean = adapter.bluetoothLeAdvertiser != null

    /**
     * Запускает периферийную часть: GATT-сервер и advertising.
     *
     * Предусловия:
     * - устройство поддерживает рекламу (иначе фиксируется ошибка в [state]);
     * - Bluetooth включён (иначе выбрасывается исключение).
     *
     * Поведение:
     * - по возможности устанавливает имя устройства [deviceName];
     * - создаёт GATT-сервис/характеристики и публикует сервис;
     * - запускает рекламу с UUID сервиса [BleUuids.SERVICE];
     * - выставляет `isRunning = true`, сбрасывает `lastError`;
     * - публикует счётчики подключений/подписок через [publishCounters].
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(deviceName: String = "BLE-Peripheral") {
        if (!isPeripheralSupported()) {
            _state.value = _state.value.copy(isSupported = false, lastError = "Peripheral/Advertising не поддерживается")
            logBus.log(message = "Peripheral/Advertising НЕ поддерживается")
            return
        }

        require(adapter.isEnabled) { "Bluetooth выключен" }

        runCatching { adapter.name = deviceName }.onFailure {
            logBus.log(message = "setName failed: ${it.message}")
        }

        startGattServer()
        startAdvertising()

        _state.value = _state.value.copy(isSupported = true, isRunning = true, lastError = null)
        logBus.log(message = "Peripheral запущен: advertising + GATT server")
        publishCounters()
    }

    /**
     * Останавливает периферийную часть и освобождает ресурсы Bluetooth.
     *
     * Поведение:
     * - останавливает потоковую передачу [stopTransfer];
     * - разрывает соединения со всеми подключёнными Central [disconnectAllCentrals];
     * - останавливает рекламу и закрывает GATT-сервер;
     * - очищает множества подписок на команды и информацию, а также подключений;
     * - выставляет `isRunning = false` и публикует счётчики.
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun stop() {
        stopTransfer()

        /* отключаем все Central девайсы от Peripheral при остановке сервера */
        disconnectAllCentrals()

        runCatching {
            advertiser?.stopAdvertising(advCallback)
        }.onFailure { logBus.log(message = "stopAdvertising failed: ${it.message}") }
        advertiser = null

        runCatching {
            gattServer?.close()
        }.onFailure { logBus.log(message = "gattServer.close failed: ${it.message}") }
        gattServer = null

        subscribedCmd.clear()
        subscribedData.clear()
        connected.clear()

        _state.value = _state.value.copy(isRunning = false)
        logBus.log(message = "Peripheral stopped")
        publishCounters()
    }

    /**
     * Разрывает соединение со всеми подключёнными Central.
     *
     * Используется при остановке сервера, чтобы принудительно закрыть активные соединения.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disconnectAllCentrals() {
        val server = gattServer ?: return
        logBus.log(message = "disconnectAllCentrals: connected=${connected.size}")

        /* отключаем от Peripheral все Central */
        for (dev in connected) {
            runCatching { server.cancelConnection(dev) }
                .onFailure { logBus.log(message = "cancelConnection failed ${dev.address}: ${it.message}") }
        }
    }

    /* Peripheral -> Central */
    /**
     * Запускает потоковую передачу данных (Peripheral -> Central) через уведомления DATA_TX (notify).
     *
     * Поведение:
     * - если поток уже запущен, повторно задачу не создаёт;
     * - циклически формирует блок размером [Protocol.STREAM_BLOCK_SIZE];
     * - в первые 4 байта пишет счётчик последовательности [txSeq];
     * - отправляет блок всем устройствам из [subscribedData];
     * - выдерживает период [Protocol.STREAM_PERIOD_MS].
     *
     * Примечание:
     * - данные реально получат только те Central, которые подписались на DATA_TX (записали CCCD).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startTransfer() {
        /* если Transfer уже запущен, то не плодим задачи, а просто выходим */
        if (txJob?.isActive == true) {
            logBus.log(message = "Peripheral TX is already started")
            return
        }

        txJob = txScope.launch {
            var tick = 0
            while (isActive) {
                val buf = ByteArray(Protocol.STREAM_BLOCK_SIZE)

                val s = txSeq++
                buf[0] = (s and 0xFF).toByte()
                buf[1] = ((s shr 8) and 0xFF).toByte()
                buf[2] = ((s shr 16) and 0xFF).toByte()
                buf[3] = ((s shr 24) and 0xFF).toByte()

                if (tick++ % 16 == 0) {
                    logBus.log(message = "TX tick seq=$s subscribedData=${subscribedData.size}")
                }

                for (dev in subscribedData) {
                    notifyData(dev, buf)
                }

                delay(Protocol.STREAM_PERIOD_MS)
            }
        }
        logBus.log(message = "Peripheral TX started; subscribedData=${subscribedData.size}")
    }

    /**
     * Останавливает потоковую передачу данных, если она запущена.
     *
     * Поведение:
     * - отменяет задачу передачи и очищает ссылку на неё;
     * - пишет диагностическое сообщение в журнал.
     */
    fun stopTransfer() {
        txJob?.cancel()
        txJob = null
        logBus.log(message = "TX stream stopped")
    }

    /**
     * Сбрасывает последнюю ошибку в [state].
     *
     * Используется UI, чтобы убрать отображение ошибки после ознакомления.
     */
    fun clearError() {
        _state.value = _state.value.copy(lastError = null)
    }

    /**
     * Открывает GATT-сервер, создаёт сервис и характеристики, публикует сервис в системе.
     *
     * Создаваемые характеристики:
     * - CMD_RX: запись (WRITE), принимает команды от Central;
     * - CMD_TX: уведомления (NOTIFY), отправляет команды и ответы в Central (через notify);
     * - DATA_RX: запись без ответа (WRITE_NO_RESPONSE), принимает поток данных от Central;
     * - DATA_TX: уведомления (NOTIFY), отправляет поток данных в Central (через notify).
     *
     * Для TX-характеристик добавляется CCCD-дескриптор, чтобы Central мог подписаться на уведомления.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startGattServer() {
        gattServer = btManager.openGattServer(context, gattCallback)
        val server = gattServer ?: error("openGattServer() вернул null")

        val service =
            BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val cmdRx = BluetoothGattCharacteristic(
            BleUuids.CMD_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        cmdTxChar = BluetoothGattCharacteristic(
            BleUuids.CMD_TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
        }

        val dataRx = BluetoothGattCharacteristic(
            BleUuids.DATA_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )

        dataTxChar = BluetoothGattCharacteristic(
            BleUuids.DATA_TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
        }

        service.addCharacteristic(cmdRx)
        service.addCharacteristic(cmdTxChar)
        service.addCharacteristic(dataRx)
        service.addCharacteristic(dataTxChar)

        val ok = server.addService(service)
        logBus.log(message = "addService: $ok")
    }

    /**
     * Запускает BLE-advertising с UUID сервиса [BleUuids.SERVICE].
     *
     * Поведение:
     * - в основной рекламе размещается UUID сервиса (чтобы Central мог фильтровать по сервису);
     * - имя устройства отдаётся в scan response (setIncludeDeviceName(true)).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        advertiser = adapter.bluetoothLeAdvertiser
        val adv = advertiser ?: error("Advertising не поддерживается")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, data, scanResponse, advCallback)
    }

    /** Коллбек рекламы: фиксирует успешный старт или ошибку запуска рекламы. */
    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            logBus.log(message = "Advertising failed: $errorCode")
            _state.value = _state.value.copy(lastError = "Advertising failed: $errorCode")
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logBus.log(message = "Advertising started")
        }
    }

    /**
     * Коллбек GATT-сервера: управляет подключениями, подписками и обработкой входящих записей.
     *
     * Основные обязанности:
     * - отслеживать подключения Central и чистить подписки при отключении;
     * - обрабатывать запись CCCD (подписка и отписка на notify);
     * - обрабатывать записи CMD_RX и DATA_RX.
     */
    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            logBus.log(message = "Service added status=$status uuid=${service.uuid}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            logBus.log(message = "Conn state: ${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.remove(device)
                subscribedCmd.remove(device)
                subscribedData.remove(device)
            }
            publishCounters()
        }

        /**
         * Обработка записи CCCD (подписка на уведомления).
         *
         * Поведение:
         * - если Central записал ENABLE_NOTIFICATION_VALUE — добавляем устройство в набор подписчиков;
         * - если записано другое значение — считаем как отключение подписки.
         * - при необходимости отправляем ответ GATT_SUCCESS.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val charUuid = descriptor.characteristic.uuid

            when (charUuid) {
                BleUuids.CMD_TX -> if (enabled) subscribedCmd.add(device) else subscribedCmd.remove(device)
                BleUuids.DATA_TX -> if (enabled) subscribedData.add(device) else subscribedData.remove(device)
            }

            logBus.log(message = "CCCD write for $charUuid enabled=$enabled subCmd=${subscribedCmd.size} subData=${subscribedData.size}")
            publishCounters()

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        /**
         * Обработка записи в характеристики (Central -> Peripheral).
         *
         * CMD_RX:
         * - декодирует команду;
         * - на Ping отвечает Pong через уведомление CMD_TX (если Central подписан).
         *
         * DATA_RX:
         * - пишет в журнал размер;
         * - если размер совпадает с [Protocol.STREAM_BLOCK_SIZE], может отправить данные обратно через DATA_TX
         *   (только при подписке Central на DATA_TX).
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            when (characteristic.uuid) {

                BleUuids.CMD_RX -> {
                    val cmd = CommandCodec.decode(value)
                    logBus.log(message = "CMD_RX decoded=$cmd rawSize=${value.size}")

                    when (cmd) {
                        Command.Ping -> notifyCmd(device, Command.Pong)
                        null -> logBus.log(message = "CMD_RX unknown bytes size=${value.size}")
                        else -> {}
                    }
                }

                BleUuids.DATA_RX -> {
                    logBus.log(message = "DATA_RX size=${value.size}")
                    if (value.size == Protocol.STREAM_BLOCK_SIZE) {
                        notifyData(device, value)
                    } else {
                        logBus.log(message = "DATA_RX wrong size=${value.size}")
                    }
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    /* обновляем счетчики подключенных устройств */
    /**
     * Публикует счётчики подключений и подписок в [state].
     *
     * Вызывается при изменениях:
     * - подключение или отключение;
     * - подписка или отписка на notify.
     */
    private fun publishCounters() {
        _state.value = _state.value.copy(
            connectedCount = connected.size,
            subscribedCmd = subscribedCmd.size,
            subscribedData = subscribedData.size,
        )
    }

    /**
     * Отправляет команду в Central через CMD_TX (notify).
     *
     * Команды отправляются только тем устройствам, которые подписаны на CMD_TX.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyCmd(device: BluetoothDevice, cmd: Command) {
        if (!subscribedCmd.contains(device)) {
            logBus.log(message = "DROP CMD notify: not subscribed dev=${device.address} cmd=$cmd")
            return
        }
        notifyCharacteristic(device, cmdTxChar, CommandCodec.encode(cmd))
    }

    /**
     * Отправляет данные в Central через DATA_TX (notify).
     *
     * Данные отправляются только тем устройствам, которые подписаны на DATA_TX.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyData(device: BluetoothDevice, data: ByteArray) {
        if (!subscribedData.contains(device)) return
        notifyCharacteristic(device, dataTxChar, data)
    }

    /**
     * Низкоуровневая отправка уведомления по характеристике.
     *
     * API 33+:
     * - используется перегрузка notifyCharacteristicChanged(..., value).
     *
     * До API 33:
     * - значение кладётся в characteristic.value;
     * - вызывается устаревшая перегрузка notifyCharacteristicChanged(...).
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyCharacteristic(device: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val server = gattServer ?: return

        if (Build.VERSION.SDK_INT >= 33) {
            server.notifyCharacteristicChanged(device, ch, false, value)
        } else {
            ch.value = value
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, ch, false)
        }
    }
}