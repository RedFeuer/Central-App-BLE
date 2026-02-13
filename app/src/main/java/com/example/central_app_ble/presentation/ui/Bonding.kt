package com.example.central_app_ble.presentation.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class Bonding(private val context: Context) {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = 30_000) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val done = CompletableDeferred<Int>() // финальный bondState

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                val d: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                if (d?.address != device.address) return

                val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                /* успех */
                if (state == BluetoothDevice.BOND_BONDED) done.complete(state)

                /* провал или отмена (переход BONDING -> NONE) */
                if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) done.complete(state)
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        try {
            /* повторный вызов createBond во время BOND_BONDING ломает процесс */
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val started = device.createBond()
                if (!started) throw IllegalStateException("createBond() returned false")
            }

            val finalState = withTimeout(timeoutMs) { done.await() }
            if (finalState != BluetoothDevice.BOND_BONDED) {
                throw IllegalStateException("Bond failed / cancelled")
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
