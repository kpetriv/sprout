package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.feature.settings.AppBuildInfo
import dev.kpetriv.sprout.feature.settings.OpenSourceLicences
import dev.kpetriv.sprout.feature.settings.UrlOpener
import dev.kpetriv.sprout.settings.AssetOpenSourceLicences
import dev.kpetriv.sprout.settings.IntentUrlOpener

/**
 * Settings → About (M8 §1): the build's version from git (D-M8-4), the policy URLs from the `sprout.privacyUrl` and
 * `sprout.securityUrl` Gradle properties, the browser and the generated licence list.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AboutModule {
    @Binds
    abstract fun urlOpener(impl: IntentUrlOpener): UrlOpener

    @Binds
    abstract fun openSourceLicences(impl: AssetOpenSourceLicences): OpenSourceLicences

    companion object {
        @Provides
        fun appBuildInfo(): AppBuildInfo = AppBuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            privacyUrl = BuildConfig.PRIVACY_URL,
            securityUrl = BuildConfig.SECURITY_URL,
        )
    }
}
