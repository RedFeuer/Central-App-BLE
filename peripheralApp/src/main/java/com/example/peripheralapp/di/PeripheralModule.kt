package com.example.peripheralapp.di

import com.example.peripheralapp.data.repositoryImpl.PeripheralRepositoryImpl
import com.example.peripheralapp.domain.repository.PeripheralRepository
import com.example.peripheralapp.domain.useCase.ObservePeripheralLogsUseCase
import com.example.peripheralapp.domain.useCase.ObservePeripheralStateUseCase
import com.example.peripheralapp.domain.useCase.StartPeripheralUseCase
import com.example.peripheralapp.domain.useCase.StopPeripheralUseCase
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
        fun provideStartPeripheralUseCase(repository: PeripheralRepository) : StartPeripheralUseCase {
            return StartPeripheralUseCase(repository)
        }

        @Provides
        @Singleton
        fun provideStopPeripheralUseCase(repository: PeripheralRepository) : StopPeripheralUseCase {
            return StopPeripheralUseCase(repository)
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