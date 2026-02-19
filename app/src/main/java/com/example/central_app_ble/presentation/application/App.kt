package com.example.central_app_ble.presentation.application

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Главный класс приложения.
 *
 * Назначение:
 * - Запускает инициализацию внедрения зависимостей на уровне всего приложения.
 *
 */

@HiltAndroidApp
class App : Application()