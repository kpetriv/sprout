package dev.kpetriv.sprout.core.crypto

import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Monotonic time since boot plus an identifier for the current boot. [PinLockoutPolicy] measures lockouts with
 * this instead of the wall clock, which the user can move.
 */
interface BootClock {
    /** Time since boot, including deep sleep. Only comparable with values read during the same [bootId]. */
    fun elapsedRealtime(): Duration

    /** Identifies the current boot; a different value means the device rebooted and [elapsedRealtime] restarted. */
    fun bootId(): String
}

/**
 * [BootClock] over `SystemClock.elapsedRealtime()` and the kernel's per-boot random UUID in
 * `/proc/sys/kernel/random/boot_id`, read once per process.
 *
 * If that file cannot be read, the boot id falls back to `Build.FINGERPRINT + Build.TIME`, which does **not**
 * change on reboot. Reboots are then detected only when elapsed realtime is earlier than a stored lockout start,
 * which [PinLockoutPolicy] also treats as a fresh, full lockout.
 */
class AndroidBootClock internal constructor(private val bootIdFile: File) : BootClock {
    constructor() : this(File(BOOT_ID_PATH))

    private val cachedBootId: String by lazy { readBootId() }

    override fun elapsedRealtime(): Duration = SystemClock.elapsedRealtime().milliseconds

    override fun bootId(): String = cachedBootId

    private fun readBootId(): String {
        val fromKernel = try {
            bootIdFile.readText().trim()
        } catch (expected: IOException) {
            ""
        } catch (expected: SecurityException) {
            ""
        }
        return fromKernel.takeIf { it.isNotEmpty() && it.length <= MAX_BOOT_ID_LENGTH }
            ?: "${Build.FINGERPRINT}${Build.TIME}".take(MAX_BOOT_ID_LENGTH)
    }

    internal companion object {
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

        /** The kernel value is a 36-character UUID; anything far longer is not a boot id. */
        const val MAX_BOOT_ID_LENGTH = 256
    }
}
