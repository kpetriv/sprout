package dev.kpetriv.sprout.feature.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.security.Signature

/**
 * Shows the unlock prompt for a [BiometricUnlockRequest.Ready] and returns the authenticated signature, or null
 * when the parent dismissed it. Throws [BiometricErrorException] on any other prompt error. The UI obtains one with
 * [rememberBiometricUnlockPrompter] and passes it to [PinEntryViewModel] in [PinEntryEvent.UseBiometric].
 */
fun interface BiometricUnlockPrompter {
    suspend fun prompt(request: BiometricUnlockRequest.Ready): Signature?
}

/** A [BiometricUnlockPrompter] over this composable's `FragmentActivity`, or null under any other activity. */
@Composable
fun rememberBiometricUnlockPrompter(): BiometricUnlockPrompter? {
    val context = LocalContext.current
    return remember(context) {
        val activity = context.findFragmentActivity() ?: return@remember null
        BiometricUnlockPrompter { request ->
            val texts = PromptTexts(
                title = activity.getString(R.string.feature_lock_unlock_prompt_title),
                subtitle = null,
                negativeButton = activity.getString(R.string.feature_lock_use_pin),
                confirmationRequired = false,
            )
            try {
                activity.authenticateStrong(texts, request.signature)
            } catch (expected: BiometricCancelledException) {
                null
            }
        }
    }
}
