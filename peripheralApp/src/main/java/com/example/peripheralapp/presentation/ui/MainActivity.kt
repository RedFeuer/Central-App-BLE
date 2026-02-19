package com.example.peripheralapp.presentation.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint

/**
 * Главная Activity приложения Peripheral.
 *
 * Назначение:
 * - создаёт корневой Compose-интерфейс;
 * - подключает обработку системных отступов через [enableEdgeToEdge];
 * - предоставляет точку входа для внедрения зависимостей Hilt в UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}