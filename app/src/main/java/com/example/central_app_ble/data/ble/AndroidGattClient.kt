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

class AndroidGattClient @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    @Assisted("address") address: String,
    private val bus: GattEventBus,
) {
    private val device: BluetoothDevice =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

    /* клиентская сессия со стороны Central
    * сообщение на уровне Central (клиент) <-> Peripheral (сервер)*/
    private var gatt: BluetoothGatt? = null

    /* TX - Transmit
    * RX - Receive */

    /* куда Central пишет команды */
    private var cmdRx: BluetoothGattCharacteristic? = null
    /* откуда Peripheral шлет события через notify */
    private var cmdTx: BluetoothGattCharacteristic? = null
    /* куда Central пишет потоко данных */
    private var dataRx: BluetoothGattCharacteristic? = null
    /* откуда Peripheral шлет поток данных через notify */
    private var dataTx: BluetoothGattCharacteristic? = null

    /* promise'ы */
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val mtuChanged = CompletableDeferred<Int>()
    private var descWriteWaiter: CompletableDeferred<Pair<UUID, Int>>? = null

    private val callback = object : BluetoothGattCallback() {

        /* callback для connected */
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            bus.log("connState status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            }
        }

        /* callback для servicesDiscovered */
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            bus.log("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
        }

        /* callback для mtuChanged */
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            bus.log("mtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuChanged.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            bus.log("descWrite char=${descriptor.characteristic.uuid} status=$status")
            descWriteWaiter?.complete(descriptor.characteristic.uuid to status)
            descWriteWaiter = null
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(characteristic, value)
        }

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

    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectAndInit() {
        checkConnectPermission() // проверка permission'ов

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
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        checkConnectPermission()
        gatt?.close()
        gatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeCmd(value: ByteArray) {
        val characteristic = cmdRx ?: error("CMD_RX missing")
        write(characteristic, value, withResponse = true)
    }

    /* Central -> Peripheral */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeDataNoResp(value: ByteArray) {
        val characteristic = dataRx ?: error("DATA_RX missing")
        write(characteristic, value, withResponse = false)
    }

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

    private fun checkConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        val ok = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        require(ok) { "BLUETOOTH_CONNECT not granted" }
    }
}