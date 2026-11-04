package dev.kpetriv.sprout.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.feature.settings.LicenceLink
import dev.kpetriv.sprout.feature.settings.OpenSourceLibrary
import dev.kpetriv.sprout.feature.settings.OpenSourceLicences
import dev.kpetriv.sprout.feature.settings.UrlOpener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Settings' [UrlOpener]: an `ACTION_VIEW` intent for the browser; false when nothing on the phone handles it. */
class IntentUrlOpener @Inject constructor(@ApplicationContext private val context: Context) : UrlOpener {
    override fun open(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Settings' [OpenSourceLicences] over the asset build-logic generates from the variant's runtime classpath
 * (`OpenSourceLicences.kt`, D-M8-9).
 */
class AssetOpenSourceLicences @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(SproutDispatchers.IO) private val io: CoroutineDispatcher,
) : OpenSourceLicences {
    override suspend fun load(): List<OpenSourceLibrary> = withContext(io) {
        parseOpenSourceLicences(context.assets.open(OPEN_SOURCE_LICENCES_ASSET).use { it.readBytes().decodeToString() })
    }
}

/** The asset's name; build-logic writes it (`OPEN_SOURCE_LICENCES_ASSET`). */
internal const val OPEN_SOURCE_LICENCES_ASSET = "open_source_licences.json"

/** Parses the generated asset: `{"libraries":[{"name","module","version","url","licences":[{"name","url"}]}]}`. */
internal fun parseOpenSourceLicences(json: String): List<OpenSourceLibrary> =
    LicenceJson.decodeFromString(LicencesAsset.serializer(), json).libraries.map { library ->
        OpenSourceLibrary(
            name = library.name,
            module = library.module,
            version = library.version,
            url = library.url,
            licences = library.licences.map { LicenceLink(it.name, it.url) },
        )
    }

private val LicenceJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class LicencesAsset(val libraries: List<LibraryEntry>)

@Serializable
private data class LibraryEntry(
    val name: String,
    val module: String,
    val version: String,
    val url: String? = null,
    val licences: List<LicenceEntry> = emptyList(),
)

@Serializable
private data class LicenceEntry(val name: String, val url: String? = null)
