package dev.kpetriv.sprout.core.domainledger.chat

/**
 * Size limits of the chat wire formats (M4 §1.3, §2). The Firestore rules enforce the same numbers; clients check
 * them before writing so a write is never refused for size.
 */
object ChatLimits {
    /** Largest message ciphertext in bytes (AEAD output: version, nonce, ciphertext, tag). */
    const val MAX_MESSAGE_CIPHERTEXT_BYTES: Int = 8192

    /** Longest voice note. */
    const val MAX_VOICE_NOTE_MILLIS: Long = 30_000

    /** Longest preset id (presets are fixed ids such as `thank-you`, resolved to localised text on display). */
    const val MAX_PRESET_ID_LENGTH: Int = 64

    /** Largest photo edge accepted in a photo message's [ChatPhotoRef]. Clients downscale to [PHOTO_MAX_EDGE_PX]. */
    const val MAX_PHOTO_DIMENSION: Int = 8192

    /** Most wraps in one `thread_key` document (one per member device). */
    const val MAX_THREAD_KEY_WRAPS: Int = 16

    /** Longest base64 `wrapped` value in a `thread_key` document. A wrapped 32-byte key is 84 characters. */
    const val MAX_WRAPPED_LENGTH: Int = 128

    /** Largest chunk of a blob in bytes (Firestore `bytes` field; rules check `data.size() <= 716800`). */
    const val MAX_BLOB_CHUNK_BYTES: Int = 700 * 1024

    /** Most chunks per blob. */
    const val MAX_BLOB_CHUNKS: Int = 2

    /** Largest blob ciphertext in bytes. */
    const val MAX_BLOB_BYTES: Int = MAX_BLOB_CHUNK_BYTES * MAX_BLOB_CHUNKS

    /** Client-side photo processing before encryption: longest edge in pixels. */
    const val PHOTO_MAX_EDGE_PX: Int = 1280

    /** Client-side photo processing before encryption: JPEG quality. */
    const val PHOTO_JPEG_QUALITY: Int = 80

    /** Client-side photo processing before encryption: target size in bytes (lower the quality to reach it). */
    const val PHOTO_TARGET_BYTES: Int = 500 * 1024

    /** Client-side voice recording: AAC-LC bit rate in bits per second (about 120 KB for 30 s). */
    const val VOICE_BIT_RATE: Int = 32_000
}
