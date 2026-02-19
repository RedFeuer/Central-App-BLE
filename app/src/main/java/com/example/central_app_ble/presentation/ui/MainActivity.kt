package com.example.central_app_ble.presentation.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.central_app_ble.presentation.theme.CentralAppBLETheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Главный экран приложения.
 *
 * Назначение:
 * - Является стартовой активностью и контейнером для интерфейса на Compose.
 * - Подключает тему оформления приложения.
 * - Отображает корневой компонент интерфейса `AppRoot()`.
 *
 * - Аннотация `@AndroidEntryPoint` разрешает внедрение зависимостей в эту активность
 *   и во вложенные компоненты, которые она создаёт
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CentralAppBLETheme {
                AppRoot()
            }
        }
    }
}
