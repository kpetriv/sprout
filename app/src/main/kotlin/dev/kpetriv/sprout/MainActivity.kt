package dev.kpetriv.sprout

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.ParentAuthorizationRequester
import dev.kpetriv.sprout.feature.lock.ParentAuthorizationHost
import dev.kpetriv.sprout.navigation.SproutApp
import javax.inject.Inject

/**
 * The single activity. A [FragmentActivity] because `BiometricPrompt` (parent unlock and per-event parent
 * authorisation) needs one; [ParentAuthorizationHost] registers it as the host of the injected
 * [ParentAuthorizationRequester] while the content is composed.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var parentAuthorizationRequester: ParentAuthorizationRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ParentAuthorizationHost()
            SproutTheme {
                SproutApp(requester = parentAuthorizationRequester)
            }
        }
    }
}
