package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.chat.ChatMaintenance
import dev.kpetriv.sprout.chat.MediaVoicePlayer
import dev.kpetriv.sprout.chat.MediaVoiceRecorder
import dev.kpetriv.sprout.chat.RoomChatThreads
import dev.kpetriv.sprout.chat.RoomMessageReader
import dev.kpetriv.sprout.chat.RoomMessageSender
import dev.kpetriv.sprout.chat.RoomThreadKeyStatus
import dev.kpetriv.sprout.chat.TransportBlobLoader
import dev.kpetriv.sprout.feature.chat.BlobLoader
import dev.kpetriv.sprout.feature.chat.ChatThreads
import dev.kpetriv.sprout.feature.chat.MessageReader
import dev.kpetriv.sprout.feature.chat.MessageSender
import dev.kpetriv.sprout.feature.chat.ThreadKeyStatus
import dev.kpetriv.sprout.feature.chat.VoicePlayer
import dev.kpetriv.sprout.feature.chat.VoiceRecorder
import dev.kpetriv.sprout.feature.chores.RemotePhotoLoader
import dev.kpetriv.sprout.feature.timebank.TimeBankControl
import dev.kpetriv.sprout.feature.timebank.TimeBankStateSource
import dev.kpetriv.sprout.sync.SyncUpkeep
import dev.kpetriv.sprout.timebank.LedgerTimeBankStateSource
import dev.kpetriv.sprout.timebank.TimeBankAutoStop
import dev.kpetriv.sprout.timebank.UseCaseTimeBankControl
import dev.kpetriv.sprout.timebank.WorkManagerTimeBankAutoStop
import dev.kpetriv.sprout.work.PhotoUploadScheduler
import dev.kpetriv.sprout.work.WorkManagerPhotoUploadScheduler

/**
 * The chat ports (`feature/chat`, M4 §5.1), per ViewModel: the adapters read the screen's arguments
 * ([dev.kpetriv.sprout.chat.ChatPerspective]) to tell a kid's view from a parent's.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal abstract class ChatPortsModule {
    @Binds
    abstract fun chatThreads(impl: RoomChatThreads): ChatThreads

    @Binds
    abstract fun messageReader(impl: RoomMessageReader): MessageReader

    @Binds
    abstract fun messageSender(impl: RoomMessageSender): MessageSender

    @Binds
    abstract fun threadKeyStatus(impl: RoomThreadKeyStatus): ThreadKeyStatus

    @Binds
    abstract fun voiceRecorder(impl: MediaVoiceRecorder): VoiceRecorder

    @Binds
    abstract fun voicePlayer(impl: MediaVoicePlayer): VoicePlayer
}

/** Blobs, the time bank ports, the photo upload and the M4 sync upkeep. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatModule {
    @Binds
    abstract fun blobLoader(impl: TransportBlobLoader): BlobLoader

    @Binds
    abstract fun remotePhotoLoader(impl: TransportBlobLoader): RemotePhotoLoader

    @Binds
    abstract fun timeBankStateSource(impl: LedgerTimeBankStateSource): TimeBankStateSource

    @Binds
    abstract fun timeBankControl(impl: UseCaseTimeBankControl): TimeBankControl

    @Binds
    abstract fun timeBankAutoStop(impl: WorkManagerTimeBankAutoStop): TimeBankAutoStop

    @Binds
    abstract fun photoUploadScheduler(impl: WorkManagerPhotoUploadScheduler): PhotoUploadScheduler

    @Binds
    abstract fun syncUpkeep(impl: ChatMaintenance): SyncUpkeep
}
