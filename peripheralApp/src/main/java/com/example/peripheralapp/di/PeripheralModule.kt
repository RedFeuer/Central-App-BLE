package com.example.peripheralapp.di

import com.example.peripheralapp.data.repositoryImpl.PeripheralRepositoryImpl
import com.example.peripheralapp.domain.repository.PeripheralRepository
import com.example.peripheralapp.domain.useCase.ObservePeripheralLogsUseCase
import com.example.peripheralapp.domain.useCase.ObservePeripheralStateUseCase
import com.example.peripheralapp.domain.useCase.StartServerPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StartTransferPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopServerPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopTransferPeripheralUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PeripheralModule {
    @Binds
    @Singleton
    abstract fun bindPeripheralRepository(impl: PeripheralRepositoryImpl) : PeripheralRepository

    companion object {
        @Provides
        @Singleton
        fun provideStartServerPeripheralUseCase(repository: PeripheralRepository) : StartServerPeripheralUseCase {
            return StartServerPeripheralUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideStopServerPeripheralUseCase(repository: PeripheralRepository) : StopServerPeripheralUseCase {
            return StopServerPeripheralUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideStartTransferPeripheralUseCase(repository: PeripheralRepository) : StartTransferPeripheralUseCase {
            return StartTransferPeripheralUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideStopTransferPeripheralUseCase(repository: PeripheralRepository) : StopTransferPeripheralUseCase {
            return StopTransferPeripheralUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideObservePeripheralStateUseCase(repository: PeripheralRepository) : ObservePeripheralStateUseCase {
            return ObservePeripheralStateUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideObservePeripheralLogsUseCase(repository: PeripheralRepository): ObservePeripheralLogsUseCase {
            return ObservePeripheralLogsUseCase(repository)
        }
    }

}