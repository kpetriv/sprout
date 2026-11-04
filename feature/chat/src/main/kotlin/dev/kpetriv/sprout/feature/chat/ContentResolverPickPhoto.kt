package dev.kpetriv.sprout.feature.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.ui.PickedImageReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The default [PickPhoto]: `core/ui`'s [PickedImageReader] with the chat limits ([PickedImageReader.Limits.CHAT]):
 * decoded with the EXIF rotation applied, at most 1280 px on the long side, JPEG from quality 80 down until it fits
 * 500 KB (M4 §2). Re-encoding drops every EXIF field, location included.
 */
class ContentResolverPickPhoto @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(SproutDispatchers.IO) private val io: CoroutineDispatcher,
) : PickPhoto {
    override suspend fun read(uri: String): Result<ByteArray> = withContext(io) {
        PickedImageReader(context.contentResolver).read(uri, PickedImageReader.Limits.CHAT)
    }
}
