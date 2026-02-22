package com.example.central_app_ble.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.data.ble.callback.GattEventBus
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.shared.BleUuids
import com.example.shared.CommandCodec
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Клиент GATT со стороны центрального устройства (роль Central).
 *
 * Назначение:
 * - устанавливает соединение GATT с периферийным устройством по его адресу;
 * - выполняет начальную настройку канала обмена:
 *   1) подключение;
 *   2) получение таблицы сервисов/характеристик (discoverServices);
 *   3) привязка нужных характеристик (bind);
 *   4) запрос увеличенного MTU (чтобы поместился блок 160 байт);
 *   5) включение уведомлений по характеристикам, по которым периферия шлёт данные в Central.
 * - обеспечивает отправку команд и блоков данных в периферию.
 *
 * Важные детали:
 * - адрес устройства передаётся при создании (он известен только во время работы), поэтому используется AssistedInject;
 * - все асинхронные этапы (подключение, получение сервисов, изменение MTU, запись дескриптора) синхронизируются
 *   через promise'ы [CompletableDeferred] и завершаются в обратных вызовах [BluetoothGattCallback];
 * - при разрыве соединения публикуется событие [GattEvent.Disconnected] в [GattEventBus],
 *   чтобы репозиторий мог обновить [BleRepository.connectionState].
 */
