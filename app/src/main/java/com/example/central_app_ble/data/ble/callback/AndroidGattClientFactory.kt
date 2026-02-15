package com.example.central_app_ble.data.ble.callback

import com.example.central_app_ble.data.ble.AndroidGattClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory

/* обычно при DI передаем объекты, которые известны в compile-time
* в данном случае строка становится известной только в run-time,
* поэтому нужна фабрика для инъекции в конструктор через DI */
@AssistedFactory
interface AndroidGattClientFactory {
    fun create(@Assisted("address") address: String): AndroidGattClient
}