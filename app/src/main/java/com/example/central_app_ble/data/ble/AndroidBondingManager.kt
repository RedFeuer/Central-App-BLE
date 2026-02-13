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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class AndroidBondingManager(
    private val context: Context,
    private val bus: GattEventBus, // шина
) {
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun ensureBonded(address: String, timeoutMs: Long = 30_000) {
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        val done = CompletableDeferred<Int>()

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

                if (state == BluetoothDevice.BOND_BONDED) done.complete(state)
                if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) done.complete(state)
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        try {
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