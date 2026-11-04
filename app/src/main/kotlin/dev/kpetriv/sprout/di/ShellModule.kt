package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.feature.lock.ParentKeyRegistry
import dev.kpetriv.sprout.feature.lock.ParentSession
import dev.kpetriv.sprout.feature.parenthome.HouseholdStatusSource
import dev.kpetriv.sprout.feature.settings.AccountControl
import dev.kpetriv.sprout.feature.settings.ChatExportSource
import dev.kpetriv.sprout.feature.settings.ChatHistoryPurger
import dev.kpetriv.sprout.feature.settings.DeviceAdmin
import dev.kpetriv.sprout.feature.settings.ExportWriter
import dev.kpetriv.sprout.feature.settings.HouseholdData
import dev.kpetriv.sprout.feature.settings.HouseholdRules
import dev.kpetriv.sprout.feature.settings.KidLinking
import dev.kpetriv.sprout.feature.settings.LockPreferences
import dev.kpetriv.sprout.feature.settings.ParentConfirmation
import dev.kpetriv.sprout.feature.settings.ParentKeyRegistrar
import dev.kpetriv.sprout.feature.settings.PinChanger
import dev.kpetriv.sprout.home.SyncHouseholdStatusSource
import dev.kpetriv.sprout.navigation.ParentLock
import dev.kpetriv.sprout.settings.ContentResolverExportWriter
import dev.kpetriv.sprout.settings.FunctionsHouseholdData
import dev.kpetriv.sprout.settings.FunctionsKidLinking
import dev.kpetriv.sprout.settings.HouseholdParentKeyRegistry
import dev.kpetriv.sprout.settings.LockParentKeyRegistrar
import dev.kpetriv.sprout.settings.LockSettingsPreferences
import dev.kpetriv.sprout.settings.ParentSessionPinChanger
import dev.kpetriv.sprout.settings.PromptParentConfirmation
import dev.kpetriv.sprout.settings.ReaderChatExportSource
import dev.kpetriv.sprout.settings.RegistryDeviceAdmin
import dev.kpetriv.sprout.settings.RepositoryHouseholdRules
import dev.kpetriv.sprout.settings.RetentionChatHistoryPurger
import dev.kpetriv.sprout.settings.SessionAccountControl

/**
 * What features need from each other, adapted in the app (features never depend on each other): settings' ports over
 * `feature/lock`, `feature/lock`'s parent-key registry over `core/database`, and the shell's [ParentLock] over the
 * parent session.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // One binding per port.
internal abstract class ShellModule {
    @Binds
    abstract fun pinChanger(impl: ParentSessionPinChanger): PinChanger

    @Binds
    abstract fun lockPreferences(impl: LockSettingsPreferences): LockPreferences

    @Binds
    abstract fun parentKeyRegistrar(impl: LockParentKeyRegistrar): ParentKeyRegistrar

    /** `feature/lock` reads and (only through its PIN-gated enrolment and renewal) writes the registered key here. */
    @Binds
    abstract fun parentKeyRegistry(impl: HouseholdParentKeyRegistry): ParentKeyRegistry

    // Settings' household sections (M3 §6).
    @Binds
    abstract fun deviceAdmin(impl: RegistryDeviceAdmin): DeviceAdmin

    @Binds
    abstract fun kidLinking(impl: FunctionsKidLinking): KidLinking

    @Binds
    abstract fun accountControl(impl: SessionAccountControl): AccountControl

    @Binds
    abstract fun householdData(impl: FunctionsHouseholdData): HouseholdData

    @Binds
    abstract fun exportWriter(impl: ContentResolverExportWriter): ExportWriter

    // Settings' M7 sections: retention and game cap (household policy v2), the readable export, delete-now, and the
    // parent confirmation they all ask for (D-M7-21).
    @Binds
    abstract fun householdRules(impl: RepositoryHouseholdRules): HouseholdRules

    @Binds
    abstract fun chatExportSource(impl: ReaderChatExportSource): ChatExportSource

    @Binds
    abstract fun chatHistoryPurger(impl: RetentionChatHistoryPurger): ChatHistoryPurger

    @Binds
    abstract fun parentConfirmation(impl: PromptParentConfirmation): ParentConfirmation

    /** The dashboard's trial banner, sync line, refused events and new-device cards. */
    @Binds
    abstract fun householdStatusSource(impl: SyncHouseholdStatusSource): HouseholdStatusSource

    companion object {
        @Provides
        fun parentLock(session: ParentSession): ParentLock = ParentLock(session::lock)
    }
}
