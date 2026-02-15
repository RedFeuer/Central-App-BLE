package com.example.central_app_ble.di

import android.content.Context
import com.example.central_app_ble.data.repositoryImpl.BleRepositoryImpl
import com.example.central_app_ble.domain.repository.BleRepository
import com.example.central_app_ble.domain.useCase.CentralStreamUseCase
import com.example.central_app_ble.domain.useCase.ConnectUseCase
import com.example.central_app_ble.domain.useCase.ObserveLogsUseCase
import com.example.central_app_ble.domain.useCase.ObserveNotificationsUseCase
import com.example.central_app_ble.domain.useCase.PeripheralTxControlUseCase
import com.example.central_app_ble.domain.useCase.PingUseCase
import com.example.central_app_ble.domain.useCase.ScanUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {
    @Binds
    @Singleton
    abstract fun bindBleRepository(impl: BleRepositoryImpl) : BleRepository

    companion object {
//        @Provides
//        @Singleton
//        fun provideGattEventBus() :

        @Provides
        @Singleton
        fun provideScanUseCase(repository: BleRepository) : ScanUseCase {
            return ScanUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideConnectUseCase(repository: BleRepository) : ConnectUseCase {
            return ConnectUseCase(repository)
        }

        @Provides
        @Singleton
        fun providePingUseCase(repository: BleRepository) : PingUseCase {
            return PingUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideCentralStreamUseCase(repository: BleRepository) : CentralStreamUseCase {
            return CentralStreamUseCase(repository)
        }

        @Provides
        @Singleton
        fun providePeripheralTxControlUseCase(repository: BleRepository) : PeripheralTxControlUseCase {
            return PeripheralTxControlUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideObserceNotificationsUseCase(repository: BleRepository) : ObserveNotificationsUseCase {
            return ObserveNotificationsUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideObserveLogsUseCase(repository: BleRepository) : ObserveLogsUseCase {
            return ObserveLogsUseCase(repository)
        }
    }
}