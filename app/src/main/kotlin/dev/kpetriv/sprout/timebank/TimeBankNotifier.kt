package dev.kpetriv.sprout.timebank

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.R
import dev.kpetriv.sprout.core.model.KidId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The ongoing "screen time is running" notification (M4 §5.3, D-M4-9; no foreground service): channel [CHANNEL_ID]
 * (low importance: silent, no heads-up), not dismissable, with a chronometer counting down to the moment the balance
 * runs out. One per kid, tagged [TAG] with the kid id; it names neither the kid nor anything else personal. Posting
 * needs `POST_NOTIFICATIONS` on Android 13+ (asked for by the time bank screen); without it nothing is shown and the
 * timer still works.
 */
@Singleton
class TimeBankNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Shows (or replaces) [kidId]'s notification for a session started at [startedAt] with [balanceMinutes] left. */
    fun show(kidId: KidId, startedAt: Instant, balanceMinutes: Long) {
        if (!canPost()) return
        ensureChannel()
        val endsAt = startedAt + balanceMinutes.coerceAtLeast(0).minutes
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timebank)
            .setContentTitle(context.getString(R.string.timebank_notification_title))
            .setContentText(context.getString(R.string.timebank_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(endsAt.toEpochMilliseconds())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addExtras(Bundle().apply { putString(EXTRA_KID_ID, kidId.value) })
            .build()
        try {
            manager.notify(TAG, idOf(kidId), notification)
        } catch (expected: SecurityException) {
            // Permission withdrawn between the check and the post: the timer works without the notification.
        }
    }

    fun cancel(kidId: KidId) = manager.cancel(TAG, idOf(kidId))

    /** Every time bank notification (a removal wipes the household). */
    fun cancelAll() {
        activeKids().forEach(::cancel)
    }

    /** The kids whose notification is showing. */
    fun activeKids(): Set<KidId> = manager.activeNotifications
        .filter { it.tag == TAG && it.notification.extras.getString(EXTRA_KID_ID) != null }
        .mapNotNull { status -> status.notification.extras.getString(EXTRA_KID_ID)?.let(::KidId) }
        .toSet()

    private fun canPost(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) && manager.areNotificationsEnabled()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.timebank_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun idOf(kidId: KidId): Int = kidId.value.hashCode()

    companion object {
        const val CHANNEL_ID: String = "timebank"
        const val TAG: String = "timebank"
        internal const val EXTRA_KID_ID: String = "dev.kpetriv.sprout.timebank.KID_ID"
    }
}
