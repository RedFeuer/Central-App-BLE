package com.example.central_app_ble.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresPermission
import com.example.central_app_ble.data.ble.callback.GattEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

class AndroidBondingManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun ensureBonded(address: String, timeoutMs: Long = 30_000) {
        val device = adapter.getRemoteDevice(address)
        /* если Pheripheral уже подключено, то все связь установлена - выходим */
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val done = CompletableDeferred<Int>() // promise, заполняем через complete

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                /* ждем тоолько intent ACTION_BOND_STATE_CHANGED */
                if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                /* устройство, по которому пришло событие */
                val d: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                if (d?.address != device.address) return // нужно только то устройство, с которым связываемся

                val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                /* связь */
                if (state == BluetoothDevice.BOND_BONDED) done.complete(state)
                /* провал или отмена связи */
                if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) done.complete(state)
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                /* связывание */
                val started = device.createBond()
                if (!started) throw IllegalStateException("createBond() returned false")
            }

            /* ожидание результата связывания по done.complete() в течении timeoutMs */
            val finalState = withTimeout(timeoutMs) { done.await() }
            if (finalState != BluetoothDevice.BOND_BONDED) {
                throw IllegalStateException("Bond failed / cancelled")
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) } // снимаем receiver
        }
    }
}