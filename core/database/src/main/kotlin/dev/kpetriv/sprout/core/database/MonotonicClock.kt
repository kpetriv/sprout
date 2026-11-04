package dev.kpetriv.sprout.core.database

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A clock the user cannot set (D-M4-24): time since the device booted, and which boot that is. The time bank measures
 * a session with it when the device has not restarted since the start, so moving the wall clock does not change what
 * is debited.
 */
interface MonotonicClock {
    /** Milliseconds since boot, deep sleep included (Android `SystemClock.elapsedRealtime`). */
    fun elapsedRealtimeMs(): Long

    /** The number of this boot (`Settings.Global.BOOT_COUNT`), or null when the device does not say. */
    fun bootCount(): Long?
}

/** The device's [MonotonicClock]. */
class AndroidMonotonicClock @Inject constructor(@ApplicationContext private val context: Context) : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Long? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN).takeIf { it >= 0 }
            ?.toLong()

    private companion object {
        const val UNKNOWN = -1
    }
}
