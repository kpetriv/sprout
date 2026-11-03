package dev.kpetriv.sprout.core.crypto

import java.util.concurrent.ConcurrentHashMap

/** [BlobStorage] in a map. Test fixture only; production binds [FilesDirBlobStorage]. */
class InMemoryBlobStorage : BlobStorage {
    private val blobs = ConcurrentHashMap<String, ByteArray>()

    override suspend fun read(name: String): ByteArray? = blobs[name]?.copyOf()

    override suspend fun write(name: String, blob: ByteArray) {
        blobs[name] = blob.copyOf()
    }

    override suspend fun delete(name: String) {
        blobs.remove(name)
    }
}
