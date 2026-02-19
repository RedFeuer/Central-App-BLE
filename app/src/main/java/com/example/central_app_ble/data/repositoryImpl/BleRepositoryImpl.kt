package com.example.central_app_ble.data.repositoryImpl

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.data.ble.AndroidBleScanner
import com.example.central_app_ble.data.ble.AndroidBondingManager
import com.example.central_app_ble.data.ble.AndroidGattClient
import com.example.central_app_ble.data.ble.callback.AndroidGattClientFactory
import com.example.central_app_ble.data.ble.callback.GattEvent
import com.example.central_app_ble.data.ble.callback.GattEventBus
import com.example.central_app_ble.data.mapper.BluetoothDeviceMapper
import com.example.central_app_ble.di.AppScope
import com.example.central_app_ble.domain.domainModel.BleDevice
import com.example.central_app_ble.domain.domainModel.BleNotification
import com.example.central_app_ble.domain.domainModel.ConnectionState
import com.example.central_app_ble.domain.repository.BleRepository
import com.example.shared.Command
import com.example.shared.CommandCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Внутренние действия репозитория, из которых формируется поток состояния соединения.
 *
 * Назначение:
 * - отделяет команды на изменение состояния от внешних событий Bluetooth;
 * - используется как промежуточный тип для построения [BleRepository.connectionState]
 *   через объединение потоков и свёртку (scan).
 *
 * Варианты:
 * - [SetState] — явная установка состояния со стороны репозитория.
 * - [RemoteDisconnected] — разрыв соединения, пришедший как событие от системы Bluetooth
 *   (с кодом [status] и новым состоянием [newState]).
 *
 * Примечание:
 * - тип объявлен как `private`, потому что используется только внутри реализации репозитория
 *   и не является частью внешнего контракта.
 */
private sealed interface RepoAction {
    /**
     * Явная установка состояния соединения со стороны репозитория.
     *
     * @param state новое состояние, которое должно стать текущим
     */
    data class SetState(val state: ConnectionState) : RepoAction
    /**
     * Событие удалённый сервер разорвал соединение.
     *
     * @param status код результата отключения из системного обратного вызова
     * @param newState новое состояние соединения из системного обратного вызова
     */
    data class RemoteDisconnected(val status: Int, val newState: Int) : RepoAction
}

/**
 * Слой доступа к BLE для центрального устройства.
 *
 * Назначение:
 * - предоставляет операции сканирования, подключения и отключения;
 * - обеспечивает отправку команд и передачу данных в периферийное устройство;
 * - отдаёт наружу потоки состояния соединения, журнала и входящих уведомлений.
 */
