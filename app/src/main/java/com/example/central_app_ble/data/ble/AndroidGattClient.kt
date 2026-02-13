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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

class AndroidGattClient(
    private val context: Context,
    address: String,
    private val bus: GattEventBus,
) {
    private val device: BluetoothDevice =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

    private var gatt: BluetoothGatt? = null

    private var cmdRx: BluetoothGattCharacteristic? = null
    private var cmdTx: BluetoothGattCharacteristic? = null
    private var dataRx: BluetoothGattCharacteristic? = null
    private var dataTx: BluetoothGattCharacteristic? = null

    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val mtuChanged = CompletableDeferred<Int>()
    private var descWriteWaiter: CompletableDeferred<Pair<UUID, Int>>? = null

    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            bus.log("connState status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            bus.log("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            bus.log("mtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuChanged.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            bus.log("descWrite char=${descriptor.characteristic.uuid} status=$status")
            descWriteWaiter?.complete(descriptor.characteristic.uuid to status)
            descWriteWaiter = null
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(ch, ch.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(ch, value)
        }

        private fun handleNotify(ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
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
        checkConnectPermission()

        val g = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        else
            @Suppress("DEPRECATION") device.connectGatt(context, false, cb)

        gatt = g

        withTimeout(10_000) { connected.await() }

        if (!g.discoverServices()) error("discoverServices() false")
        withTimeout(10_000) { servicesDiscovered.await() }

        bind()

        if (Build.VERSION.SDK_INT >= 21) {
            g.requestMtu(247)
            val mtu = withTimeout(10_000) { mtuChanged.await() }
            require(mtu >= 163) { "MTU=$mtu слишком мал для 160 байт" }
        }

        enableNotify(cmdTx!!)
        enableNotify(dataTx!!)

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
        val ch = cmdRx ?: error("CMD_RX missing")
        write(ch, value, withResponse = true)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun writeDataNoResp(value: ByteArray) {
        val ch = dataRx ?: error("DATA_RX missing")
        write(ch, value, withResponse = false)
    }

    private fun bind() {
        val g = gatt ?: error("no gatt")
        val svc = g.getService(BleUuids.SERVICE) ?: error("service not found")

        cmdRx = svc.getCharacteristic(BleUuids.CMD_RX) ?: error("CMD_RX missing")
        cmdTx = svc.getCharacteristic(BleUuids.CMD_TX) ?: error("CMD_TX missing")
        dataRx = svc.getCharacteristic(BleUuids.DATA_RX) ?: error("DATA_RX missing")
        dataTx = svc.getCharacteristic(BleUuids.DATA_TX) ?: error("DATA_TX missing")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun enableNotify(ch: BluetoothGattCharacteristic) {
        val g = gatt ?: error("no gatt")

        require(g.setCharacteristicNotification(ch, true)) {
            "setCharacteristicNotification failed uuid=${ch.uuid}"
        }

        val cccd = ch.getDescriptor(BleUuids.CCCD) ?: error("CCCD missing uuid=${ch.uuid}")
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

        require(started) { "writeDescriptor start failed uuid=${ch.uuid}" }

        val (uuid, status) = withTimeout(10_000) { waiter.await() }
        require(uuid == ch.uuid) { "Descriptor write mismatch: expected=${ch.uuid} got=$uuid" }
        require(status == BluetoothGatt.GATT_SUCCESS) { "Descriptor write status=$status uuid=$uuid" }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun write(ch: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean) {
        val g = gatt ?: error("no gatt")

        ch.writeType = if (withResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        val started: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, ch.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        require(started) { "writeCharacteristic start failed uuid=${ch.uuid}" }
    }

    private fun checkConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        val ok = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        require(ok) { "BLUETOOTH_CONNECT not granted" }
    }
}