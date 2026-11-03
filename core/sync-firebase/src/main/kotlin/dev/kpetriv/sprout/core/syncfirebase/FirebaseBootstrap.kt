package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initialises the default [FirebaseApp] once per process (M3 §5 "Bootstrap").
 *
 * With `google-services.json` the app is already initialised by Firebase's init provider and is used as is. Without
 * it (no real project yet, D-M3-1) the app is initialised programmatically for the `demo-sprout` project, which the
 * Emulator Suite accepts without credentials. In **debug** builds, when [FirebaseConfig.emulatorHost] is set, Auth and
 * Firestore are pointed at the emulators, and Functions too unless [FirebaseConfig.functionsBaseUrl] is set (D-M3-29:
 * the callables then go to that HTTP service); release builds never do ([EmulatorSupport]). Firestore keeps a memory
 * cache only: Room is the source of truth on the device, so nothing synced is persisted twice.
 */
@Singleton
class FirebaseBootstrap internal constructor(
    private val config: FirebaseConfig,
    private val emulators: EmulatorSupport,
) {
    @Inject
    constructor(config: FirebaseConfig) : this(config, Emulators)

    @Volatile
    private var app: FirebaseApp? = null

    /** The configured default app; safe to call from any thread, any number of times. */
    fun ensureInitialised(context: Context): FirebaseApp {
        app?.let { return it }
        return synchronized(this) {
            app ?: initialise(context.applicationContext).also { app = it }
        }
    }

    private fun initialise(context: Context): FirebaseApp {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context)
            ?: FirebaseApp.initializeApp(context, demoOptions())
        FirebaseFirestore.getInstance(app).firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        emulators.connect(app, config)
        return app
    }

    private fun demoOptions(): FirebaseOptions = FirebaseOptions.Builder()
        .setProjectId(DEMO_PROJECT_ID)
        .setApplicationId(DEMO_APPLICATION_ID)
        .setApiKey(DEMO_API_KEY)
        .build()

    companion object {
        /** The Emulator Suite project (`.firebaserc`); `demo-` projects never reach production services. */
        const val DEMO_PROJECT_ID: String = "demo-sprout"
        private const val DEMO_APPLICATION_ID = "1:0:android:sprout"

        /**
         * A format placeholder, not a secret: the emulator-only `demo-sprout` app needs *some* API key, and the
         * Functions SDK's `firebase-iid` dependency refuses one that does not look like `AIza` + 35 characters of
         * `[0-9A-Za-z_-]` (it throws before any request leaves the phone). This one has the right shape and is
         * obviously fake; the emulators accept any key, and a `demo-` project never reaches a real service.
         */
        internal const val DEMO_API_KEY = "AIza" + "00000000000000000000000000000000000"

        const val AUTH_EMULATOR_PORT: Int = 9099
        const val FIRESTORE_EMULATOR_PORT: Int = 8080
        const val FUNCTIONS_EMULATOR_PORT: Int = 5001
    }
}
