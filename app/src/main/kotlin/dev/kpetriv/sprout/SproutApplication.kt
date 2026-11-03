package dev.kpetriv.sprout

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.kpetriv.sprout.di.FirebaseModule
import dev.kpetriv.sprout.startup.AppInitializer
import javax.inject.Inject

/**
 * Checks a release build's server configuration, starts the process-start work ([AppInitializer]) and provides
 * WorkManager's configuration, so workers are created by Hilt ([HiltWorkerFactory]). The manifest removes
 * WorkManager's own androidx.startup initializer.
 */
@HiltAndroidApp
class SproutApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var appInitializer: AppInitializer

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Fail fast: a release build that cannot reach the server must not start (audit Low 5).
        FirebaseModule.requireFunctionsBaseUrl(BuildConfig.DEBUG, BuildConfig.FUNCTIONS_BASE_URL)
        appInitializer.start()
    }
}
