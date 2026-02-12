package com.example.peripheralapp.presentation.ui

import android.Manifest
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.data.ble.BlePeripheralServer

class MainActivity : ComponentActivity() {

    private lateinit var server: BlePeripheralServer

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply { textSize = 16f }
        setContentView(tv)

        server = BlePeripheralServer(this)

        if (!server.isPeripheralSupported()) {
            tv.text = "Peripheral/Advertising НЕ поддерживается на этом устройстве"
            return
        }

        server.start()
        tv.text = "Peripheral запущен: advertising + GATT server"
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}