class BleRepositoryImpl @Inject constructor (
    @ApplicationContext private val appContext: Context,
    private val bus: GattEventBus, /* шина Gatt событий*/
    private val scanner: AndroidBleScanner,
    private val bonding: AndroidBondingManager,
    private val gattFactory: AndroidGattClientFactory, // фабрика для создания gattClient
    @AppScope private val appScope: CoroutineScope,
) : BleRepository {
    /**
     * Поток строк журнала работы BLE-части.
     *
     * Откуда берётся:
     * - [bus.events] — единая шина событий, куда BLE-слой публикует разные события;
     * - здесь выбираются только события типа [GattEvent.Log] и преобразуются в строки.
     *
     * Зачем нужен:
     * - интерфейс подписывается на этот поток и отображает журнал на экране;
     * - удобно для отладки: сканирование, сопряжение, подключение, запись характеристик,
     *   ответы устройства, ошибки и т.п. попадают в одно место.
     *
     * Особенность:
     * - это поток событий, а не текущее состояние: новые строки приходят по мере возникновения.
     */
    override val logs: Flow<String> =
        bus.events.filterIsInstance<GattEvent.Log>().map { it.line }

    /**
     * Поток уведомлений, приходящих от периферийного устройства.
     *
     * Откуда берётся:
     * - BLE-слой (GATT-клиент) получает уведомления по характеристике и публикует событие [GattEvent.Notify] в [bus];
     * - здесь выбираются только эти события и извлекаются данные [BleNotification].
     *
     * Зачем нужен:
     * - отделяет входящие данные устройства от журнала:
     *   журнал — для человека, уведомления — для логики приложения;
     * - верхний слой (viewModel) подписывается и реагирует на входящие пакеты.
     *
     * Примечание:
     * - поток холодный в том смысле, что он начинает потребляться при подписке,
     *   но сами события поставляются из [bus] по мере их появления.
     */
    override val notifications: Flow<BleNotification> =
        bus.events.filterIsInstance<GattEvent.Notify>().map { it.notification }

    /**
     * Внутренний поток действий репозитория, из которых формируется [connectionState].
     *
     * Идея:
     * - репозиторий не пишет состояние в переменную, а публикует действия,
     *   которые затем сворачиваются в итоговое состояние через [scan].
     *
     * Что сюда попадает:
     * - команды вида “установить новое состояние” (Scanning/Bonding/Connecting/Ready/Error/Idle).
     *
     * Зачем так сделано:
     * - единый механизм построения состояния соединения:
     *   все изменения состояния проходят через один поток и одну свёртку;
     * - проще отлаживать и расширять: добавили новое действие - оно попало в общий конвейер.
     *
     * Почему есть буфер:
     * - [extraBufferCapacity] позволяет не блокировать отправителя, если события приходят целой пачкой
     *   (например, несколько переходов состояния подряд).
     */
    private val actions = MutableSharedFlow<RepoAction>(extraBufferCapacity = 64)

    /* поток дисконектов */
    /**
     * Поток разрывов соединения, пришедших от Bluetooth (через шину событий [bus]).
     *
     * Что это:
     * - системный обратный вызов о разрыве соединения конвертируется BLE-слоем в событие [GattEvent.Disconnected],
     *   затем это событие попадает в [bus.events].
     *
     * Зачем выделять в отдельный поток:
     * - разрыв соединения — событие снаружи (его инициирует сервер/устройство), а не сам репозиторий;
     * - важно обрабатывать его независимо от того, кто и когда менял состояние через [actions].
     *
     * Почему сразу закрываем GATT:
     * - чтобы гарантированно освободить ресурсы и не оставить рабочий клиент;
     * - чтобы дальнейшие команды и данные не уходили в уже мёртвое соединение.
     *
     * Что дальше:
     * - событие преобразуется в [RepoAction.RemoteDisconnected], чтобы общая логика построения состояния
     *   (через merge + scan) работала одинаково для внутренних действий и внешних событий.
     */
    private val remoteDisconnectedActions: Flow<RepoAction> =
        bus.events
            .filterIsInstance<GattEvent.Disconnected>() // фильтруем события
            .onEach { closeGatt() } // закрываем GATT сразу при разрыве соединения
            .map{ RepoAction.RemoteDisconnected(it.status, it.newState) } // маппим событие GattEvent.Disconnected -> RepoAction

    /* склеиваем два потока в один */
    /**
     * Поток состояния соединения центрального устройства с периферийным.
     *
     * Как формируется:
     * - объединяет два источника:
     *   1) [actions] — внутренние переходы состояния (сканирование, сопряжение, подключение, готовность и т.д.);
     *   2) [remoteDisconnectedActions] — внешние разрывы соединения от Bluetooth.
     * - затем [scan] сворачивает последовательность действий в текущее [ConnectionState].
     *
     * Почему это [StateFlow]:
     * - UI и другие подписчики всегда получают последнее актуальное состояние сразу при подписке;
     * - начальное значение — [ConnectionState.Idle].
     *
     * Почему [SharingStarted.Eagerly]:
     * - поток начинает работать сразу, а не при первой подписке
     */
    override val connectionState: StateFlow<ConnectionState> =
        merge(actions, remoteDisconnectedActions)
            .scan<RepoAction, ConnectionState>(ConnectionState.Idle) { _,action ->
                when (action) {
                    is RepoAction.SetState -> action.state // ставим новое состояние
                    is RepoAction.RemoteDisconnected -> ConnectionState.Disconnected(action.status, action.newState) // ставим состояние дисконнекта
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, ConnectionState.Idle)


    /* Central-устройство (Клиент) */
    /**
     * Текущий GATT-клиент центрального устройства.
     *
     * `null` означает, что соединение не установлено или уже закрыто.
     */
    private var gattClient: AndroidGattClient? = null

    /**
     * Закрывает текущий GATT-клиент и сбрасывает ссылку.
     *
     * Метод безопасен при повторном вызове.
     */
    private fun closeGatt() {
        runCatching { gattClient?.close() }
        gattClient = null
    }

    /* закрываем старый GATT и меняем состояние на ожидание */
    /**
     * Отключается от устройства и освобождает ресурсы Bluetooth.
     *
     * Поведение:
     * - закрывает активное GATT-соединение (если оно есть);
     * - переводит [connectionState] в [ConnectionState.Idle].
     */
    override fun disconnect() {
        closeGatt()
        setState(ConnectionState.Idle)
    }

    /**
     * Выполняет сканирование и возвращает первое найденное устройство.
     *
     * Параметры:
     * - [timeoutMs] — максимальное время сканирования в миллисекундах.
     *
     * Результат:
     * - [BleDevice] — первое найденное устройство;
     * - `null` — если за [timeoutMs] ничего не найдено.
     *
     * Примечание:
     * - реализация переводит [connectionState] в [ConnectionState.Scanning] на время поиска.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override suspend fun scanFirst(timeoutMs: Long): BleDevice? {
        setState(ConnectionState.Scanning) // сканируем
        bus.log("scan start")

        val res = scanner.scanFirst(timeoutMs) // вызов сканнера
        val dev = if (res != null) {
            val domainModel: BleDevice = BluetoothDeviceMapper.toDomain(res.device, res.rssi)
            bus.log("FOUND: name=${domainModel.name} addr=${domainModel.address} rssi=${domainModel.rssi}")
            domainModel
        } else null

        bus.log("scan stop; selected=${dev?.address ?: "none"}")
        setState(ConnectionState.Idle) // ожидание действий
        return dev
    }

    /**
     * Подключается к указанному устройству и подготавливает соединение к обмену данными.
     *
     * Параметры:
     * - [device] — устройство, к которому нужно подключиться.
     *
     * Поведение:
     * - может включать сопряжение (создание пары), если это требуется;
     * - после успешного подключения переводит [connectionState] в [ConnectionState.Ready].
     *
     * Ошибки:
     * - при неудаче может выбросить исключение.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun connect(device: BleDevice) {
        disconnect() // закрываем GATT и меняем состояние на ожидание

        /* связывание */
        setState(ConnectionState.Bonding)
        bonding.ensureBonded(device.address)
        bus.log("BONDED OK")

        setState(ConnectionState.Connecting)

        /* создаем клиента через фабрику */
        val client = gattFactory.create(device.address)
        gattClient = client

        try {
            client.connectAndInit()
            setState(ConnectionState.Ready)
        } catch (e: Exception) {
            setState(ConnectionState.Error(e.message ?: "connect/init error"))
            disconnect()
            throw e
        }
    }

    /**
     * Отправляет команду в подключённое устройство.
     *
     * Параметры:
     * - [cmd] — команда протокола.
     *
     * Предусловие:
     * - соединение должно быть установлено и готово к обмену ([ConnectionState.Ready]).
     *
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun sendCmd(cmd: Command) {
        val client = gattClient ?: error("not connected")
        client.writeCmd(CommandCodec.encode(cmd))
    }

    /* Central -> Peripheral */
    /**
     * Отправляет блок данных от центрального устройства к периферийному.
     *
     * Параметры:
     * - [bytes] — отправляемые данные.
     *
     * Предусловие:
     * - соединение должно быть установлено и готово к обмену ([ConnectionState.Ready]).
     *
     * Примечание:
     * - метод предназначен для трансфера потока данных.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override suspend fun writeCentralData(bytes: ByteArray) {
        val client = gattClient ?: error("not connected")
        client.writeDataNoResp(bytes)
    }

    /**
     * Публикует новое состояние соединения во внутренний поток действий [actions].
     *
     * Используется как единый способ изменения [connectionState] внутри репозитория.
     */
    private fun setState(state: ConnectionState) {
        actions.tryEmit(RepoAction.SetState(state))
    }
}