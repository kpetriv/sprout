package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * A photo attached to a chore submission: the bare [fileName] of the encrypted file in the app's private photo
 * store (never a path, so it cannot point elsewhere) and the lower-case hex [sha256] of its plaintext, which lets
 * a receiver check the bytes it decrypts. The file name follows the id alphabet (see [ID_PATTERN]) and cannot be
 * `.` or `..`.
 */
@Serializable
data class PhotoRef(val fileName: String, val sha256: String) {
    init {
        require(ID_PATTERN.matches(fileName) && fileName != "." && fileName != "..") {
            "fileName must be a bare file name of 1 to $MAX_ID_LENGTH characters from [A-Za-z0-9._-]"
        }
        require(SHA256_HEX.matches(sha256)) { "sha256 must be 64 lower-case hex characters" }
    }
}
