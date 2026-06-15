package com.msp1974.vacompanion.di

import android.content.Context
import com.msp1974.vacompanion.data.NetworkStatusManager
import com.msp1974.vacompanion.device.DeviceInfo
import com.msp1974.vacompanion.settings.APPConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAPPConfig(@ApplicationContext context: Context): APPConfig {
        return APPConfig(context)
    }

    @Provides
    @Singleton
    fun provideDevice(@ApplicationContext context: Context): DeviceInfo {
        return DeviceInfo(context)
    }

    @Provides
    @Singleton
    fun provideNetworkStatusManager(@ApplicationContext context: Context): NetworkStatusManager {
        return NetworkStatusManager(context)
    }
}
