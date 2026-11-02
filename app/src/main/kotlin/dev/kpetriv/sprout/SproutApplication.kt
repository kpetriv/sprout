package dev.kpetriv.sprout

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.kpetriv.sprout.startup.AppInitializer
import javax.inject.Inject

@HiltAndroidApp
class SproutApplication : Application() {
    @Inject
    lateinit var appInitializer: AppInitializer

    override fun onCreate() {
        super.onCreate()
        appInitializer.start()
    }
}
