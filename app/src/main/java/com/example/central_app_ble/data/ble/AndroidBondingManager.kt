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

/**
 * Менеджер сопряжения (создания пары) для BLE-устройства.
 *
 * Назначение:
 * - гарантирует, что для устройства с указанным адресом создана доверенная пара (BOND_BONDED);
 * - если пара уже создана — сразу завершает работу;
 * - если пары нет — запускает создание пары и ждёт результата через системные широковещательные события.
 *
 * Как работает:
 * 1) получает [BluetoothDevice] по адресу через [BluetoothAdapter.getRemoteDevice];
 * 2) если устройство уже в состоянии [BluetoothDevice.BOND_BONDED] — выходит;
 * 3) регистрирует [BroadcastReceiver] на [BluetoothDevice.ACTION_BOND_STATE_CHANGED];
 * 4) при необходимости запускает [BluetoothDevice.createBond];
 * 5) ждёт финальное состояние с таймаутом:
 *    - успех: состояние стало [BluetoothDevice.BOND_BONDED];
 *    - провал/отмена: состояние стало [BluetoothDevice.BOND_NONE] после [BluetoothDevice.BOND_BONDING].
 * 6) в любом случае снимает [BroadcastReceiver].
 *
 * Почему используется ожидание через BroadcastReceiver:
 * - создание пары асинхронное и завершается событием системы, а не немедленным результатом функции.
 *
 * Ошибки:
 * - если [BluetoothDevice.createBond] вернул `false`;
 * - если по истечении [timeoutMs] не получен финальный результат;
 * - если создание пары завершилось неуспешно или было отменено.
 */
class AndroidBondingManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    /**
     * Гарантирует, что устройство с данным адресом сопряжено (создана пара).
     *
     * Параметры:
     * - [address] — адрес Bluetooth-устройства, с которым нужно создать пару.
     * - [timeoutMs] — максимальное время ожидания результата создания пары.
     *
     * Предусловия:
     * - требуется системное разрешение [Manifest.permission.BLUETOOTH_CONNECT].
     *
     * Примечание:
     * - метод приостанавливает корутину до результата сопряжения или до таймаута.
     */
    @SuppressLint("SupportAnnotationUsage")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun ensureBonded(address: String, timeoutMs: Long = 30_000) {
        val device = adapter.getRemoteDevice(address)
        /* если Pheripheral уже подключено, то все связь установлена - выходим */
        if (device.bondState == BluetoothDevice.BOND_BONDED) return

        /* ожидаемый результат сопряжение (promise) - заполняется через BroadcastReceiver через complete */
        val done = CompletableDeferred<Int>()

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