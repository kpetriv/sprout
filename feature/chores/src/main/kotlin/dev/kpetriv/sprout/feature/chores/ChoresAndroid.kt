package dev.kpetriv.sprout.feature.chores

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.MoneyLabels
import javax.inject.Inject

/** Turns a decrypted chore photo into something Compose can draw; null when the bytes are not an image. */
fun interface PhotoDecoder {
    fun decode(bytes: ByteArray): ImageBitmap?

    companion object {
        /** [BitmapFactory] decoding. */
        val Platform: PhotoDecoder = PhotoDecoder { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
}

/**
 * The reason shown in the parent authorisation prompt for one reward event: "Pay Leo £2.50 for Make bed". The
 * prompt is a security surface, so the text names who is paid, how much and for what.
 */
internal fun interface ApprovalReasonText {
    fun reason(kidName: String, choreTitle: String, amount: Money, currency: Currency): String
}

/** [ApprovalReasonText] from this module's string resources, in the current locale. */
internal class ResourceApprovalReasonText @Inject constructor(@ApplicationContext private val context: Context) :
    ApprovalReasonText {
    override fun reason(kidName: String, choreTitle: String, amount: Money, currency: Currency): String {
        val resources = context.resources
        val formatter = MoneyFormatter(resources.configuration.locales[0], MoneyLabels.fromResources(resources))
        return resources.getString(
            R.string.feature_chores_authorize_reason,
            kidName,
            formatter.format(amount, currency),
            choreTitle,
        )
    }
}
