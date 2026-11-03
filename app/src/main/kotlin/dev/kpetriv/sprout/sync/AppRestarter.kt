package dev.kpetriv.sprout.sync

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Restarts the app process into its launcher activity (after a wipe closed the database for good). */
fun interface AppRestarter {
    fun restart()
}

class ProcessAppRestarter @Inject constructor(@param:ApplicationContext private val context: Context) : AppRestarter {
    override fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
