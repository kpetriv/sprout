package dev.kpetriv.sprout.feature.chores

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * The `FileProvider` the camera app writes chore photos through. A subclass so its manifest entry never collides
 * with another library's `androidx.core.content.FileProvider`. Authority: `<applicationId>.feature.chores.photos`
 * ([ChorePhotoFiles.authority]); it only exposes `cacheDir/chore_photos/`.
 */
class ChorePhotoFileProvider : FileProvider(R.xml.feature_chores_photo_paths)

/**
 * Clears the plaintext capture cache (`cacheDir/chore_photos/`, audit L1). The camera app writes an unencrypted JPEG
 * there; it is deleted as soon as it has been read, but a crash, a process death while the camera is in front, or a
 * cancelled capture can leave one behind. The app calls [clear] at startup (`AppInitializer`), and every capture
 * clears the directory before it starts.
 */
class ChorePhotoCache @Inject constructor(@ApplicationContext private val context: Context) {
    /** Deletes every file in the capture directory. Returns how many were deleted. Blocking file IO: call off main. */
    fun clear(): Int = ChorePhotoFiles.clear(context)
}

/**
 * Temporary capture files for [rememberChorePhotoCapture]. Files are deleted as soon as they have been read, and the
 * directory is emptied before each capture and at app start ([ChorePhotoCache]).
 */
internal object ChorePhotoFiles {
    private const val DIRECTORY = "chore_photos"
    private const val AUTHORITY_SUFFIX = ".feature.chores.photos"
    private const val MAX_SIDE_PX = 1_600
    private const val JPEG_QUALITY = 85
    private const val DEGREES_90 = 90f
    private const val DEGREES_180 = 180f
    private const val DEGREES_270 = 270f

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** A fresh capture file, after deleting whatever earlier captures left in the directory. */
    fun newCaptureFile(context: Context): File {
        clear(context)
        val directory = directoryOf(context).apply { mkdirs() }
        return File(directory, "capture-${UUID.randomUUID()}.jpg")
    }

    /** Deletes every file (and nested directory) in the capture directory; returns how many entries were deleted. */
    fun clear(context: Context): Int = directoryOf(context).listFiles().orEmpty().count { it.deleteRecursively() }

    private fun directoryOf(context: Context): File = File(context.cacheDir, DIRECTORY)

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /**
     * The captured photo as a JPEG of at most [MAX_SIDE_PX] on its longer side, upright, without the camera's
     * EXIF metadata (re-encoding drops location and device details). Null when the file holds no image.
     */
    @Suppress("ReturnCount") // Guard clauses: each early return is a precondition that is not met.
    fun readAndShrink(file: File): ByteArray? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val upright = rotateUpright(decoded, orientationOf(file))
        return ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private fun orientationOf(file: File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotateUpright(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> DEGREES_90
            ExifInterface.ORIENTATION_ROTATE_180 -> DEGREES_180
            ExifInterface.ORIENTATION_ROTATE_270 -> DEGREES_270
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

/**
 * "Add photo": returns a function that opens the camera app ([ActivityResultContracts.TakePicture]) writing into a
 * cache file shared through [ChorePhotoFileProvider]. The file is read, shrunk and deleted, then [onCaptured] gets
 * the JPEG bytes (the ViewModel encrypts them through `PhotoStore`). [onFailed] runs when there is no camera app or
 * the photo cannot be read; a cancelled capture calls neither.
 */
@Composable
internal fun rememberChorePhotoCapture(
    onCaptured: (ByteArray) -> Unit,
    onFailed: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onFailed)
    // Survives the activity being recreated while the camera app is in front.
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPath ?: return@rememberLauncherForActivityResult
        pendingPath = null
        scope.launch {
            val file = File(path)
            val bytes = withContext(ioDispatcher) {
                try {
                    if (success) ChorePhotoFiles.readAndShrink(file) else null
                } finally {
                    file.delete()
                }
            }
            when {
                bytes != null -> captured(bytes)
                success -> failed()
            }
        }
    }
    return {
        val file = ChorePhotoFiles.newCaptureFile(context)
        pendingPath = file.path
        try {
            launcher.launch(ChorePhotoFiles.uriFor(context, file))
        } catch (_: ActivityNotFoundException) {
            pendingPath = null
            failed()
        }
    }
}
