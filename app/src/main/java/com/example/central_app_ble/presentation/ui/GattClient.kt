package com.example.centralapp

import android.Manifest
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
import com.example.shared.BleUuids
import com.example.shared.CommandCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

class SimpleGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit
) {
    private var gatt: BluetoothGatt? = null

    private var cmdRx: BluetoothGattCharacteristic? = null
    private var cmdTx: BluetoothGattCharacteristic? = null
    private var dataRx: BluetoothGattCharacteristic? = null
    private var dataTx: BluetoothGattCharacteristic? = null

    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val mtuChanged = CompletableDeferred<Int>()
    private val descWritten = CompletableDeferred<UUID>()

    private var descWriteWaiter: CompletableDeferred<Pair<UUID, Int>>? = null

    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("connState status=$status newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("mtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) mtuChanged.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("descWrite char=${descriptor.characteristic.uuid} status=$status")
            descWriteWaiter?.complete(descriptor.characteristic.uuid to status)
            descWriteWaiter = null
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            when (ch.uuid) {
                BleUuids.CMD_TX -> log("NOTIFY CMD_TX: ${CommandCodec.decode(v)} raw=${v.joinToString()}")
                BleUuids.DATA_TX -> log("NOTIFY DATA_TX size=${v.size}")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            when (ch.uuid) {
                BleUuids.CMD_TX -> log("NOTIFY CMD_TX: ${CommandCodec.decode(value)}")
                BleUuids.DATA_TX -> log("NOTIFY DATA_TX size=${value.size}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectAndInit() {
        checkConnectPermission()

        val g = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        else
            @Suppress("DEPRECATION") device.connectGatt(context, false, cb)

        gatt = g

        withTimeout(10_000) { connected.await() }

        // discover
        if (!g.discoverServices()) error("discoverServices() false")
        withTimeout(10_000) { servicesDiscovered.await() }

        // bind chars
        bind()

        // mtu
        if (Build.VERSION.SDK_INT >= 21) {
            g.requestMtu(247)
            val mtu = withTimeout(10_000) { mtuChanged.await() }
            require(mtu >= 163) { "MTU=$mtu слишком мал для 160 байт" }
        }

        // enable notify on CMD_TX + DATA_TX
        enableNotify(cmdTx!!)
        enableNotify(dataTx!!)
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
        val ok = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        require(ok) { "BLUETOOTH_CONNECT not granted" }
    }
}