class AndroidGattClient @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    @Assisted("address") address: String,
    private val bus: GattEventBus,
) {
    /** Устройство Bluetooth, полученное по адресу. */
    private val device: BluetoothDevice =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)


    /**
     * Текущая сессия GATT со стороны Central.
     *
     * `null` означает, что соединение не установлено или уже закрыто.
     */
    private var gatt: BluetoothGatt? = null

    /* TX - Transmit
    * RX - Receive */

    /**
     * Характеристики пользовательского протокола:
     *
     * - [cmdRx] — характеристика, куда Central записывает команды (Central -> Peripheral).
     * - [cmdTx] — характеристика, по которой Peripheral присылает команды/события в Central через уведомления notify.
     * - [dataRx] — характеристика, куда Central записывает поток данных (Central -> Peripheral).
     * - [dataTx] — характеристика, по которой Peripheral присылает поток данных в Central через уведомления notify.
     */
    private var cmdRx: BluetoothGattCharacteristic? = null
    private var cmdTx: BluetoothGattCharacteristic? = null
    private var dataRx: BluetoothGattCharacteristic? = null
    private var dataTx: BluetoothGattCharacteristic? = null

    /**
     * Флаг: выполняется сопряжение и начальная инициализация GATT-сервера.
     *
     * Потокобезопасность:
     * - помечен [Volatile], потому что читается и меняется из разных потоков:
     *   - корутина `connectAndInit()` (поток выполнения корутины),
     *   - системные обратные вызовы `BluetoothGattCallback` (другие потоки).
     */
    @Volatile private var initInProgress: Boolean = false
    /**
     * Флаг: соединение полностью готово для передачи команд и данных.
     *
     * Потокобезопасность:
     * - помечен [Volatile], потому что читается и меняется из разных потоков
     *   - корутина `connectAndInit()` (поток выполнения корутины),
     *   - системные обратные вызовы `BluetoothGattCallback` (другие потоки).
     */
    @Volatile private var ready: Boolean = false

    /**
     * Promise'ы для этапов начальной настройки.
     *
     * Заполняются в обратных вызовах [callback].
     */
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val mtuChanged = CompletableDeferred<Int>()

    /**
     * Promise записи дескриптора (CCCD) для включения уведомлений.
     */
    private var descWriteWaiter: CompletableDeferred<Pair<UUID, Int>>? = null

    /**
     * Обработчик событий GATT.
     *
     * Роль:
     * - завершает ожидатели этапов настройки (connected/servicesDiscovered/mtuChanged/descWriteWaiter);
     * - принимает уведомления от периферии и поднимает их наверх через [GattEventBus];
     * - при разрыве соединения завершает все ожидатели ошибкой и публикует событие разрыва.
     */
    private val callback = object : BluetoothGattCallback() {

        /* этот метод помогает определить, что соединение между устройствами разорвалось
        * при этом он тригерится и при базовом сопряжении + коннекте - отдельная обработка
        *
        * здесь нужно поменять состояние, чтобы оно обновилось в UI
        *
        * ЭТОТ МЕТОД ПОЯВИЛСЯ ПОСЛЕ SDK 31. не знаю как запустится на Android 10, НАДО ПРОВЕРИТЬ*/

        /**
         * Сигнал от системы: на периферийном устройстве изменилась таблица сервисов, характеристик (GATT database).
         *
         * Что это означает:
         * - кэш сервисов, характеристик на Central может стать неактуальным;
         * - после этого требуется заново выполнить `discoverServices()` и повторно привязать характеристики и уведомления.
         *
         * Зачем этот callback используется в данном проекте:
         * - на практике он помогает сбросить ложное состояние соединения Ready, когда Peripheral-сервер
         *   был остановлен и перезапущен, а Central формально ещё считает соединение установленным;
         * - в таком случае мы принудительно закрываем текущий GATT и сообщаем наверх о разрыве,
         *   чтобы UI перешёл из Ready в Disconnected, а затем в Idle и затем прошёл повторную инициализацию.
         *
         * Примечание:
         * - callback может вызываться во время первичного подключения и инициализации (в том числе при `discoverServices`);
         *   поэтому при [initInProgress] событие считается нормальным и не приводит к принудительному реконнекту.
         *
         * Совместимость:
         * - метод доступен начиная с API 31 (Android 12). На Android 10 не будет вызван.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServiceChanged(g: BluetoothGatt) {
            bus.log("Service Changed: GATT database changed on peripheral")

            /* обработка ситуации при сопряжении и инициализации - здесь все нормально */
            if (initInProgress) {
                bus.log("Service Changed during init: it's ok")
                return
            }

            /* обработка ситуации при остановке Peripheral сервера
            * при подключенном к нему Central приложении */
            if (ready) {
                clearCharacteristics() // чистим характеристики, чтобы при следующем коннекте присвоить новые
                ready = false // сбрасовыем до значения по умолчанию
                bus.log("Service Changed while the connection was already established (Ready): closing GATT and forcing reconnect ")

                /* закрываем GATT сервер на Central */
                runCatching { g.close() }
                gatt = null

                /* переводим ConnState из Ready в Disconnected -> Init */
                bus.disconnected(BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
            }
        }

        /**
         * Изменение состояния соединения GATT.
         *
         * Поведение:
         * - при успешном подключении (`GATT_SUCCESS` + `STATE_CONNECTED`) завершает ожидатель [connected];
         * - при разрыве/ошибке:
         *   - сбрасывает флаг готовности [ready];
         *   - сбрасывает характеристики через [clearCharacteristics], чтобы при переподключении не писать в старые;
         *   - завершает все ожидатели ошибкой, чтобы [connectAndInit] не висел до таймаута;
         *   - публикует событие разрыва в [bus.disconnected].
         */
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            bus.log("connState status=$status newState=$newState")

            /* успешное подключение */
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
                return
            }

            /* ошибка соединения или намеренный дисконект */
            val disconnected = newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS
            if (disconnected) {
                clearCharacteristics() // чистим характеристики, чтобы при следующем коннекте присвоить новые
                ready = false // сбрасываем в значение по умолчанию

                val exception = IllegalStateException("GATT disconnected status=$status newState=$newState")

                /* если connectAndInit() сейчас ждет - то пусть падает, а не ждет до timeoutMs */
                if (!connected.isCompleted) connected.completeExceptionally(exception)
                if (!servicesDiscovered.isCompleted) servicesDiscovered.completeExceptionally(exception)
                if (!mtuChanged.isCompleted) mtuChanged.completeExceptionally(exception)
                descWriteWaiter?.completeExceptionally(exception)
                descWriteWaiter = null

                /* сообщаем репозиторию, что потеряли связь */
                bus.disconnected(status, newState)
            }
        }

        /**
         * Завершение получения таблицы сервисов/характеристик (discoverServices).
         *
         * При успехе завершает [servicesDiscovered] (callback).
         * При неудаче ожидание завершится по таймауту в [connectAndInit].
         */
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            bus.log("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
        }

        /**
         * Завершение изменения MTU.
         *
         * При успехе завершает [mtuChanged] значением MTU (callback).
         * При неуспехе ожидание завершится по таймауту в [connectAndInit].
         */
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            bus.log("mtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuChanged.complete(mtu)
        }

        /**
         * Завершение записи дескриптора (CCCD для включения уведомлений).
         *
         * Завершает [descWriteWaiter] парой (UUID характеристики, статус записи).
         */
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            bus.log("descWrite char=${descriptor.characteristic.uuid} status=$status")
            descWriteWaiter?.complete(descriptor.characteristic.uuid to status)
            descWriteWaiter = null
        }

        /**
         * Уведомления (старый вариант обратного вызова, где значение берётся из characteristic.value).
         */
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic, characteristic.value ?: byteArrayOf())
        }

        /**
         * Уведомления (новый вариант обратного вызова, где значение приходит отдельным параметром).
         */
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(characteristic, value)
        }

        /**
         * Обработка уведомлений с Peripheral устройства.
         *
         * - по [BleUuids.CMD_TX] декодирует команду и публикует [BleNotification.Cmd];
         * - по [BleUuids.DATA_TX] публикует [BleNotification.Data].
         */
        private fun handleNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                BleUuids.CMD_TX -> {
                    val cmd = CommandCodec.decode(value)
                    bus.log("RX CMD_TX: $cmd")
                    bus.notify(BleNotification.Cmd(cmd))
                }
                BleUuids.DATA_TX -> {
                    bus.log("RX DATA_TX size=${value.size}")
                    bus.notify(BleNotification.Data(value))
                }
            }
        }
    }

    /**
     * Подключается к устройству и выполняет начальную настройку канала обмена.
     *
     * Шаги:
     * 1) проверка разрешения BLUETOOTH_CONNECT (для Android 12+);
     * 2) создание соединения GATT через [BluetoothDevice.connectGatt];
     * 3) ожидание успешного подключения ([connected]);
     * 4) запрос таблицы сервисов (discoverServices) и ожидание результата ([servicesDiscovered]);
     * 5) привязка характеристик протокола (`bind`);
     * 6) запрос MTU=247 и проверка, что фактический MTU ≥ 163 (чтобы передавать 160 байт);
     * 7) включение уведомлений по [cmdTx] и [dataTx] через запись CCCD.
     *
     * Флаги состояния:
     * - в начале устанавливает [initInProgress] = true, чтобы обратные вызовы могли отличать "идёт инициализация”
     *   от ситуации, когда соединение уже установлено;
     * - устанавливает [ready] = true только после успешного завершения всех шагов;
     * - в `finally` всегда сбрасывает [initInProgress] в false, даже если произошла ошибка или таймаут.
     *
     * Ошибки:
     * - при превышении таймаутов ожидания этапов;
     * - если обязательные элементы GATT не найдены (сервис, характеристики, CCCD);
     * - если MTU слишком мал;
     * - если запись CCCD/характеристики не стартовала или завершилась с ошибкой.
     */
    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectAndInit() {
        checkConnectPermission() // проверка permission'ов

        initInProgress = true // началось сопряжение и инициализация
        ready = false // на этом этапе пока не установлено соединение для передачи команд и данных
        try {
            /* создание GATT соединения
            * события подключения придут в callback в другом потоке (асинхронно) */
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g

            /* ждем возврата connected.complete(Unit) или истечения времени из onConnectionStateChange() */
            withTimeout(10_000) { connected.await() }

            /* ждем возврата servicesDiscovered.complete(Unit) или истечения времени из onServicesDiscovered
            * составление таблицы атрибутов на Peripheral (Gatt Server) */
            if (!g.discoverServices()) error("discoverServices() false")
            withTimeout(10_000) { servicesDiscovered.await() }

            bind() // получаем GATT характеристики Peripheral устройства

            /* ждем возврата mtuChanged.complete(mtu) или истечения времени для onMtuChanged() */
            g.requestMtu(247) // 163 байта точно влезет
            val mtu = withTimeout(10_000) { mtuChanged.await() }
            require(mtu >= 163) { "MTU=$mtu слишком мал для 160 байт" }

            /* подписка на уведомления от Peripheral*/
            enableNotify(cmdTx!!) // чтобы Central получал команды Peripheral -> Central (Pong)
            enableNotify(dataTx!!) // чтобы Cental получал данные  Peripheral -> Central

            bus.log("notify enabled CMD_TX")
            bus.log("notify enabled DATA_TX")

            ready = true // коннект установлен и готова передача команд и данных
        }
        finally {
            initInProgress = false // сбрасываем до значения по умолчанию
        }
    }

    /**
     * Закрывает соединение GATT и освобождает ресурсы.
     *
     * Поведение:
     * - очищает ссылки на характеристики через [clearCharacteristics], чтобы при следующем подключении
     *   характеристики были получены заново в `bind` и не использовались “старые” объекты;
     * - закрывает текущий [gatt] и сбрасывает ссылку на него;
     * - сбрасывает флаги [initInProgress] и [ready] в исходные значения.
     *
     * Примечание:
     * - безопасно вызывать повторно: после закрытия [gatt] становится `null`.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        /* чистим характеристики, чтобы при следующем коннекте присвоить новые */
        checkConnectPermission()

        /*  */
        clearCharacteristics()
        gatt?.close()
        gatt = null

        /* сброс в исходное состояние */
        initInProgress = false
        ready = false
    }

    /**
     * Записывает команду в характеристику команд (Central -> Peripheral).
     *
     * Предусловие:
     * - вызван [connectAndInit] и успешно выполнена [bind], иначе [cmdRx] будет отсутствовать.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCmd(value: ByteArray) {
        val characteristic = cmdRx ?: error("CMD_RX missing")
        write(characteristic, value, withResponse = true)
    }

    /* Central -> Peripheral */
    /**
     * Записывает блок потока данных в характеристику данных (Central -> Peripheral) без подтверждения.
     *
     * Предусловие:
     * - вызван [connectAndInit] и успешно выполнена [bind], иначе [dataRx] будет отсутствовать.
     *
     * Примечание:
     * - запись без подтверждения используется для потока данных, чтобы уменьшить задержки.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeDataNoResp(value: ByteArray) {
        val characteristic = dataRx ?: error("DATA_RX missing")
        write(characteristic, value, withResponse = false)
    }

    /**
     * Сбрасывает ссылки на характеристики, полученные в [bind].
     *
     * Что делает :
     * - очищает поля `cmdRx`, `cmdTx`, `dataRx`, `dataTx`, которые указывают на объекты характеристик текущей GATT-сессии.
     *
     * Зачем это нужно в данном проекте:
     * - после разрыва соединения, закрытия GATT или смены таблицы сервисов (GATT database changed)
     *   старые объекты характеристик больше нельзя считать валидными;
     * - сброс гарантирует, что при следующем подключении характеристики будут заново найдены в [bind],
     *   и мы не попопытаемся писать и включать уведомления через старые ссылки.
     */
    private fun clearCharacteristics() {
        cmdRx = null; cmdTx = null; dataRx = null; dataTx = null
    }

    /**
     * Находит пользовательский (Peripheral) сервис и необходимые характеристики протокола.
     *
     * Ошибка:
     * - если сервис или одна из характеристик отсутствуют.
     */
    private fun bind() {
        /* ищем сервис, который Central получил от Peripheral после discoverService() */
        val g = gatt ?: error("no gatt")
        val service = g.getService(BleUuids.SERVICE) ?: error("service not found")

        /* получаем GATT характеристики Peripheral устройства */
        cmdRx = service.getCharacteristic(BleUuids.CMD_RX) ?: error("CMD_RX missing")
        cmdTx = service.getCharacteristic(BleUuids.CMD_TX) ?: error("CMD_TX missing")
        dataRx = service.getCharacteristic(BleUuids.DATA_RX) ?: error("DATA_RX missing")
        dataTx = service.getCharacteristic(BleUuids.DATA_TX) ?: error("DATA_TX missing")
    }

    /**
     * Включает уведомления по характеристике.
     *
     * Делает две вещи:
     * 1) включает уведомления на стороне телефона через [BluetoothGatt.setCharacteristicNotification];
     * 2) записывает дескриптор CCCD (Client Characteristic Configuration Descriptor),
     *    чтобы периферийное устройство начало присылать уведомления.
     *
     * Синхронизация:
     * - ожидает завершение записи CCCD через [onDescriptorWrite] с таймаутом.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableNotify(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: error("no gatt")

        require(g.setCharacteristicNotification(characteristic, true)) {
            "setCharacteristicNotification failed uuid=${characteristic.uuid}" // lazyMessage из документации
        }

        val cccd = characteristic.getDescriptor(BleUuids.CCCD) ?: error("CCCD missing uuid=${characteristic.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        val waiter = CompletableDeferred<Pair<UUID, Int>>()
        descWriteWaiter = waiter

        val started: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }

        require(started) { "writeDescriptor start failed uuid=${characteristic.uuid}" }

        /* ждем возврата descWriteWaiter?.complete или истчения времени для onDescriptorWrite() */
        val (uuid, status) = withTimeout(10_000) { waiter.await() }
        require(uuid == characteristic.uuid) { "Descriptor write mismatch: expected=${characteristic.uuid} got=$uuid" }
        require(status == BluetoothGatt.GATT_SUCCESS) { "Descriptor write status=$status uuid=$uuid" }
    }

    /* используется writeCmd и write(Central -> Peripheral) */
    /**
     * Низкоуровневая запись в характеристику (Central -> Peripheral).
     *
     * Параметры:
     * - [withResponse] определяет тип записи:
     *   - `true` — запись с подтверждением (надежнее, но медленнее);
     *   - `false` — запись без подтверждения (быстрее для потока).
     *
     * Примечание по версиям Android:
     * - на Android 13+ используется вариант API, где значение передаётся параметром;
     * - на более старых версиях значение записывается в поле characteristic.value.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean) {
        val g = gatt ?: error("no gatt")

        characteristic.writeType = if (withResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        val started: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, value, characteristic.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        require(started) { "writeCharacteristic start failed uuid=${characteristic.uuid}" }
    }

    /**
     * Проверяет наличие разрешения BLUETOOTH_CONNECT для Android 12+.
     *
     * Ошибка:
     * - если разрешение не выдано — выбрасывает исключение.
     */
    private fun checkConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        val ok = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        require(ok) { "BLUETOOTH_CONNECT not granted" }
    }
}