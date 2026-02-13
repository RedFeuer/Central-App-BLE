package com.example.peripheralapp.presentation.ui

import android.Manifest
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import com.example.peripheralapp.data.ble.BlePeripheralServer

class MainActivity : ComponentActivity() {
    private lateinit var tv: TextView
    private lateinit var server: BlePeripheralServer

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tv = TextView(this).apply { textSize = 14f }
        setContentView(ScrollView(this).apply { addView(tv) })

        server = BlePeripheralServer(this) { s -> log(s) }  // <-- добавим logger

        if (!server.isPeripheralSupported()) {
            log("Peripheral/Advertising НЕ поддерживается")
            return
        }

        server.start()
        log("Peripheral запущен: advertising + GATT server")
    }

    private fun log(s: String) {
        android.util.Log.i("PeripheralUI", s)
        tv.post { tv.append("$s\n") }
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}