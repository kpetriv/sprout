package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What this build is and where its policies are published (M8 §1). The app provides it from `BuildConfig`:
 * [versionName] and [versionCode] from git (D-M8-4), [privacyUrl] and [securityUrl] from the `sprout.privacyUrl` and
 * `sprout.securityUrl` Gradle properties (null only in a release build that was never configured to ship).
 */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val privacyUrl: String?,
    val securityUrl: String?,
)

/**
 * Opens a web page outside Sprout (the app binds an `ACTION_VIEW` intent to the browser). Returns false when nothing
 * on the phone can open it. Only `https://` and `http://` URLs reach it.
 */
fun interface UrlOpener {
    fun open(url: String): Boolean
}

/** The open-source libraries this build ships, generated from the release classpath's POMs at build time (D-M8-9). */
fun interface OpenSourceLicences {
    suspend fun load(): List<OpenSourceLibrary>
}

/**
 * One library on the runtime classpath.
 *
 * @property module `group:name`.
 * @property url the project's page, when its POM names one.
 */
data class OpenSourceLibrary(
    val name: String,
    val module: String,
    val version: String,
    val url: String?,
    val licences: List<LicenceLink>,
) {
    /** What a tap opens: the first licence's text, else the project's page. */
    val link: String?
        get() = licences.firstNotNullOfOrNull { it.url?.takeIf(String::isWebUrl) } ?: url?.takeIf(String::isWebUrl)
}

/** A licence as the POM names it; [url] points at its text. */
data class LicenceLink(val name: String, val url: String?)

/**
 * Settings → About and its open-source licences page.
 *
 * @property libraries null while loading or after [licencesFailed].
 */
data class AboutUiState(
    val versionName: String = "",
    val versionCode: Long = 0,
    val hasPrivacyPolicy: Boolean = false,
    val hasSecurityPolicy: Boolean = false,
    val libraries: List<OpenSourceLibrary>? = null,
    val licencesFailed: Boolean = false,
    val message: SettingsMessage? = null,
)

sealed interface AboutEvent {
    data object OpenPrivacyPolicy : AboutEvent

    data object OpenSecurityPolicy : AboutEvent

    /** A row of the licences page: opens [OpenSourceLibrary.link]. */
    data class OpenLibrary(val library: OpenSourceLibrary) : AboutEvent

    data object MessageShown : AboutEvent
}

/** Shows [AppBuildInfo], opens the policy pages through [UrlOpener] and loads [OpenSourceLicences] once. */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val info: AppBuildInfo,
    private val urlOpener: UrlOpener,
    private val licences: OpenSourceLicences,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AboutUiState(
            versionName = info.versionName,
            versionCode = info.versionCode,
            hasPrivacyPolicy = info.privacyUrl?.isWebUrl() == true,
            hasSecurityPolicy = info.securityUrl?.isWebUrl() == true,
        ),
    )
    val state: StateFlow<AboutUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            var loaded: List<OpenSourceLibrary>? = null
            val ok = attempt { loaded = licences.load() }
            mutableState.update { it.copy(libraries = loaded, licencesFailed = !ok) }
        }
    }

    fun onEvent(event: AboutEvent) {
        when (event) {
            AboutEvent.OpenPrivacyPolicy -> open(info.privacyUrl)
            AboutEvent.OpenSecurityPolicy -> open(info.securityUrl)
            is AboutEvent.OpenLibrary -> open(event.library.link)
            AboutEvent.MessageShown -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun open(url: String?) {
        val opened = url?.isWebUrl() == true && runCatching { urlOpener.open(url) }.getOrDefault(false)
        if (!opened) mutableState.update { it.copy(message = SettingsMessage.LINK_NOT_OPENED) }
    }
}

/** Only web links leave the app: a POM or a property can name any scheme. */
internal fun String.isWebUrl(): Boolean = startsWith("https://") || startsWith("http://")
