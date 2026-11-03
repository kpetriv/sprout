package dev.kpetriv.sprout.core.syncfirebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.functions.FirebaseFunctions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initialises the default [FirebaseApp] once per process (M3 §5 "Bootstrap").
 *
 * With `google-services.json` the app is already initialised by Firebase's init provider and is used as is. Without
 * it (no real project yet, D-M3-1) the app is initialised programmatically for the `demo-sprout` project, which the
 * Emulator Suite accepts without credentials. When [FirebaseConfig.emulatorHost] is set, Auth, Firestore and Functions
 * are pointed at the emulators. Firestore keeps a memory cache only: Room is the source of truth on the device, so
 * nothing synced is persisted twice.
 */
@Singleton
class FirebaseBootstrap @Inject constructor(private val config: FirebaseConfig) {
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
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
        config.emulatorHost?.let { host ->
            FirebaseAuth.getInstance(app).useEmulator(host, AUTH_EMULATOR_PORT)
            firestore.useEmulator(host, FIRESTORE_EMULATOR_PORT)
            FirebaseFunctions.getInstance(app, config.region).useEmulator(host, FUNCTIONS_EMULATOR_PORT)
        }
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
        private const val DEMO_API_KEY = "demo"

        const val AUTH_EMULATOR_PORT: Int = 9099
        const val FIRESTORE_EMULATOR_PORT: Int = 8080
        const val FUNCTIONS_EMULATOR_PORT: Int = 5001
    }
}
