package com.example.peripheralapp.presentation.application

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Класс приложения Peripheral.
 *
 * Назначение:
 * - включает внедрение зависимостей Hilt на уровне всего приложения;
 * - служит точкой входа для создания графа зависимостей.
 */
@HiltAndroidApp
class App: Application()